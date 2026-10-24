# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Maven mirror is configurable at build time. Empty (default) = Maven Central, so the
# image builds anywhere; docker-compose.aegis-alpha.yml passes the Aliyun mirror by default.
ARG MAVEN_MIRROR_URL=
RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 \
      && printf '%s\n' \
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">' \
        '  <mirrors>' \
        '    <mirror>' \
        '      <id>build-mirror</id>' \
        '      <mirrorOf>central</mirrorOf>' \
        "      <url>${MAVEN_MIRROR_URL}</url>" \
        '    </mirror>' \
        '  </mirrors>' \
        '</settings>' > /root/.m2/settings.xml; \
    fi

# Dependency layer first: only re-runs when pom.xml (or the mirror ARG) changes.
COPY pom.xml .
RUN mvn -B -ntp -Dmaven.wagon.http.retryHandler.count=5 dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update \
  && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/*

ENV AEGIS_ALPHA_PORT=5178
COPY --from=build /workspace/target/aegis-alpha-api.jar /app/aegis-alpha-api.jar

EXPOSE 5178
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${AEGIS_ALPHA_PORT}/_backend/auth/me" >/dev/null || test $? -eq 22

ENTRYPOINT ["java", "-jar", "/app/aegis-alpha-api.jar"]
