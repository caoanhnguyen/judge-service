package com.kma.judgeservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RunCodeRequest {

    // --- Thông tin định danh ---
    UUID runToken;
    UUID problemId;

    // --- Payload User gửi ---
    String sourceCode;
    String languageKey;
    List<RunTestCaseSdi> customInputs;

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

