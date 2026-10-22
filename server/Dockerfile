FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon -q \
  && java -Djarmode=tools -jar build/libs/server-*.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 clud
WORKDIR /app
# Layers from least to most frequently changing, so rebuilds reuse the dependency layers.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER clud
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
