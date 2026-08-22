# 构建阶段：Maven + JDK 17 打包
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline -DskipTests
COPY src ./src
RUN mvn -B package -DskipTests

# 运行阶段：JRE 17
FROM eclipse-temurin:17-jre
WORKDIR /home/user/app
COPY --from=build /build/target/litlebro-1.0.0.jar /home/user/app/app.jar
# 魔搭创空间规定外部端口必须为 7860
ENV SERVER_PORT=7860
EXPOSE 7860
ENTRYPOINT ["java", "-jar", "/home/user/app/app.jar"]
