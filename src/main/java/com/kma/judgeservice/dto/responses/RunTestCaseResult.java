package com.kma.judgeservice.dto.responses;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RunTestCaseResult {
    String input;            // Input gốc gửi lên
    String output;           // Output thực tế code in ra
    String expectedOutput;   // Output mong đợi
    String verdict;          // Trạng thái (SUCCESS, RE, TLE, MLE, OLE)
    String errorMessage;     // Lỗi (nếu bị RE/MLE)
    long timeTakenMs;        // Thời gian chạy testcase này
}