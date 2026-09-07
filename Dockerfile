# syntax=docker/dockerfile:1
# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

ARG GITHUB_ACTOR

# Write settings.xml to a path NOT shadowed by the /root/.m2 cache mount
RUN --mount=type=secret,id=github_token \
    mkdir -p /root/.m2-config && \
    echo "<settings><servers><server><id>github</id><username>${GITHUB_ACTOR}</username><password>$(cat /run/secrets/github_token)</password></server></servers></settings>" > /root/.m2-config/settings.xml

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -s /root/.m2-config/settings.xml -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -s /root/.m2-config/settings.xml -B clean package -DskipTests

# --- Run stage ---
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]