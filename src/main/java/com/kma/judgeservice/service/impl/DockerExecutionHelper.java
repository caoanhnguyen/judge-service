package com.kma.judgeservice.service.impl;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DockerExecutionHelper {

    private final DockerClient dockerClient;

    @Value("${oj.judge.container.image-name:oj-sandbox:v1}")
    private String dockerImageName;

    @Value("${oj.judge.container.work-dir}")
    private String containerWorkDir;

    @Value("${oj.judge.container.testcase-dir}")
    private String containerTestcaseDir;

    @Value("${oj.judge.container.pids-limit}")
    private long pidsLimit;

    @Value("${oj.judge.container.cpus}")
    private double cpus;

    @Value("${oj.judge.container.readonly-rootfs}")
    private boolean readonlyRootfs;

    @Value("${oj.judge.container.drop-caps}")
    private boolean dropCaps;

    // Object chứa thông số trả về từ Docker
    public static class ExecResult {
        public long timeTaken;
        public boolean isTle;
        public int exitCode;
        public String outputLog;

        public ExecResult(long timeTaken, boolean isTle, int exitCode, String outputLog) {
            this.timeTaken = timeTaken;
            this.isTle = isTle;
            this.exitCode = exitCode;
            this.outputLog = outputLog;
        }
    }

    /**
     * Khởi tạo và bật Container Sandbox.
     * @param hostWorkDir Thư mục chứa code/output trên máy chủ.
     * @param hostTestcaseDir Thư mục chứa testcase (có thể null nếu RunCode không xài chung testcase).
     * @param memoryLimitMb Giới hạn RAM.
     * @return Container ID.
     */
    public String startSandboxContainer(Path hostWorkDir, Path hostTestcaseDir, long memoryLimitMb) {
        long memoryLimitBytes = memoryLimitMb * 1024L * 1024L;
        long nanoCpus = (long) (cpus * 1_000_000_000L); // Quy đổi từ số Core ra NanoCPUs cho Docker hiểu

        List<Bind> binds = new ArrayList<>();
        binds.add(new Bind(hostWorkDir.toAbsolutePath().toString(), new Volume(containerWorkDir)));
        if (hostTestcaseDir != null) {
            binds.add(new Bind(hostTestcaseDir.toAbsolutePath().toString(), new Volume(containerTestcaseDir)));
        }

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(binds)
                .withMemory(memoryLimitBytes)
                .withMemorySwap(memoryLimitBytes)
                .withPidsLimit(pidsLimit)
                .withNetworkMode("none")
                .withNanoCPUs(nanoCpus)
                .withReadonlyRootfs(readonlyRootfs)
                .withAutoRemove(true);

        // Riêng cái CapDrop nó nhận mảng, nên ta check if rồi gán
        if (dropCaps) {
            hostConfig.withCapDrop(Capability.ALL);
        }

        CreateContainerResponse container = dockerClient.createContainerCmd(dockerImageName)
                .withHostConfig(hostConfig)
                .withWorkingDir(containerWorkDir)
                .exec();

        dockerClient.startContainerCmd(container.getId()).exec();
        return container.getId();
    }

    /**
     * Dọn dẹp/Ép dừng Container.
     */
    public void cleanupContainer(String containerId) {
        if (containerId != null) {
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception e) {
                log.warn("Lỗi khi xoá container {}: {}", containerId, e.getMessage());
            }
        }
    }

    /**
     * Thực thi một lệnh bash (sh -c) bên trong Container.
     */
    public ExecResult executeCommand(String containerId, String bashCommand, long timeLimitMs) {
        try {
            long startTime = System.currentTimeMillis();

            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withWorkingDir(containerWorkDir)
                    .withCmd("sh", "-c", bashCommand)
                    .exec();

            String execId = execCreateCmdResponse.getId();
            StringBuilder dockerLog = new StringBuilder();

            boolean isCompleted = dockerClient.execStartCmd(execId)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame item) {
                            if (dockerLog.length() < 5000) {
                                dockerLog.append(new String(item.getPayload()));
                            }
                            super.onNext(item);
                        }
                    })
                    .awaitCompletion(timeLimitMs, java.util.concurrent.TimeUnit.MILLISECONDS);

            long executionTime = System.currentTimeMillis() - startTime;

            if (!isCompleted) {
                return new ExecResult(timeLimitMs, true, -1, dockerLog.toString());
            }

            Long exitCodeLong = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            int exitCode = (exitCodeLong != null) ? exitCodeLong.intValue() : 0;

            return new ExecResult(executionTime, false, exitCode, dockerLog.toString());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(0, false, -1, "Interrupted");
        }
    }
}