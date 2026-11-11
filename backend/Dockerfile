FROM maven:3.9.11-eclipse-temurin-25-alpine AS builder
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S gatekeeper && adduser -S gatekeeper -G gatekeeper
WORKDIR /app
COPY --from=builder /workspace/target/gatekeeper-backend-*.jar /app/app.jar
RUN chown -R gatekeeper:gatekeeper /app
USER gatekeeper
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

