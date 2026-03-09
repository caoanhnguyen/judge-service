package com.kma.judgeservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.kma.judgeservice.dto.JudgeSdi;
import com.kma.judgeservice.dto.ProblemInfo;
import com.kma.judgeservice.dto.TestCaseInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeServiceImpl implements JudgeService {

    private final DockerClient dockerClient;
    private final ObjectMapper objectMapper;

    @Value("${oj.judge.testcases-dir}")
    private String testcasesDir;

    @Value("${oj.judge.work-dir}")
    private String workDir;

    @Override
    public String judge(JudgeSdi sdi) {
        String submissionId = sdi.getSubmissionId().toString();

        Path hostTestcaseDir = Paths.get(testcasesDir, sdi.getProblemId().toString());
        Path hostWorkDir = Paths.get(workDir, "submissions", submissionId);

        try {
            File infoFile = hostTestcaseDir.resolve("info.json").toFile();
            if (!infoFile.exists()) {
                return "System Error: Testcase data not found";
            }
            ProblemInfo problemInfo = objectMapper.readValue(infoFile, ProblemInfo.class);

            Files.createDirectories(hostWorkDir);

            // Ghi file source code dựa vào tên sourceName do Core chỉ định
            Path sourceFile = hostWorkDir.resolve(sdi.getSourceName());
            Files.writeString(sourceFile, sdi.getSourceCode());

            // --- ÁP DỤNG MEMORY LIMIT ---
            // Lấy Memory Limit (MB) từ Core, mặc định cho 256MB nếu Core không gửi hoặc gửi giá trị không hợp lệ
            long memoryLimitMb = sdi.getMemoryLimitAllowance() != null && sdi.getMemoryLimitAllowance() > 0
                    ? sdi.getMemoryLimitAllowance() : 256L;
            long memoryLimitBytes = memoryLimitMb * 1024L * 1024L;

            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withBinds(
                            new Bind(hostWorkDir.toAbsolutePath().toString(), new Volume("/sandbox/app")),
                            new Bind(hostTestcaseDir.toAbsolutePath().toString(), new Volume("/testcases"))
                    )
                    .withMemory(memoryLimitBytes) // Ép Docker không được ăn quá số RAM quy định
                    .withMemorySwap(memoryLimitBytes) // Chặn luôn swap (RAM ảo) để tránh lách luật
                    .withAutoRemove(true);

            CreateContainerResponse container = dockerClient.createContainerCmd("oj-sandbox:v1")
                    .withHostConfig(hostConfig)
                    .withCmd("tail", "-f", "/dev/null")
                    .exec();

            dockerClient.startContainerCmd(container.getId()).exec();
            String containerId = container.getId();
            log.info("Started Sandbox for Submission {}: {} with {}MB RAM", submissionId, containerId, memoryLimitMb);

            // 1. Giai đoạn Biên dịch (Sử dụng isCompiled để Check an toàn)
            if (sdi.isCompiled() && sdi.getCompileCommand() != null) {
                String compileCmd = sdi.getCompileCommand().trim();
                if (!compileCmd.isEmpty()) {
                    // Ép thời gian build cố định là 15 giây (15000ms), đủ thoải mái cho Java/C++
                    ExecResult compileResult = executeCommandInContainer(containerId, compileCmd, 15000);
                    if (compileResult.isTle || compileResult.exitCode != 0) {
                        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                        return "Verdict: CE (Compile Error) | Score: 0/100";
                    }
                }
            }

            // 2. Giai đoạn Chấm điểm
            int totalScore = 0;
            long maxExecutionTime = 0;
            String finalVerdict = "AC";

            for (TestCaseInfo testCase : problemInfo.getTestCases()) {
                String inFileName = testCase.getInputName();
                String outFileName = testCase.getOutputName();
                String userOutName = "user_" + outFileName;

                // Dùng thẳng template và time limit từ Core
                String runCmd = String.format(sdi.getRunCommand(), inFileName, userOutName);
                ExecResult result = executeCommandInContainer(containerId, runCmd, sdi.getTimeLimitAllowance());

                if (result.isTle) {
                    finalVerdict = "TLE (Time Limit Exceeded) at " + inFileName;
                    break;
                }

                maxExecutionTime = Math.max(maxExecutionTime, result.timeTaken);

                if (result.exitCode != 0) {
                    finalVerdict = "RE (Runtime Error) at " + inFileName + " [Exit Code: " + result.exitCode + "]";
                    break;
                }

                Path userOutputPath = hostWorkDir.resolve(userOutName);
                Path expectedOutputPath = hostTestcaseDir.resolve(outFileName);

                if (Files.exists(userOutputPath)) {
                    String userOutput = Files.readString(userOutputPath);
                    String expectedOutput = Files.readString(expectedOutputPath);

                    if (checkAnswer(userOutput, expectedOutput)) {
                        totalScore += testCase.getScore();
                        log.info("Testcase {} AC! Time: {}ms", inFileName, result.timeTaken);
                    } else {
                        finalVerdict = "WA (Wrong Answer) at " + inFileName;
                        break;
                    }
                } else {
                    finalVerdict = "System Error: Output file missing";
                    break;
                }
            }

            // Dọn dẹp
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();

            // Tạm thời vẫn return String để test Postman, sau này ghép RabbitMQ sẽ đổi thành bắn Message
            return String.format("Verdict: %s | Score: %d/100 | Max Time: %d ms", finalVerdict, totalScore, maxExecutionTime);

        } catch (Exception e) {
            log.error("Judge system exception", e);
            return "System Exception: " + e.getMessage();
        }
    }

    // --- CÁC CLASS & HÀM PHỤ TRỢ ---

    // Object chứa toàn bộ thông số của 1 lần chạy
    static class ExecResult {
        long timeTaken;
        boolean isTle;
        int exitCode;

        public ExecResult(long timeTaken, boolean isTle, int exitCode) {
            this.timeTaken = timeTaken;
            this.isTle = isTle;
            this.exitCode = exitCode;
        }
    }

    // Hàm so sánh output "chuẩn OJ": Xóa khoảng trắng thừa và chuẩn hóa dấu xuống dòng
    private boolean checkAnswer(String userOut, String expectedOut) {
        if (userOut == null || expectedOut == null) return false;

        // 1. (?m)[ \t]+$ : Xóa tất cả dấu cách/tab thừa ở cuối MỖI DÒNG
        // 2. replace("\r\n", "\n") : Đồng bộ dấu xuống dòng của Windows (CRLF) về Linux (LF)
        // 3. trim() : Xóa sạch các dòng trống thừa thãi ở đầu và cuối file
        String normalizedUser = userOut.replaceAll("(?m)[ \\t]+$", "").replace("\r\n", "\n").trim();
        String normalizedExpected = expectedOut.replaceAll("(?m)[ \\t]+$", "").replace("\r\n", "\n").trim();

        return normalizedUser.equals(normalizedExpected);
    }

    private ExecResult executeCommandInContainer(String containerId, String bashCommand, long timeLimitMs) {
        try {
            long startTime = System.currentTimeMillis();

            ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withCmd("sh", "-c", bashCommand)
                    .exec();

            String execId = execCreateCmdResponse.getId();

            // Chạy và chờ kết quả
            boolean isCompleted = dockerClient.execStartCmd(execId)
                    .exec(new ResultCallback.Adapter<Frame>())
                    .awaitCompletion(timeLimitMs, java.util.concurrent.TimeUnit.MILLISECONDS);

            long executionTime = System.currentTimeMillis() - startTime;

            if (!isCompleted) {
                return new ExecResult(timeLimitMs, true, -1); // Bị ép dừng (TLE)
            }

            // Hút Exit Code từ Docker ra (0 = Thành công, Khác 0 = Lỗi)
            Long exitCodeLong = dockerClient.inspectExecCmd(execId).exec().getExitCodeLong();
            int exitCode = (exitCodeLong != null) ? exitCodeLong.intValue() : 0;

            return new ExecResult(executionTime, false, exitCode);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(0, false, -1);
        }
    }
}