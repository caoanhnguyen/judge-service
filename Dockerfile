# ==========================================
# STAGE 1: Build file JAR ứng dụng
# ==========================================
FROM maven:3.9.6-eclipse-temurin-21 AS builder

# Tạo thư mục làm việc
WORKDIR /app

# Copy file cấu hình Maven và thư mục mã nguồn vào
COPY pom.xml .
COPY src ./src

# Chạy lệnh build của Maven để đóng gói ra file JAR (bỏ qua chạy Test để build nhanh hơn)
RUN mvn clean package -DskipTests

# ==========================================
# STAGE 2: Tạo Image chạy thực tế (Runtime)
# ==========================================
FROM eclipse-temurin:21-jdk-jammy

# Thiết lập thư mục làm việc
WORKDIR /app

# [CÚ CHỐT BẮT BUỘC PHẢI CÓ CHO DooD]
# Cài đặt Docker CLI để Judge Service có thể gọi lệnh docker xuống máy chủ Host
RUN apt-get update && \
    apt-get install -y docker.io && \
    rm -rf /var/lib/apt/lists/*

# Copy file JAR đã được build từ STAGE 1 sang STAGE 2
COPY --from=builder /app/target/*.jar app.jar

# Khai báo port
EXPOSE 8090

# Lệnh khởi chạy ứng dụng khi container start
ENTRYPOINT ["java", "-jar", "app.jar"]