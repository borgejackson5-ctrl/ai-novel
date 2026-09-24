# ============================================================
# 后端镜像：多阶段构建
# Stage1 构建 jar；Stage2 用 jre-alpine 运行，体积小
# ============================================================

# ---------- Stage 1: Maven 构建 ----------
# JDK 版本必须与 pom 的 java.version 一致：构建阶段 JDK 低于目标版本会直接编不过
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
# 阿里云 Maven 镜像：加速国内构建时的依赖下载
COPY settings.xml /root/.m2/settings.xml
# 先只拷 pom，利用层缓存加速依赖下载
COPY pom.xml .
RUN mvn -B dependency:go-offline
# 再拷源码编译打包（跳过测试，加速镜像构建）
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Stage 2: 运行 ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# 时区 + 健康检查工具
RUN apk add --no-cache curl tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone
COPY --from=builder /build/target/ai-novel-backend.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java","-XX:+UseG1GC","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
