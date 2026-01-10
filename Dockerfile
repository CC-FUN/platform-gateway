# 假设 2026 年已有 eclipse-temurin:25-jre-alpine 或类似的基础镜像
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# 创建非 root 用户 (安全最佳实践)
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 从构建阶段复制分层文件
COPY target/*.jar app.jar

# 暴露端口 (Gateway & Actuator)
EXPOSE 9000 9002

# 启动命令 (启用 CDS 或 ZGC 等 Java 25 特性)
ENTRYPOINT ["java", \
  "-Xms512m", \
  "-Xmx512m", \
  "-XX:+UseZGC", \
  "-XX:+ZGenerational", \
  "-jar", \
  "app.jar"]