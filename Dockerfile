# Builds the API into a small image that runs as an unprivileged user.
#   docker build -t liftonway-api .
# Configure it with environment variables (see deploy/.env.example); it listens on 8080.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon --quiet bootJar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --no-create-home liftonway
WORKDIR /app
COPY --from=build /src/build/libs/liftonway-backend.jar app.jar
USER 10001
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
