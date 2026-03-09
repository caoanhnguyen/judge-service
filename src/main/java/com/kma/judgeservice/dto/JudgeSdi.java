package com.kma.judgeservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Data
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class JudgeSdi {
    UUID submissionId;         // ID submission (để tìm đúng thư mục submission)
    UUID problemId;            // ID problem (để tìm đúng thư mục đề)
    String languageKey;        // Nhận từ languages.json (VD: "CPP", "JAVA")
    String sourceCode;         // Mã nguồn user nộp

    // -- Thông số ngôn ngữ (từ languages.json) --
    String key;
    String displayName;
    String aceMode;
    String compileCommand;
    String runCommand;
    @JsonProperty("isCompiled")
    boolean isCompiled;
    String sourceName;
    String exeName;
    Integer memoryLimitAllowance;
    Integer timeLimitAllowance;
}
