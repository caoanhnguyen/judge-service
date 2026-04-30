package com.kma.judgeservice.utils;

import com.kma.judgeservice.config.LanguageLoader;
import com.kma.judgeservice.dto.LanguageConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LanguageDockerImageMapper {

    private final LanguageLoader languageConfigLoader;

    public String getImageName(String languageKey) {
        if (languageKey == null) return null;
        LanguageConfig cfg = languageConfigLoader.getConfigByKey(languageKey.trim().toUpperCase());
        return (cfg != null) ? cfg.getDockerImageName() : null;
    }
}