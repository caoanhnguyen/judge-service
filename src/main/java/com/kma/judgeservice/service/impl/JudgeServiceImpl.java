package com.kma.judgeservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.kma.judgeservice.dto.JudgeResultSdi;
import com.kma.judgeservice.dto.JudgeSdi;
import com.kma.judgeservice.dto.ProblemInfo;
import com.kma.judgeservice.dto.TestCaseInfo;
import com.kma.judgeservice.service.JudgeService;
import com.kma.judgeservice.service.TestcaseManager; // Import kho lưu trữ
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils; // Dọn rác

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeServiceImpl implements JudgeService {

    private final DockerClient dockerClient;
    private final ObjectMapper objectMapper;

    // Đã thay thế testcasesDir fix cứng bằng TestcaseManager xịn sò
    private final TestcaseManager testcaseManager;

    @Value("${oj.judge.work-dir}")
    private String workDir;

    @Override
    public JudgeResultSdi judge(JudgeSdi sdi) {
        String submissionId = sdi.getSubmissionId().toString();
        log.info("Bắt đầu chấm bài cho Submission [{}]", submissionId);

        Path hostWorkDir = Paths.get(workDir, "submissions", submissionId);

        try {
            // 1. KẾT NỐI MINIO LẤY TESTCASE
            Path hostTestcaseDir = testcaseManager.getOrDownloadTestcases(sdi.getProblemId());

            File infoFile = hostTestcaseDir.resolve("info.json").toFile();
            if (!infoFile.exists()) {
                return buildFailedResult(sdi, "System Error: Testcase data not found");
            }
            ProblemInfo problemInfo = objectMapper.readValue(infoFile, ProblemInfo.class);

            Files.createDirectories(hostWorkDir);

            // Ghi file source code
            Path sourceFile = hostWorkDir.resolve(sdi.getSourceName());
            Files.writeString(sourceFile, sdi.getSourceCode());

            // --- ÁP DỤNG MEMORY LIMIT ---
            long memoryLimitMb = sdi.getFinalMemoryLimitMb() != null && sdi.getFinalMemoryLimitMb() > 0
                    ? sdi.getFinalMemoryLimitMb() : 256L;
            long memoryLimitBytes = memoryLimitMb * 1024L * 1024L;

            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withBinds(
                            new Bind(hostWorkDir.toAbsolutePath().toString(), new Volume("/sandbox/app")),
                            new Bind(hostTestcaseDir.toAbsolutePath().toString(), new Volume("/testcases"))
                    )
                    .withMemory(memoryLimitBytes)
                    .withMemorySwap(memoryLimitBytes)
                    .withAutoRemove(true);

            CreateContainerResponse container = dockerClient.createContainerCmd("oj-sandbox:v1")
                    .withHostConfig(hostConfig)
                    .withWorkingDir("/sandbox/app")
                    .withCmd("tail", "-f", "/dev/null")
                    .exec();

            dockerClient.startContainerCmd(container.getId()).exec();
            String containerId = container.getId();
            log.info("Started Sandbox for Submission {}: {} with {}MB RAM", submissionId, containerId, memoryLimitMb);

            // 1. Giai đoạn Biên dịch
            if (sdi.isCompiled() && sdi.getCompileCommand() != null) {
                String compileCmd = sdi.getCompileCommand().trim();
                if (!compileCmd.isEmpty()) {
                    ExecResult compileResult = executeCommandInContainer(containerId, compileCmd, 15000);
                    if (compileResult.isTle || compileResult.exitCode != 0) {
                        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                        return buildResult(sdi, "COMPLETED", "CE", 0, 0, problemInfo.getTestCases().size(), 0L, memoryLimitMb, "Lỗi biên dịch");
                    }
                }
            }

            // 2. Giai đoạn Chấm điểm (TÍCH HỢP ACM & OI)
            int totalScore = 0;
            int passedTestCount = 0;
            long maxExecutionTime = 0;
            String finalVerdict = "AC";
            int totalTestCount = problemInfo.getTestCases().size();

            // Lấy luật chơi, mặc định ACM
            boolean isAcmMode = sdi.getRuleType() == null || sdi.getRuleType().equalsIgnoreCase("ACM");

            for (TestCaseInfo testCase : problemInfo.getTestCases()) {
                String inFileName = testCase.getInputName();     // VD: "1.in"
                String outFileName = testCase.getOutputName();   // VD: "1.out"
                String userOutName = "user_" + outFileName;      // VD: "user_1.out"

                // BÍ QUYẾT GIẢI LÚ: Trỏ đích danh tọa độ của file bên trong bụng Docker!
                // Đầu vào nằm ở phòng testcases
                String containerInPath = "/testcases/" + inFileName;
                // Đầu ra bắt nó ghi vào phòng sandbox/app
                String containerOutPath = "/sandbox/app/" + userOutName;

                // Lắp ráp lệnh chạy. VD: java Main < /testcases/1.in > /sandbox/app/user_1.out
                String runCmd = String.format(sdi.getRunCommand(), containerInPath, containerOutPath);

                log.info("Lệnh sẽ chạy trong Docker: {}", runCmd);

                ExecResult result = executeCommandInContainer(containerId, runCmd, sdi.getFinalTimeLimitMs());

                String currentVerdict = "AC";

                if (result.isTle) {
                    currentVerdict = "TLE";
                } else if (result.exitCode != 0) {
                    currentVerdict = "RE";
                } else {
                    Path userOutputPath = hostWorkDir.resolve(userOutName);
                    Path expectedOutputPath = hostTestcaseDir.resolve(outFileName);

                    if (Files.exists(userOutputPath)) {
                        String userOutput = Files.readString(userOutputPath);
                        String expectedOutput = Files.readString(expectedOutputPath);

                        if (!checkAnswer(userOutput, expectedOutput)) {
                            currentVerdict = "WA";
                        }
                    } else {
                        currentVerdict = "SE"; // Missing output
                    }
                }

                maxExecutionTime = Math.max(maxExecutionTime, result.timeTaken);

                // --- XỬ LÝ THEO LUẬT ACM HOẶC OI ---
                if (currentVerdict.equals("AC")) {
                    totalScore += testCase.getScore();
                    passedTestCount++;
                    log.info("Testcase {} AC! Time: {}ms", inFileName, result.timeTaken);
                } else {
                    log.info("Testcase {} Failed: {}", inFileName, currentVerdict);

                    // Ghi nhận lỗi ĐẦU TIÊN làm kết quả chung cuộc
                    if (finalVerdict.equals("AC")) {
                        finalVerdict = currentVerdict;
                    }

                    if (isAcmMode) {
                        totalScore = 0;
                        break; // ACM: Chết 1 phát dừng luôn
                    }
                }
            }

            // Dọn dẹp Docker
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();

            // Trả kết quả chuẩn JSON
            return buildResult(sdi, "COMPLETED", finalVerdict, totalScore, passedTestCount, totalTestCount, maxExecutionTime, memoryLimitMb, null);

        } catch (Exception e) {
            log.error("Judge system exception", e);
            return buildFailedResult(sdi, "System Exception: " + e.getMessage());
        } finally {
            // Luôn luôn dọn rác code của User để không đầy ổ cứng
            try {
                FileSystemUtils.deleteRecursively(hostWorkDir);
            } catch (Exception ignored) {}
        }
    }

    // --- HÀM PHỤ TRỢ BUILD KẾT QUẢ ---
    private JudgeResultSdi buildResult(JudgeSdi sdi, String status, String verdict, int score, int passed, int total, long time, long memory, String error) {
        return JudgeResultSdi.builder()
                .submissionId(sdi.getSubmissionId())
                .submissionStatus(status)
                .submissionVerdict(verdict)
                .score(score)
                .passedTestCount(passed)
                .totalTestCount(total)
                .executionTimeMs(time)
                .executionMemoryMb(memory)
                .errorMessage(error)
                .build();
    }

    private JudgeResultSdi buildFailedResult(JudgeSdi sdi, String errorMessage) {
        return JudgeResultSdi.builder()
                .submissionId(sdi.getSubmissionId())
                .submissionStatus("FAILED")
                .submissionVerdict("SE")
                .errorMessage(errorMessage)
                .build();
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
                    .withWorkingDir("/sandbox/app")
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