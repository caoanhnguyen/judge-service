package com.kma.judgeservice.service;

import java.nio.file.Path;
import java.util.UUID;

public interface TestcaseManager {
    Path getOrDownloadTestcases(UUID problemId);
}
