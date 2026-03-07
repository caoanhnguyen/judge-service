package com.kma.judgeservice.dto;

import lombok.Data;
import java.util.List;

@Data
public class ProblemInfo {
    private String problemId;
    private List<TestCaseInfo> testCases;
}
