FROM eclipse-temurin:25-jdk-noble@sha256:ba0f0e1a33864105e1d454e88105c617ab40f71062db1f306bf58be32a3f8e5e AS build
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

FROM eclipse-temurin:25-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5 AS runtime
WORKDIR /opt/lookahead
RUN groupadd --gid 10001 lookahead && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin lookahead \
    # DLV-928: a new named volume mounted here copies this owner, so Domain can write platform reviews.
    && mkdir -p /var/lib/lookahead/platform-reviews \
    && chown 10001:10001 /var/lib/lookahead/platform-reviews && chmod 700 /var/lib/lookahead/platform-reviews
COPY --from=build --chown=10001:10001 /workspace/target/lookahead-domain-api.jar /opt/lookahead/app.jar
COPY --from=build --chown=10001:10001 /workspace/health /opt/lookahead/health
COPY --from=build --chown=10001:10001 /workspace/tools/container/trust/rds-global-bundle.pem /opt/lookahead/trust/rds-global-bundle.pem
COPY deployment/container/dns.security /opt/lookahead/dns.security
USER 10001:10001
ENV LOOKAHEAD_BIND_ADDRESS=0.0.0.0 PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 CMD ["java", "-cp", "/opt/lookahead/health", "com.lookahead.domain.health.ContainerHealthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/lookahead/app.jar"]
