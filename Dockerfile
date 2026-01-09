# Stage 1: 构建阶段 (使用包含 Gradle 的 JDK 25 镜像)
FROM gradle:8.5-jdk25 AS builder
WORKDIR /app

# 利用 Docker 缓存机制：先只复制依赖文件
COPY build.gradle.kts settings.gradle.kts ./
# 如果有 gradle.properties 也复制
# COPY gradle.properties ./

# 预下载依赖 (如果构建失败可以注释掉这一行，取决于 Gradle 配置)
# RUN gradle dependencies --no-daemon

# 复制源码
COPY src ./src

# 执行构建 (跳过测试以加速，生产构建通常在 CI 流水线做过测试了)
RUN gradle clean bootJar -x test --no-daemon

# 提取分层 JAR (Spring Boot 优化特性，启动更快)
WORKDIR /app/build/libs
RUN java -Djarmode=layertools -jar *.jar extract

# ==========================================

# Stage 2: 运行阶段 (仅使用 JRE，极致瘦身)
# 假设 2026 年已有 eclipse-temurin:25-jre-alpine 或类似的基础镜像
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# 创建非 root 用户 (安全最佳实践)
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 从构建阶段复制分层文件
COPY --from=builder /app/build/libs/dependencies/ ./
COPY --from=builder /app/build/libs/spring-boot-loader/ ./
COPY --from=builder /app/build/libs/snapshot-dependencies/ ./
COPY --from=builder /app/build/libs/application/ ./

# 暴露端口 (Gateway & Actuator)
EXPOSE 9000 9002

# 启动命令 (启用 CDS 或 ZGC 等 Java 25 特性)
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:+ZGenerational", \
  "org.springframework.boot.loader.launch.JarLauncher"]