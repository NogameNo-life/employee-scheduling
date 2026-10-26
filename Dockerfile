# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn dependency:go-offline -B

COPY src ./src

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn clean package -DskipTests -B

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /build/target/employee-scheduling.jar /app/app.jar
# TABU2 SQL Server only supports TLS 1.0, see the comments in this file
COPY src/main/jvm/legacy-tls.security /app/legacy-tls.security

# Profile (e.g. "local" for SQL logging): SPRING_PROFILES_ACTIVE. Extra JVM options: JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-Djava.security.properties=/app/legacy-tls.security", "-jar", "/app/app.jar"]
