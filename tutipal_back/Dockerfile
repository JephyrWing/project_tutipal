FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle settings.gradle gradle.lockfile ./
RUN chmod +x gradlew
COPY src src
RUN --mount=type=cache,id=tutipal-gradle,target=/root/.gradle,sharing=locked ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system tutipal && useradd --system --gid tutipal tutipal \
    && mkdir -p /app/data && chown tutipal:tutipal /app/data
COPY --from=build --chown=tutipal:tutipal /app/build/libs/server.jar server.jar
# Logged once at startup (StartupVersionLogger). Declared after the jar copy so a new SHA
# does not invalidate the cached layers above.
ARG GIT_SHA=unknown
ARG APP_VERSION=unknown
ENV GIT_SHA=$GIT_SHA APP_VERSION=$APP_VERSION
USER tutipal
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
