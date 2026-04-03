package com.kma.judgeservice.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Data
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class JudgeSdi {

    // --- Thông tin định danh ---
    UUID submissionId;
    UUID problemId;
    String ruleType; // "ACM" hoặc "OI"

    // --- Payload User gửi ---
    String sourceCode;
    String languageKey;

    // --- Cấu hình ngôn ngữ (Lấy từ LanguageLoader) ---
    String compileCommand;
    String runCommand;
    @JsonProperty("isCompiled")
    boolean isCompiled;
    String sourceName;
    String exeName;

    // --- Giới hạn ĐÃ ĐƯỢC TÍNH TOÁN ---
    Integer finalTimeLimitMs;
    Integer finalMemoryLimitMb;
}
