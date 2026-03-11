package com.kma.judgeservice.service.impl;

import com.kma.judgeservice.service.TestcaseManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestcaseManagerImpl implements TestcaseManager {

    private final MinioClient minioClient;

    @Value("${oj.judge.cache-dir}")
    private String cacheDir;

    @Value("${oj.storage.minio.bucket-testcase}")
    private String bucketName;

    /**
     * Trả về đường dẫn thư mục chứa testcase.
     * Có cache thì dùng cache, không có thì tự tải về từ MinIO!
     */
    @Override
    public Path getOrDownloadTestcases(UUID problemId) {
        Path problemCacheDir = Paths.get(cacheDir, problemId.toString());
        Path infoJsonPath = problemCacheDir.resolve("info.json");

        // 1. KIỂM TRA CACHE (LOCAL HIT)
        // Dấu hiệu nhận biết cache chuẩn là file info.json phải tồn tại
        if (Files.exists(infoJsonPath)) {
            log.info("Cache HIT: Đã có sẵn testcase cho Problem [{}] tại local", problemId);
            return problemCacheDir;
        }

        // 2. CACHE MISS -> KÉO TỪ MINIO
        log.info("Cache MISS: Đang tải testcase cho Problem [{}] từ MinIO...", problemId);
        try {
            // Tạo thư mục nếu chưa có
            Files.createDirectories(problemCacheDir);

            String minioBasePath = "problems/" + problemId + "/testcases";

            // 2.1 Tải file info.json
            downloadFile(minioBasePath + "/info.json", infoJsonPath);

            // 2.2 Tải file testcases.zip
            Path zipPath = problemCacheDir.resolve("testcases.zip");
            downloadFile(minioBasePath + "/testcases.zip", zipPath);

            // 3. GIẢI NÉN VÀ DỌN RÁC
            log.info("Đang giải nén testcases...");
            unzip(zipPath, problemCacheDir);

            // Xóa cái file .zip đi cho nhẹ ổ cứng
            Files.deleteIfExists(zipPath);

            log.info("Hoàn tất tải và giải nén testcase cho Problem [{}]", problemId);
            return problemCacheDir;

        } catch (Exception e) {
            log.error("Lỗi cmnr khi tải testcase từ MinIO cho bài [{}]", problemId, e);
            throw new RuntimeException("Không thể chuẩn bị testcase: " + e.getMessage());
        }
    }

    private void downloadFile(String objectName, Path targetPath) throws Exception {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build())) {
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void unzip(Path zipFilePath, Path destDirectory) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFilePath))) {
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                Path newFilePath = destDirectory.resolve(zipEntry.getName());

                // Tránh bẫy "Zip Slip" vulnerability (bảo mật cực kỳ quan trọng)
                if (!newFilePath.normalize().startsWith(destDirectory.normalize())) {
                    throw new IOException("Bad zip entry: " + zipEntry.getName());
                }

                if (!zipEntry.isDirectory()) {
                    Files.copy(zis, newFilePath, StandardCopyOption.REPLACE_EXISTING);
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }
}
