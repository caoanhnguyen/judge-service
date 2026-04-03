package com.kma.judgeservice.dto.requests;

import lombok.*;
import lombok.experimental.FieldDefaults;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class JudgeResultSdi {

    // ID của bài nộp để biết đường mà update
    UUID submissionId;

    String submissionStatus;      // Dùng String cho an toàn thay vì Enum để tránh lỗi Mapping giữa 2 service

    String submissionVerdict;     // Dùng String ("AC", "WA", "TLE"...)

    // Các chỉ số đo lường
    Integer score;
    Integer passedTestCount;
    Integer totalTestCount;
    Long executionTimeMs;
    Long executionMemoryMb;

    // Báo lỗi (Nếu bị CE - Compile Error hoặc RE - Runtime Error)
    String errorMessage;
}