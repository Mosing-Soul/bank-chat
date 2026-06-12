# 第一阶段：使用 Maven + JDK 8 构建 JAR 包
FROM maven:3.9.9-eclipse-temurin-8 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# 第二阶段：使用 JRE 8 运行应用
FROM eclipse-temurin:8-jre-alpine
WORKDIR /app

# 创建非 root 用户（提升安全性）
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# 从构建阶段复制 JAR 文件
COPY --from=builder /app/target/*.jar app.jar

# 启动应用（JVM 参数适配容器环境）
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]