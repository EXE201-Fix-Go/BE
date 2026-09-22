# ==========================================
# Bước 1: Build ứng dụng (Build Stage)
# ==========================================
FROM maven:3.9.6-eclipse-temurin-25 AS builder

# Thiết lập thư mục làm việc trong container
WORKDIR /app

# Copy file cấu hình thư viện vào trước để tận dụng cache của Docker
COPY pom.xml .

# Tải trước các thư viện (giúp các lần build sau nhanh hơn nếu pom.xml không đổi)
RUN mvn dependency:go-offline

# Copy toàn bộ mã nguồn vào container
COPY src ./src

# Build ứng dụng, bỏ qua test để tăng tốc độ deploy trên Render
RUN mvn clean package -DskipTests

# ==========================================
# Bước 2: Chạy ứng dụng (Run Stage)
# ==========================================
# Sử dụng image JRE (chỉ chứa môi trường chạy Java) siêu nhẹ
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Tạo thư mục upload để tránh lỗi không tìm thấy đường dẫn khi app khởi chạy
RUN mkdir -p .local/uploads

# Copy file .jar đã được build từ Bước 1 sang Bước 2
# Spring Boot thường tạo ra file jar trong thư mục target/
COPY --from=builder /app/target/*.jar app.jar

# Khai báo port (Render sẽ tự động ghi đè bằng biến môi trường PORT)
EXPOSE 8080

# Lệnh khởi chạy ứng dụng
ENTRYPOINT ["java", "-jar", "app.jar"]