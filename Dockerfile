# ==========================================
# Bước 1: Build ứng dụng (Build Stage)
# ==========================================
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy file cấu hình thư viện vào trước để tận dụng cache của Docker
COPY pom.xml .

# Tải trước các thư viện (các lần build sau nhanh hơn nếu pom.xml không đổi)
RUN mvn -B dependency:go-offline

COPY src ./src

# Bỏ qua test ở bước này: CI (.github/workflows/ci.yml) đã chạy toàn bộ test trước khi merge.
RUN mvn -B clean package -DskipTests

# ==========================================
# Bước 2: Chạy ứng dụng (Run Stage)
# ==========================================
# Đổi từ Alpine (musl) sang Ubuntu (glibc) để thử lỗi SSL handshake với Supabase
FROM eclipse-temurin:21-jre

# wget cho HEALTHCHECK
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

# Không chạy bằng root
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

# Thư mục dành cho STORAGE_PROVIDER=local (chỉ để dev). Production dùng Supabase Storage.
RUN mkdir -p .local/uploads .local/private-uploads && chown -R app:app /app
USER app

# Giới hạn heap theo RAM của container; chết hẳn khi hết bộ nhớ để nền tảng khởi động lại thay vì treo.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Render tự ghi đè bằng biến môi trường PORT
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD wget -qO- "http://localhost:${PORT:-8080}/api/v1/ping" > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]