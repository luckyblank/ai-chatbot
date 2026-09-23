FROM maven:3.9.9-eclipse-temurin-17-alpine AS build

WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache curl \
    && addgroup -S app \
    && adduser -S -G app app

WORKDIR /app
RUN mkdir -p /app/runtime-data /app/knowledge-seed \
    && chown -R app:app /app

COPY --from=build --chown=app:app \
    /workspace/target/ai-chatbot-service-1.0-SNAPSHOT.jar \
    /app/ai-chatbot-service.jar

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/ai-chatbot-service.jar"]
