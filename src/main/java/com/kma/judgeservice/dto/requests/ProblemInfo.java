package com.kma.judgeservice.dto.requests;

import lombok.Data;
import java.util.List;

@Data
public class ProblemInfo {
    private String problemId;
    private List<SubtaskInfo> subtasks;
    private List<TestCaseInfo> testCases;
}
