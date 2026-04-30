package com.kma.judgeservice.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kma.judgeservice.dto.LanguageConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LanguageLoader {
    // Đổi List thành Map cho tốc độ truy xuất O(1)
    private Map<String, LanguageConfig> configMap;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void init() {
        try (InputStream is = getClass().getResourceAsStream("/languages.json")) {
            if (is == null) throw new IOException("Config file not found in resources");

            List<LanguageConfig> data = objectMapper.readValue(is, new TypeReference<List<LanguageConfig>>() {});

            // Biến List thành Map với key là getLanguageKey() (VD: "CPP", "JAVA")
            configMap = data.stream()
                    .collect(Collectors.toMap(LanguageConfig::getLanguageKey, config -> config));

            log.info("Successfully loaded {} languages into Memory Cache", configMap.size());
        } catch (IOException e) {
            log.error("Failed to load languages.json: {}", e.getMessage());
        }
    }

    // Hàm lấy cấu hình của 1 ngôn ngữ cụ thể
    public LanguageConfig getConfigByKey(String key) {
        return configMap.get(key);
    }

    // Hàm lấy tất cả (nếu bro cần hiển thị lên UI)
    public Map<String, LanguageConfig> getAllConfigs() {
        return Collections.unmodifiableMap(configMap);
    }
}