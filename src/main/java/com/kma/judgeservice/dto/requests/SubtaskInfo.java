package com.kma.judgeservice.dto.requests;

import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SubtaskInfo {
    int subtaskId;
    int score;
    List<TestCaseInfo> testCases;
}
