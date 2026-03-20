package com.kma.judgeservice.dto;

import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class TestCaseInfo {
    String inputName;   // VD: "1.in"
    String outputName;  // VD: "1.out"
    int score;          // VD: 50
    long inputSize;
    long outputSize;
    String strippedOutputMd5;
}
