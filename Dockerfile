# --- Build stage ---
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /build

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline -B

COPY src src
RUN ./mvnw -q package -DskipTests -B

# --- Runtime stage ---
FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --system wallet && useradd --system --gid wallet --no-create-home wallet
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/wallet-transfer-service.jar app.jar
RUN chown -R wallet:wallet /app

USER wallet

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
