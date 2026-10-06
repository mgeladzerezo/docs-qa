# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
# Tests need Docker (Testcontainers) and run in CI, not inside the image build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -Dmaven.test.skip=true package \
 && cp target/docs-qa-1.0.0.jar /build/app.jar

# Fetch the embedding model and DJL's native libraries once, at build time, so that the container
# starts without network access. Cached under /opt/models, which the runtime image copies.
FROM build AS model
ENV DOCSQA_MODEL_CACHE_DIR=/opt/models \
    DJL_CACHE_DIR=/opt/models/djl
RUN java -Dloader.main=io.github.mgeladzerezo.docsqa.embedding.ModelWarmup \
      -cp /build/app.jar org.springframework.boot.loader.launch.PropertiesLauncher

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --create-home docsqa \
 && mkdir -p /data/uploads && chown -R docsqa /data
WORKDIR /app
COPY --from=build /build/app.jar /app/docs-qa.jar
COPY --from=model --chown=docsqa /opt/models /opt/models
ENV DOCSQA_MODEL_CACHE_DIR=/opt/models \
    DJL_CACHE_DIR=/opt/models/djl \
    DOCSQA_STORAGE_DIR=/data/uploads
USER docsqa
EXPOSE 8209
VOLUME /data
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/docs-qa.jar"]
