FROM eclipse-temurin:24-jdk@sha256:7493205ffe6caa8074fa8a06a276bb1c5ac41d3dd0fd43a0db66d7f776e80b3e AS build
WORKDIR /workspace
# Keep the wrapper on the checksum-pinned ZIP distribution (its tar fallback
# has different bytes). This package is needed only in the build stage.
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
COPY tools/container/Healthcheck.java tools/container/Healthcheck.java
RUN ./mvnw --batch-mode --no-transfer-progress verify \
    && mkdir /workspace/health \
    && javac --release 21 -d /workspace/health tools/container/Healthcheck.java

FROM eclipse-temurin:24-jre@sha256:8cb2387a28af84cf0db0948d9c67d4480192f4e567027a3963f145d218e8b4f2 AS runtime
WORKDIR /opt/lookahead
RUN groupadd --gid 10001 lookahead && useradd --uid 10001 --gid 10001 --no-create-home lookahead
COPY --from=build --chown=10001:10001 /workspace/target/lookahead-domain-api.jar /opt/lookahead/app.jar
COPY --from=build --chown=10001:10001 /workspace/health /opt/lookahead/health
USER 10001:10001
ENV LOOKAHEAD_BIND_ADDRESS=0.0.0.0 PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 CMD ["java", "-cp", "/opt/lookahead/health", "Healthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/lookahead/app.jar"]
