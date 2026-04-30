package com.kma.judgeservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.FieldDefaults;

@Data
@FieldDefaults(level = lombok.AccessLevel.PRIVATE)
public class LanguageConfig {

    String languageKey;
    String displayName;
    String aceMode;
    String compileCommand;
    String runCommand;
    @JsonProperty("isCompiled")
    boolean isCompiled;

    String sourceName;
    String exeName;
    Double timeMultiplier;
    Double memoryMultiplier;
    Integer memoryLimitAllowance;
    Integer timeLimitAllowance;
    String dockerImageName;
}