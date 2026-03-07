package com.kma.judgeservice.dto;

import lombok.Data;

@Data
public class TestCaseInfo {
    private String inputName;   // VD: "1.in"
    private String outputName;  // VD: "1.out"
    private int score;          // VD: 50
    private String inputMd5;
    private String outputMd5;
    private long inputSize;
    private long outputSize;
}
