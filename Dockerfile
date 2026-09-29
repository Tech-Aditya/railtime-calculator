# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# RailTime Calculator — multi-stage production image
#   build   : Maven + JDK 25 → tested, layered Spring Boot JAR
#   runtime : JRE 25 (Alpine), non-root, layered for fast rebuilds
# ---------------------------------------------------------------------------

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Resolve dependencies first so they are cached until pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
# Tests run by default; pass --build-arg SKIP_TESTS=true only when CI has already run them.
ARG SKIP_TESTS=false
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests=${SKIP_TESTS} \
 && java -Djarmode=tools -jar target/railtime-calculator.jar extract --layers --launcher --destination target/extracted

# ---------- runtime stage ----------
FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -S railtime && adduser -S -G railtime -H -s /sbin/nologin railtime
WORKDIR /app

# Least-frequently-changing layers first for maximum cache reuse.
COPY --from=build --chown=railtime:railtime /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=railtime:railtime /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=railtime:railtime /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=railtime:railtime /workspace/target/extracted/application/ ./

USER railtime

# PORT: the app listens on $PORT (server.port=${PORT:8080}). Render injects PORT itself;
# locally the default 8080 applies. JAVA_TOOL_OPTIONS sizes the heap from the container limit,
# so it also fits small instances (e.g. 512 MB).
ENV SPRING_PROFILES_ACTIVE=prod \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC"

EXPOSE 8080

# Container-level check for Docker/Compose (Render uses healthCheckPath from render.yaml instead).
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health/liveness" >/dev/null || exit 1

# exec form: java is PID 1 and receives SIGTERM directly, so graceful shutdown works.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
