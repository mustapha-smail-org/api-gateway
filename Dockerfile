# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode dependency:go-offline
COPY src/ src/
RUN ./mvnw --batch-mode clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ARG VCS_REF
LABEL org.opencontainers.image.title="api-gateway" \
      org.opencontainers.image.description="CityPulse API Gateway" \
      org.opencontainers.image.revision="${VCS_REF}"

COPY --from=builder /app/target/api-gateway-*.jar app.jar

# Optional externalized config, mounted by the deployment phase. If not mounted, the application will use the default config in the jar.
ENV SPRING_CONFIG_IMPORT="optional:file:/etc/secrets/application.yaml"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
