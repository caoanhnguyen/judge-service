package com.kma.judgeservice.dto;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RunCodeResponse {
    UUID runToken;
    UUID problemId;
    String status;           // "COMPLETED" hoặc "FAILED" (nếu lỗi CE/Hệ thống)
    String compileMessage;   // Thông báo lỗi biên dịch (nếu có)
    List<RunTestCaseResult> results; // Mảng kết quả đầu ra
}