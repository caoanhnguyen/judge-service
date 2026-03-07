package com.kma.judgeservice.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class JudgeSdi {
    private UUID problemId;            // ID problem (để tìm đúng thư mục đề)
    private String languageKey;        // Nhận từ languages.json (VD: "CPP", "JAVA")
    private String sourceCode;         // Mã nguồn user nộp
}
