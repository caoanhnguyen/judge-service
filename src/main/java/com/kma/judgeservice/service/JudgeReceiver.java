package com.kma.judgeservice.service;

import com.kma.judgeservice.dto.JudgeResultSdi;
import com.kma.judgeservice.dto.JudgeSdi;
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

    @RabbitListener(queues = "judge.queue")
    public void receiveSubmission(JudgeSdi sdi) {
        log.info("[JUDGE] Đã nhận được yêu cầu chấm bài cho Submission: {}", sdi.getSubmissionId());

        try {
            // 1. GỌI MÁY CHẤM THẬT (Đã bao gồm tải MinIO và chạy Docker)
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
}