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
import com.kma.judgeservice.service.TestcaseManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

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
    private final TestcaseManager testcaseManager;

    @Value("${oj.judge.container.image-name:oj-sandbox:v1}")
    private String dockerImageName;

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

    @Override
    public JudgeResultSdi judge(JudgeSdi sdi) {
        String submissionId = sdi.getSubmissionId().toString();
        log.info("Bat dau cham bai cho Submission [{}]", submissionId);

        Path hostWorkDir = Paths.get(hostWorkDirBase, "submissions", submissionId);

        String finalErrorMessage = null;

        try {
            // 1. KET NOI MINIO LAY TESTCASE
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

            // --- AP DUNG MEMORY LIMIT ---
            long memoryLimitMb = sdi.getFinalMemoryLimitMb() != null && sdi.getFinalMemoryLimitMb() > 0
                    ? sdi.getFinalMemoryLimitMb() : 256L;
            long memoryLimitBytes = memoryLimitMb * 1024L * 1024L;

            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withBinds(
                            new Bind(hostWorkDir.toAbsolutePath().toString(), new Volume(containerWorkDir)),
                            new Bind(hostTestcaseDir.toAbsolutePath().toString(), new Volume(containerTestcaseDir))
                    )
                    .withMemory(memoryLimitBytes)
                    .withMemorySwap(memoryLimitBytes)
                    .withAutoRemove(true);

            // Khoi tao va chay container
            CreateContainerResponse container = dockerClient.createContainerCmd(dockerImageName)
                    .withHostConfig(hostConfig)
                    .withWorkingDir(containerWorkDir)
                    .withCmd("tail", "-f", "/dev/null")
                    .exec();

            dockerClient.startContainerCmd(container.getId()).exec();
            String containerId = container.getId();
            log.info("Started Sandbox for Submission {}: {} with {}MB RAM", submissionId, containerId, memoryLimitMb);

            // 1. Giai doan Bien dich
            // Neu co lenh bien dich, thuc thi no truoc. Neu loi bien dich, tra ve ngay ket qua CE.
            if (sdi.isCompiled() && sdi.getCompileCommand() != null) {
                String compileCmd = sdi.getCompileCommand().trim();
                if (!compileCmd.isEmpty()) {
                    ExecResult compileResult = executeCommandInContainer(containerId, compileCmd, 15000);
                    if (compileResult.isTle || compileResult.exitCode != 0) {
                        // Don dep Docker ngay lap tuc neu bien dich loi de khong phi tai nguyen
                        dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                        return buildResult(sdi, "COMPLETED", "CE", 0, 0, problemInfo.getTestCases().size(), 0L, memoryLimitMb, compileResult.outputLog.trim());
                    }
                }
            }

            // 2. Giai doan Cham diem (TICH HOP ACM & OI)
            int totalScore = 0;
            int passedTestCount = 0;
            long maxExecutionTime = 0;
            String finalVerdict = "AC";
            int totalTestCount = problemInfo.getTestCases().size();

            // Lay luat choi, mac dinh ACM
            boolean isAcmMode = sdi.getRuleType() == null || sdi.getRuleType().equalsIgnoreCase("ACM");

            for (TestCaseInfo testCase : problemInfo.getTestCases()) {
                String inFileName = testCase.getInputName();     // VD: "1.in"
                String outFileName = testCase.getOutputName();   // VD: "1.out"
                String userOutName = "user_" + outFileName;      // VD: "user_1.out"

                // Dau vao nam o folder testcases
                String containerInPath = containerTestcaseDir + "/" + inFileName;
                // Dau ra bat no ghi vao folder sandbox/app
                String containerOutPath = containerWorkDir + "/" + userOutName;

                String baseCmd = sdi.getRunCommand();
                if (baseCmd.contains("{memory_limit}")) {
                    baseCmd = baseCmd.replace("{memory_limit}", String.valueOf(memoryLimitMb));
                }

                if (!baseCmd.contains("%s")) {
                    baseCmd += " < %s > %s";
                }

                // 1: KHIEN VAT LY ulimit
                // ulimit -f gioi han kich thuoc file output toi da. Don vi la KB.
                // 32768 KB = 32 MB. Qua gioi han nay, Linux tu dong kill tien trinh!
                baseCmd = "ulimit -f " + maxOutputKb + "; " + baseCmd;

                String runCmd = String.format(baseCmd, containerInPath, containerOutPath);

                log.info("Lenh se chay trong Docker: {}", runCmd);

                ExecResult result = executeCommandInContainer(containerId, runCmd, sdi.getFinalTimeLimitMs());

                String currentVerdict = "AC";

                Path userOutputPath = hostWorkDir.resolve(userOutName);
                long maxOutputSizeBytes = (long) maxOutputMb * 1024 * 1024L;
                long actualOutputSize = Files.exists(userOutputPath) ? Files.size(userOutputPath) : 0;

                // 2: BAT BENH THEO THU TU UU TIEN (OLE len dau)

                // Check OLE truoc RE! Vi khi ulimit chem, exit code se khac 0 (no tuong la RE)
                if (actualOutputSize >= maxOutputSizeBytes || result.outputLog.contains("File size limit exceeded")) {
                    currentVerdict = "OLE";
                    if (finalErrorMessage == null) {
                        finalErrorMessage = "Output Limit Exceeded: Output qua lon. Hay kiem tra lai vong lap vo han!";
                    }
                }
                else if (result.isTle) {
                    currentVerdict = "TLE";
                }
                else if (result.exitCode == 137 || result.outputLog.contains("OutOfMemoryError") || result.outputLog.contains("bad_alloc")) {
                    currentVerdict = "MLE";
                    if (finalErrorMessage == null && !result.outputLog.trim().isEmpty()) {
                        finalErrorMessage = result.outputLog.trim();
                    }
                }
                else if (result.exitCode != 0) {
                    currentVerdict = "RE";
                    if (finalErrorMessage == null && !result.outputLog.trim().isEmpty()) {
                        finalErrorMessage = result.outputLog.trim();
                    }
                }
                else {
                    // GIAI DOAN CHECK ANSWER (Chi chay khi file < 32MB va khong co loi)
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

                // --- XU LY THEO LUAT ACM HOAC OI ---
                if (currentVerdict.equals("AC")) {
                    totalScore += testCase.getScore();
                    passedTestCount++;
                    log.info("Testcase {} AC! Time: {}ms", inFileName, result.timeTaken);
                } else {
                    log.info("Testcase {} Failed: {}", inFileName, currentVerdict);

                    // Ghi nhan loi DAU TIEN lam ket qua chung cuoc
                    if (finalVerdict.equals("AC")) {
                        finalVerdict = currentVerdict;
                    }

                    if (isAcmMode) {
                        totalScore = 0;
                        break; // ACM: Chet 1 phat dung luon
                    }
                }
            }

            // Don dep Docker
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();

            // Tra ket qua chuan JSON
            return buildResult(sdi, "COMPLETED", finalVerdict, totalScore, passedTestCount, totalTestCount, maxExecutionTime, memoryLimitMb, finalErrorMessage);

        } catch (Exception e) {
            log.error("Judge system exception", e);
            return buildFailedResult(sdi, "System Exception: " + e.getMessage());
        } finally {
            // Luon luon don rac code cua User de khong day o cung
            try {
                FileSystemUtils.deleteRecursively(hostWorkDir);
            } catch (Exception ignored) {}
        }
    }

    // --- HAM PHU TRO BUILD KET QUA ---
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

    // --- CAC CLASS & HAM PHU TRO ---

    // Object chua toan bo thong so cua 1 lan chay
    static class ExecResult {
        long timeTaken;
        boolean isTle;
        int exitCode;
        String outputLog;

        public ExecResult(long timeTaken, boolean isTle, int exitCode, String outputLog) {
            this.timeTaken = timeTaken;
            this.isTle = isTle;
            this.exitCode = exitCode;
            this.outputLog = outputLog;
        }
    }

    // Ham so sanh output: Xoa khoang trang thua va chuan hoa dau xuong dong
    private boolean checkAnswer(String userOut, String expectedOut) {
        if (userOut == null || expectedOut == null) return false;

        // 1. (?m)[ \t]+$ : Xoa tat ca dau cach/tab thua o cuoi MOI DONG
        // 2. replace("\r\n", "\n") : Dong bo dau xuong dong cua Windows (CRLF) ve Linux (LF)
        // 3. trim() : Xoa sach cac dong trong thua thai o dau va cuoi file
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
                    .withWorkingDir(containerWorkDir)
                    .withCmd("sh", "-c", bashCommand)
                    .exec();

            String execId = execCreateCmdResponse.getId();
            StringBuilder dockerLog = new StringBuilder();

            // Chay va cho ket qua
            boolean isCompleted = dockerClient.execStartCmd(execId)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame item) {
                            // Cat bot log neu qua dai (tranh vo DB hoac tran RAM)
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