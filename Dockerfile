# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# RailTime Calculator — multi-stage production image
#   build   : Maven + JDK 25 → tested, layered Spring Boot JAR
#   runtime : JRE 25 (Alpine), non-root, layered, with a JVM AOT cache for fast cold starts
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
# Extracted (not nested) JARs: the layout the JVM AOT cache needs, split into layers by change frequency.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests=${SKIP_TESTS} \
 && java -Djarmode=tools -jar target/railtime-calculator.jar extract --layers --destination target/extracted

# ---------- runtime stage ----------
FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -S railtime && adduser -S -G railtime -H -s /sbin/nologin railtime
WORKDIR /app

# Least-frequently-changing layers first for maximum cache reuse.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

# PORT: the app listens on $PORT (server.port=${PORT:8080}). Render injects PORT itself;
# locally the default 8080 applies. JAVA_TOOL_OPTIONS sizes the heap from the container limit and
# leaves about a third for metaspace, code cache and thread stacks, so it fits Render's 512 MB
# instances; Serial GC has the smallest footprint on a single small CPU. The same options are used for the
# AOT training run below, so the cache matches the runtime JVM configuration.
ENV SPRING_PROFILES_ACTIVE=prod \
    PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC -Xss512k"

# JVM AOT cache (JEP 483/514/515): a training run starts the Spring context once and records the
# loaded and linked classes and method profiles, roughly halving startup time. That matters on
# Render's free plan, where an idle instance is stopped and the next request waits for a cold start.
# If the cache ever doesn't match the JVM, the JVM ignores it and starts normally.
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar railtime-calculator.jar \
 && chown -R railtime:railtime /app

USER railtime

EXPOSE 8080

# Container-level check for Docker/Compose (Render uses healthCheckPath from render.yaml instead).
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health/liveness" >/dev/null || exit 1

# exec form: java is PID 1 and receives SIGTERM directly, so graceful shutdown works.
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "railtime-calculator.jar"]
