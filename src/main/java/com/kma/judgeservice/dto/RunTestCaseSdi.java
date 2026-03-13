package com.kma.judgeservice.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RunTestCaseSdi {

    String input;
    String expectedOutput; // Mang gía trị null nếu là custom input
}
