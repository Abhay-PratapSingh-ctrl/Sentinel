# Build stage
FROM maven:3.8.5-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/sentinel-bot-1.0.0.jar app.jar

# The bot expects environment variables for:
# DEPLOYER_PRIVATE_KEY
# SENTINEL_PRIVATE_KEY
# GUARDIAN_ADDRESS
# TELEGRAM_BOT_TOKEN
# ANTHROPIC_API_KEY
# GEMINI_API_KEY
# Groq_API_KEY

ENTRYPOINT ["java", "-jar", "app.jar"]
