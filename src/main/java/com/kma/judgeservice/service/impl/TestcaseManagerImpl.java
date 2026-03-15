package com.kma.judgeservice.service.impl;

import com.kma.judgeservice.service.TestcaseManager;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils; // Import cái này để xóa thư mục cho tiện

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    @Value("${oj.storage.minio.bucket-testcase}")
    private String bucketName;

    @Value("${oj.judge.cache-dir}")
    private String cacheDir;

    @Value("${oj.storage.minio.prefix-problem:problems}")
    private String prefixProblem;

    @Value("${oj.storage.minio.suffix-testcase:testcases}")
    private String suffixTestcase;

    public Path getOrDownloadTestcases(UUID problemId) {
        Path problemCacheDir = Paths.get(cacheDir, problemId.toString());
        Path localInfoJsonPath = problemCacheDir.resolve("info.json");
        String minioBasePath = prefixProblem + "/" + problemId + "/" + suffixTestcase;
        try {
            // 1: Lấy bản vân tay (info.json) mới nhất từ MinIO về RAM
            log.info("Đang kiểm tra phiên bản testcase của Problem [{}]...", problemId);
            String remoteInfoJson = getFileContentFromMinio(minioBasePath + "/info.json");

            // 2: Kiểm tra đối chiếu với Cache local
            boolean isCacheValid = false;
            if (Files.exists(localInfoJsonPath)) {
                String localInfoJson = Files.readString(localInfoJsonPath, StandardCharsets.UTF_8);
                // Nếu 2 chuỗi JSON giống hệt nhau -> Không có sự thay đổi nào từ Moderator -> Cache vẫn còn giá trị sử dụng
                if (remoteInfoJson.equals(localInfoJson)) {
                    isCacheValid = true;
                }
            }

            // 3: Quyết định sử dụng Cache hay tải lại
            if (isCacheValid) {
                log.info("Cache HIT: Bộ testcase vẫn là bản mới nhất, dùng local cache!");
                return problemCacheDir;
            }

            // --- NẾU CACHE BẨN HOẶC CHƯA CÓ CACHE ---
            log.info("Cache MISS hoặc ĐÃ CŨ: Tiến hành dọn dẹp và tải lại từ MinIO...");

            // XÓA SẠCH thư mục cũ (nếu có), sau đó tạo lại thư mục mới
            FileSystemUtils.deleteRecursively(problemCacheDir);
            Files.createDirectories(problemCacheDir);

            // Ghi file info.json mới nhất xuống ổ cứng
            Files.writeString(localInfoJsonPath, remoteInfoJson, StandardCharsets.UTF_8);

            // Tải file testcases.zip
            Path zipPath = problemCacheDir.resolve("testcases.zip");
            downloadFile(minioBasePath + "/testcases.zip", zipPath);

            // Giải nén và dọn rác Zip
            log.info("Đang giải nén bộ testcase mới...");
            unzip(zipPath, problemCacheDir);
            Files.deleteIfExists(zipPath);

            log.info("Hoàn tất cập nhật bộ testcase chuẩn cho Problem [{}]", problemId);
            return problemCacheDir;

        } catch (Exception e) {
            log.error("Lỗi khi chuẩn bị testcase từ MinIO cho bài [{}]", problemId, e);
            throw new RuntimeException("Lỗi hệ thống lưu trữ testcase: " + e.getMessage());
        }
    }

    // --- CÁC HÀM ULTILITY ---
    private String getFileContentFromMinio(String objectName) throws Exception {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build())) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
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
                // 1. Bỏ qua tất cả các thư mục, ta chỉ quan tâm đến CÁC FILE
                if (!zipEntry.isDirectory()) {

                    // 2. Móc lấy CÁI TÊN FILE CỤ THỂ, vứt bỏ toàn bộ đường dẫn cha bên trong ZIP
                    // Ví dụ: "Welcome To Java/1.in" -> Lấy đúng "1.in"
                    String fileName = Paths.get(zipEntry.getName()).getFileName().toString();

                    // Chặn mấy cái file rác ẩn do MacOS tự động sinh ra khi nén ZIP
                    if (!fileName.startsWith("._") && !fileName.equals(".DS_Store") && !zipEntry.getName().contains("__MACOSX")) {

                        // 3. Ép file bung ra thẳng thư mục gốc của Problem
                        Path newFilePath = destDirectory.resolve(fileName);

                        // Copy đè dữ liệu
                        Files.copy(zis, newFilePath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }
}