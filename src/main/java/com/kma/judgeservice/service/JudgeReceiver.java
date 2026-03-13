package com.kma.judgeservice.service;

import com.kma.judgeservice.config.RabbitMQConfig;
import com.kma.judgeservice.dto.JudgeResultSdi;
import com.kma.judgeservice.dto.JudgeSdi;
import com.kma.judgeservice.dto.RunCodeRequest;
import com.kma.judgeservice.dto.RunCodeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class JudgeReceiver {

    private final RabbitTemplate rabbitTemplate;
    private final JudgeService judgeService;
    private final RunCodeService runCodeService;

    @RabbitListener(queues = "judge.queue")
    public void receiveSubmission(JudgeSdi sdi) {
        log.info("[JUDGE] Đã nhận được yêu cầu chấm bài cho Submission: {}", sdi.getSubmissionId());

        try {
            // 1. GỌI MÁY CHẤM
            JudgeResultSdi finalResult = judgeService.judge(sdi);

            // 2. Gửi kết quả về lại Core
            rabbitTemplate.convertAndSend("result.queue", finalResult);
            log.info("[JUDGE] Đã gửi trả kết quả [{}] về Core!", finalResult.getSubmissionVerdict());

        } catch (Exception e) {
            log.error("Lỗi nghiêm trọng lúc chấm bài", e);

            JudgeResultSdi failResult = JudgeResultSdi.builder()
                    .submissionId(sdi.getSubmissionId())
                    .submissionStatus("FAILED")
                    .submissionVerdict("SE")
                    .errorMessage(e.getMessage())
                    .build();
            rabbitTemplate.convertAndSend("result.queue", failResult);
        }
    }

    // =========================================================
    // LUỒNG 2: CHẠY THỬ CODE (RUN CODE)
    // =========================================================
    @RabbitListener(queues = RabbitMQConfig.RUN_CODE_QUEUE)
    public void processRunCode(RunCodeRequest request) {
        log.info("[RUN CODE] Đã nhận yêu cầu chạy thử. Token: [{}]", request.getRunToken());

        try {
            // 1. Gọi Service xử lý (Bật Docker, chạy list Custom Input)
            RunCodeResponse response = runCodeService.executeCustomRun(request);

            // 2. Gửi kết quả về lại Core
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.JUDGE_EXCHANGE,
                    RabbitMQConfig.RUN_CODE_RESULT_ROUTING_KEY,
                    response
            );
            log.info("[RUN CODE] Đã gửi trả kết quả của Token: [{}] về Core!", request.getRunToken());

        } catch (Exception e) {
            log.error("Lỗi nghiêm trọng lúc Run Code", e);

            RunCodeResponse errorResponse = RunCodeResponse.builder()
                    .runToken(request.getRunToken())
                    .status("FAILED")
                    .compileMessage("Lỗi hệ thống máy chấm: " + e.getMessage())
                    .build();

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.JUDGE_EXCHANGE,
                    RabbitMQConfig.RUN_CODE_RESULT_ROUTING_KEY,
                    errorResponse
            );
        }
    }
}