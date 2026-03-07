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
        String submissionId = UUID.randomUUID().toString();
        Path hostTestcaseDir = Paths.get(testcasesDir, sdi.getProblemId().toString());
        Path hostWorkDir = Paths.get(workDir, "submissions", submissionId);

        try {
            File infoFile = hostTestcaseDir.resolve("info.json").toFile();
            if (!infoFile.exists()) {
                return "System Error: Testcase data not found";
            }
            ProblemInfo problemInfo = objectMapper.readValue(infoFile, ProblemInfo.class);

            Files.createDirectories(hostWorkDir);

            String fileName = "";
            String compileCmd = null;
            String runCmdTemplate = "";
            long timeLimitMs = 2000; // Mặc định C++ là 2000ms (2s)

            switch (sdi.getLanguageKey().toUpperCase()) {
                case "CPP":
                case "C++":
                    fileName = "main.cpp";
                    compileCmd = "g++ /sandbox/app/main.cpp -o /sandbox/app/main";
                    runCmdTemplate = "/sandbox/app/main < /testcases/%s > /sandbox/app/%s";
                    break;
                case "JAVA":
                    fileName = "Main.java";
                    compileCmd = "javac /sandbox/app/Main.java";
                    runCmdTemplate = "cd /sandbox/app && java Main < /testcases/%s > %s";
                    timeLimitMs = 4000; // Java cần x2 thời gian để khởi động máy ảo JVM
                    break;
                case "PYTHON":
                    fileName = "main.py";
                    runCmdTemplate = "python3 /sandbox/app/main.py < /testcases/%s > /sandbox/app/%s";
                    timeLimitMs = 5000; // Python thông dịch nên cho hẳn x2.5 thời gian
                    break;
                default:
                    return "System Error: Unsupported language";
            }

            Path sourceFile = hostWorkDir.resolve(fileName);
            Files.writeString(sourceFile, sdi.getSourceCode());

            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withBinds(
                            new Bind(hostWorkDir.toAbsolutePath().toString(), new Volume("/sandbox/app")),
                            new Bind(hostTestcaseDir.toAbsolutePath().toString(), new Volume("/testcases"))
                    )
                    .withAutoRemove(true);

            CreateContainerResponse container = dockerClient.createContainerCmd("oj-sandbox:v1")
                    .withHostConfig(hostConfig)
                    .withCmd("tail", "-f", "/dev/null")
                    .exec();

            dockerClient.startContainerCmd(container.getId()).exec();
            String containerId = container.getId();
            log.info("Started Sandbox: {}", containerId);

            // Giai đoạn Biên dịch (Compile)
            if (compileCmd != null) {
                ExecResult compileResult = executeCommandInContainer(containerId, compileCmd, 10000); // Cho build tối đa 10s
                if (compileResult.isTle || compileResult.exitCode != 0) {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                    return "Verdict: CE (Compile Error) | Score: 0/100";
                }
            }

            // Giai đoạn Chấm điểm (Grade)
            int totalScore = 0;
            long maxExecutionTime = 0;
            String finalVerdict = "AC";

            for (TestCaseInfo testCase : problemInfo.getTestCases()) {
                String inFileName = testCase.getInputName();
                String outFileName = testCase.getOutputName();
                String userOutName = "user_" + outFileName;

                String runCmd = String.format(runCmdTemplate, inFileName, userOutName);

                // Chạy lệnh với Time Limit tương ứng của ngôn ngữ
                ExecResult result = executeCommandInContainer(containerId, runCmd, timeLimitMs);

                // 1. Check TLE (Lặp vô hạn)
                if (result.isTle) {
                    finalVerdict = "TLE (Time Limit Exceeded) at " + inFileName;
                    break;
                }

                maxExecutionTime = Math.max(maxExecutionTime, result.timeTaken);

                // 2. Check RE (Crash, Chia cho 0, Tràn mảng...)
                if (result.exitCode != 0) {
                    finalVerdict = "RE (Runtime Error) at " + inFileName + " [Exit Code: " + result.exitCode + "]";
                    break;
                }

                // 3. Check WA (Sai đáp án)
                Path userOutputPath = hostWorkDir.resolve(userOutName);
                Path expectedOutputPath = hostTestcaseDir.resolve(outFileName);

                if (Files.exists(userOutputPath)) {
                    // Đọc nguyên bản file lên
                    String userOutput = Files.readString(userOutputPath);
                    String expectedOutput = Files.readString(expectedOutputPath);

                    // Đưa qua máy lọc để chuẩn hóa rồi mới so sánh
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

            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
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