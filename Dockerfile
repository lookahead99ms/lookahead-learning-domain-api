FROM eclipse-temurin:21-jdk@sha256:92a2a4d7a928d057e7bd999c418d66c26a34eb9a0442f3ab67721c3f88110b2d AS build
WORKDIR /workspace
# Keep the wrapper on the checksum-pinned ZIP distribution (its tar fallback
# has different bytes). This package is needed only in the build stage.
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
COPY .mvn .mvn
COPY mvnw ./
RUN ./mvnw --batch-mode --no-transfer-progress --version
COPY pom.xml ./
COPY src src
COPY tools/container/VerifyRdsTrust.java tools/container/VerifyRdsTrust.java
COPY tools/container/trust/rds-global-bundle.pem tools/container/trust/rds-global-bundle.sha256 tools/container/trust/
WORKDIR /workspace/tools/container/trust
RUN sha256sum --check rds-global-bundle.sha256
WORKDIR /workspace
RUN java tools/container/VerifyRdsTrust.java tools/container/trust/rds-global-bundle.pem
RUN ./mvnw --batch-mode --no-transfer-progress verify \
    && mkdir /workspace/health \
    && javac --release 21 -d /workspace/health src/main/java/com/lookahead/domain/health/ContainerHealthcheck.java

FROM eclipse-temurin:21-jre@sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5 AS runtime
WORKDIR /opt/lookahead
RUN groupadd --gid 10001 lookahead && useradd --uid 10001 --gid 10001 --no-create-home lookahead
COPY --from=build --chown=10001:10001 /workspace/target/lookahead-domain-api.jar /opt/lookahead/app.jar
COPY --from=build --chown=10001:10001 /workspace/health /opt/lookahead/health
COPY --from=build --chown=10001:10001 /workspace/tools/container/trust/rds-global-bundle.pem /opt/lookahead/trust/rds-global-bundle.pem
USER 10001:10001
ENV LOOKAHEAD_BIND_ADDRESS=0.0.0.0 PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 CMD ["java", "-cp", "/opt/lookahead/health", "com.lookahead.domain.health.ContainerHealthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/lookahead/app.jar"]
