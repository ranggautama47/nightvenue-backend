# --- Stage 1: Build ---
FROM eclipse-temurin:21.0.6_7-jdk-jammy AS build

WORKDIR /workspace/app

# Copy Maven wrapper and POM first for layer caching
COPY .mvn/ .mvn
COPY mvnw mvnw.cmd pom.xml ./

# Install unzip for maven wrapper
RUN apt-get update && apt-get install -y --no-install-recommends unzip && \
    rm -rf /var/lib/apt/lists/*

# Give execute permission and pre-download dependencies (cached layer)
RUN chmod +x ./mvnw && ./mvnw dependency:go-offline -B --no-transfer-progress

# Copy source and build
COPY src ./src
RUN ./mvnw package -DskipTests -B --no-transfer-progress

# --- Stage 2: Runtime ---
FROM eclipse-temurin:21.0.6_7-jre-jammy

# Install curl for healthcheck — explicitly listed so the tool is guaranteed present
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Security: run as non-root
RUN groupadd --system --gid 1001 appgroup && \
    useradd --system --uid 1001 --gid appgroup appuser

WORKDIR /app

# Copy the fat JAR from build stage
COPY --from=build /workspace/app/target/*.jar app.jar

USER appuser

EXPOSE 8080

# Healthcheck hits the readiness probe (includes DB + Redis dependency checks)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl --fail --silent http://localhost:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

