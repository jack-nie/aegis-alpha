# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:8-jre
WORKDIR /app

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*

ENV MARKETMIND_PORT=5178
COPY --from=build /workspace/target/aegis-alpha-api.jar /app/aegis-alpha-api.jar

EXPOSE 5178
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${MARKETMIND_PORT}/_backend/auth/me" >/dev/null || test $? -eq 22

ENTRYPOINT ["java", "-jar", "/app/aegis-alpha-api.jar"]
