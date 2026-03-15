package com.kma.judgeservice.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kma.judgeservice.dto.*;
import com.kma.judgeservice.service.JudgeService;
import com.kma.judgeservice.service.RunCodeService;
import com.kma.judgeservice.service.TestcaseManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedJudgeServiceImpl implements JudgeService, RunCodeService {

    private final DockerExecutionHelper dockerHelper;
    private final ObjectMapper objectMapper;
    private final TestcaseManager testcaseManager;

    @Value("${oj.judge.work-dir}")
    private String hostWorkDirBase;

    @Value("${oj.judge.container.work-dir}")
    private String containerWorkDir;

    @Value("${oj.judge.container.testcase-dir}")
    private String containerTestcaseDir;

    @Value("${oj.judge.container.max-output-kb}")
    private int maxOutputKb;

    @Value("${oj.judge.container.max-output-mb}")
    private int maxOutputMb;

    @Value("${oj.judge.container.compile-timeout-ms}")
    private long compileTimeoutMs;

    // =========================================================================
    // Chức năng SUBMIT
    // =========================================================================
    @Override
    public JudgeResultSdi judge(JudgeSdi sdi) {
        String submissionId = sdi.getSubmissionId().toString();
        log.info("[SUBMIT] Bắt đầu chấm bài cho Submission [{}]", submissionId);

        Path hostWorkDir = Paths.get(hostWorkDirBase, "submissions", submissionId);
        String finalErrorMessage = null;
        String containerId = null;

        try {
            // 1. Lấy Testcase từ MinIO
            Path hostTestcaseDir = testcaseManager.getOrDownloadTestcases(sdi.getProblemId());
            File infoFile = hostTestcaseDir.resolve("info.json").toFile();
            if (!infoFile.exists()) return buildFailedResult(sdi, "System Error: Testcase data not found");
            ProblemInfo problemInfo = objectMapper.readValue(infoFile, ProblemInfo.class);

            Files.createDirectories(hostWorkDir);
            Files.writeString(hostWorkDir.resolve(sdi.getSourceName()), sdi.getSourceCode());

            long memoryLimitMb = sdi.getFinalMemoryLimitMb() != null && sdi.getFinalMemoryLimitMb() > 0 ? sdi.getFinalMemoryLimitMb() : 256L;

            // 2. Bật Docker
            containerId = dockerHelper.startSandboxContainer(hostWorkDir, hostTestcaseDir, memoryLimitMb);

            // 3. Biên dịch
            String ceMessage = handleCompilation(containerId, sdi.isCompiled(), sdi.getCompileCommand());
            if (ceMessage != null) {
                return buildResult(sdi, "COMPLETED", "CE", 0, 0, problemInfo.getTestCases().size(), 0L, memoryLimitMb, ceMessage);
            }

            // 4. Chấm điểm từng Testcase
            int totalScore = 0, passedTestCount = 0;
            long maxExecutionTime = 0;
            String finalVerdict = "AC";
            boolean isAcmMode = sdi.getRuleType() == null || sdi.getRuleType().equalsIgnoreCase("ACM");

            for (TestCaseInfo testCase : problemInfo.getTestCases()) {
                String inFileName = testCase.getInputName();
                String outFileName = testCase.getOutputName();
                String userOutName = "user_" + outFileName;

                String containerInPath = containerTestcaseDir + "/" + inFileName;
                String containerOutPath = containerWorkDir + "/" + userOutName;
                Path userOutputPath = hostWorkDir.resolve(userOutName);
                Path expectedOutputPath = hostTestcaseDir.resolve(outFileName);

                // GỌI HÀM DÙNG CHUNG
                TestCaseResult internalResult = evaluateSingleTestCase(
                        containerId, sdi.getRunCommand(), containerInPath, containerOutPath,
                        userOutputPath, expectedOutputPath, null, sdi.getFinalTimeLimitMs(), memoryLimitMb
                );

                maxExecutionTime = Math.max(maxExecutionTime, internalResult.timeTakenMs);

                if (internalResult.verdict.equals("AC")) {
                    totalScore += testCase.getScore();
                    passedTestCount++;
                } else {
                    if (finalVerdict.equals("AC")) {
                        finalVerdict = internalResult.verdict;
                        finalErrorMessage = internalResult.errorMessage;
                    }
                    if (isAcmMode) {
                        totalScore = 0; break;
                    }
                }
            }

            return buildResult(sdi, "COMPLETED", finalVerdict, totalScore, passedTestCount, problemInfo.getTestCases().size(), maxExecutionTime, memoryLimitMb, finalErrorMessage);

        } catch (Exception e) {
            log.error("Judge system exception", e);
            return buildFailedResult(sdi, "System Exception: " + e.getMessage());
        } finally {
            dockerHelper.cleanupContainer(containerId);
            try { FileSystemUtils.deleteRecursively(hostWorkDir); } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // Chức năng RUN CODE
    // =========================================================================
    @Override
    public RunCodeResponse executeCustomRun(RunCodeRequest request) {
        String runToken = request.getRunToken().toString();
        log.info("[RUN CODE] Bắt đầu thực thi Run Code cho Token: [{}]", runToken);

        Path hostWorkDir = Paths.get(hostWorkDirBase, "run_codes", runToken);
        String containerId = null;

        try {
            Files.createDirectories(hostWorkDir);
            Files.writeString(hostWorkDir.resolve(request.getSourceName()), request.getSourceCode());

            long memoryLimitMb = request.getFinalMemoryLimitMb() != null && request.getFinalMemoryLimitMb() > 0 ? request.getFinalMemoryLimitMb() : 256L;

            // 1. Bật Docker (Không cần mount thư mục testcase)
            containerId = dockerHelper.startSandboxContainer(hostWorkDir, null, memoryLimitMb);

            // 2. Biên dịch
            String ceMessage = handleCompilation(containerId, request.isCompiled(), request.getCompileCommand());
            if (ceMessage != null) {
                return RunCodeResponse.builder().runToken(request.getRunToken()).problemId(request.getProblemId())
                        .status("FAILED").compileMessage(ceMessage).build();
            }

            // 3. Chạy vòng lặp testcase FE gửi
            List<RunTestCaseResult> results = new ArrayList<>();
            List<RunTestCaseSdi> testCases = request.getCustomInputs() != null ? request.getCustomInputs() : new ArrayList<>();
            if (testCases.isEmpty()) testCases.add(new RunTestCaseSdi("", null));

            for (int i = 0; i < testCases.size(); i++) {
                RunTestCaseSdi tc = testCases.get(i);
                String rawInput = tc.getInput() != null ? tc.getInput() : "";
                String expectedOutputString = tc.getExpectedOutput(); // 🌟 Lấy string output mong đợi

                String inFileName = "custom_" + i + ".in";
                String outFileName = "custom_" + i + ".out";

                Files.writeString(hostWorkDir.resolve(inFileName), rawInput); // Ghi input xuống file

                String containerInPath = containerWorkDir + "/" + inFileName;
                String containerOutPath = containerWorkDir + "/" + outFileName;
                Path userOutputPath = hostWorkDir.resolve(outFileName);

                // GỌI HÀM DÙNG CHUNG
                TestCaseResult internalResult = evaluateSingleTestCase(
                        containerId, request.getRunCommand(), containerInPath, containerOutPath,
                        userOutputPath, null, expectedOutputString, request.getFinalTimeLimitMs(), memoryLimitMb
                );

                String trimmedOutput = internalResult.actualOutput;
                if (trimmedOutput.length() > 5000) {
                    trimmedOutput = trimmedOutput.substring(0, 5000) + "\n...[Output truncated]";
                }

                results.add(RunTestCaseResult.builder()
                        .input(rawInput)
                        .output(trimmedOutput.trim())
                        .expectedOutput(expectedOutputString)
                        .verdict(internalResult.verdict)
                        .errorMessage(internalResult.errorMessage)
                        .timeTakenMs(internalResult.timeTakenMs)
                        .build());
            }

            return RunCodeResponse.builder().runToken(request.getRunToken()).problemId(request.getProblemId()).status("COMPLETED").results(results).build();

        } catch (Exception e) {
            log.error("Run Code exception", e);
            return RunCodeResponse.builder().runToken(request.getRunToken()).problemId(request.getProblemId()).status("FAILED").compileMessage("System Exception: " + e.getMessage()).build();
        } finally {
            dockerHelper.cleanupContainer(containerId);
            try { FileSystemUtils.deleteRecursively(hostWorkDir); } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // CÁC HÀM XỬ LÝ DÙNG CHUNG (TÁI SỬ DỤNG LOGIC)
    // =========================================================================

    // DTO bọc kết quả 1 testcase
    private static class TestCaseResult {
        String verdict;
        String errorMessage;
        String actualOutput;
        long timeTakenMs;
        public TestCaseResult(String verdict, String errorMessage, String actualOutput, long timeTakenMs) {
            this.verdict = verdict;
            this.errorMessage = errorMessage;
            this.actualOutput = actualOutput;
            this.timeTakenMs = timeTakenMs;
        }
    }

    /**
     * Hàm dùng chung xử lý vòng đời của 1 Testcase (Chạy lệnh -> Bắt bệnh TLE, MLE, OLE, RE -> Check Answer)
     */
    private TestCaseResult evaluateSingleTestCase(
            String containerId, String baseCmd, String containerInPath, String containerOutPath,
            Path hostUserOutputPath, Path expectedOutputFile, String expectedOutputString,
            long timeLimitMs, long memoryLimitMb) throws IOException {

        if (baseCmd.contains("{memory_limit}")) baseCmd = baseCmd.replace("{memory_limit}", String.valueOf(memoryLimitMb));
        if (!baseCmd.contains("%s")) baseCmd += " < %s > %s";
        baseCmd = "ulimit -f " + maxOutputKb + "; " + baseCmd; // Bọc ulimit

        String runCmd = String.format(baseCmd, containerInPath, containerOutPath);
        DockerExecutionHelper.ExecResult result = dockerHelper.executeCommand(containerId, runCmd, timeLimitMs);

        String currentVerdict = "SUCCESS"; // Mặc định cho custom run không có expected
        String errorMessage = null;
        String actualOutput = "";

        long maxOutputSizeBytes = (long) maxOutputMb * 1024 * 1024L;
        long actualOutputSize = Files.exists(hostUserOutputPath) ? Files.size(hostUserOutputPath) : 0;

        // BẮT BỆNH THEO ƯU TIÊN
        if (actualOutputSize >= maxOutputSizeBytes || result.outputLog.contains("File size limit exceeded")) {
            currentVerdict = "OLE"; errorMessage = "Output Limit Exceeded";
        } else if (result.isTle) {
            currentVerdict = "TLE"; errorMessage = "Time Limit Exceeded";
        } else if (result.exitCode == 137 || result.outputLog.contains("OutOfMemoryError") || result.outputLog.contains("bad_alloc")) {
            currentVerdict = "MLE"; errorMessage = result.outputLog.trim();
        } else if (result.exitCode != 0) {
            currentVerdict = "RE"; errorMessage = result.outputLog.trim();
        } else {
            // NẾU CODE CHẠY MƯỢT -> XỬ LÝ OUTPUT VÀ SO SÁNH
            try {
                if (Files.exists(hostUserOutputPath)) {
                    actualOutput = Files.readString(hostUserOutputPath);
                }

                String expectedOut = null;
                // Trường hợp 1: Chấm bài Submit (đọc từ file của hệ thống)
                if (expectedOutputFile != null && Files.exists(expectedOutputFile)) {
                    expectedOut = Files.readString(expectedOutputFile);
                    currentVerdict = checkAnswer(actualOutput, expectedOut) ? "AC" : "WA";
                }
                // Trường hợp 2: Run Code Example (Frontend gửi kèm string expected)
                else if (expectedOutputString != null && !expectedOutputString.trim().isEmpty()) {
                    expectedOut = expectedOutputString;
                    currentVerdict = checkAnswer(actualOutput, expectedOut) ? "AC" : "WA";
                }
                // Trường hợp 3: Run Code Custom -> currentVerdict vẫn giữ nguyên là "SUCCESS"
            } catch (Exception e) {
                currentVerdict = "SE"; errorMessage = "Error reading output files: " + e.getMessage();
            }
        }

        return new TestCaseResult(currentVerdict, errorMessage, actualOutput, result.timeTaken);
    }

    /**
     * Hàm xử lý biên dịch nếu cần thiết. Trả về null nếu compile thành công hoặc không cần compile, ngược lại trả về message lỗi để trả về cho người dùng.
     */
    private String handleCompilation(String containerId, boolean isCompiled, String compileCmd) {
        if (isCompiled && compileCmd != null && !compileCmd.trim().isEmpty()) {
            DockerExecutionHelper.ExecResult compileResult = dockerHelper.executeCommand(containerId, compileCmd.trim(), compileTimeoutMs);
            if (compileResult.isTle || compileResult.exitCode != 0) {
                return compileResult.outputLog.trim();
            }
        }
        return null; // Null nghĩa là compile thành công (hoặc không cần compile)
    }

    /**
     * Hàm so sánh output của user với output chuẩn, bỏ qua khoảng trắng cuối dòng và chuẩn hóa newline để tránh lỗi do khác hệ điều hành.
     */
    private boolean checkAnswer(String userOut, String expectedOut) {
        if (userOut == null || expectedOut == null) return false;
        String normalizedUser = userOut.replaceAll("(?m)[ \\t]+$", "").replace("\r\n", "\n").trim();
        String normalizedExpected = expectedOut.replaceAll("(?m)[ \\t]+$", "").replace("\r\n", "\n").trim();
        return normalizedUser.equals(normalizedExpected);
    }

    // Các hàm build result
    private JudgeResultSdi buildResult(JudgeSdi sdi, String status, String verdict, int score, int passed, int total, long time, long memory, String error) {
        return JudgeResultSdi.builder().submissionId(sdi.getSubmissionId()).submissionStatus(status).submissionVerdict(verdict).score(score).passedTestCount(passed).totalTestCount(total).executionTimeMs(time).executionMemoryMb(memory).errorMessage(error).build();
    }
    private JudgeResultSdi buildFailedResult(JudgeSdi sdi, String errorMessage) {
        return JudgeResultSdi.builder().submissionId(sdi.getSubmissionId()).submissionStatus("FAILED").submissionVerdict("SE").errorMessage(errorMessage).build();
    }
}