FROM eclipse-temurin:21-jdk-noble@sha256:bd6ac06e2a2df928af21c75c4a8a0f2829a93377a65761d6a577bb32281e2ac3 AS build
ARG RELEASE_VERSION=dev
ARG BUILD_VERSION=0.0.1-SNAPSHOT
ARG SOURCE_REVISION=local
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
# DLV-914: the jar is lookahead-domain-api-<version>.jar and /actuator/info reports version, release tag and commit.
RUN ./mvnw --batch-mode --no-transfer-progress "-Drevision=$BUILD_VERSION" "-Drelease.tag=$RELEASE_VERSION" "-Dsource.revision=$SOURCE_REVISION" verify \
    && test -f "/workspace/target/lookahead-domain-api-$BUILD_VERSION.jar" \
    && mkdir /workspace/health \
    && javac --release 21 -d /workspace/health src/main/java/com/lookahead/domain/health/ContainerHealthcheck.java

FROM eclipse-temurin:21-jre-noble@sha256:161310cd5bd41fd549d19cf9f90b614897cb4a1532555c613b43b7e0628fe5d6 AS runtime
ARG RELEASE_VERSION=dev
ARG BUILD_VERSION=0.0.1-SNAPSHOT
ARG SOURCE_REVISION=local
LABEL org.opencontainers.image.version="$RELEASE_VERSION" \
      org.opencontainers.image.revision="$SOURCE_REVISION" \
      com.lookahead.app="domain"
WORKDIR /opt/lookahead
RUN groupadd --gid 10001 lookahead && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin lookahead \
    # DLV-928: a new named volume mounted here copies this owner, so Domain can write platform reviews.
    && mkdir -p /var/lib/lookahead/platform-reviews \
    && chown 10001:10001 /var/lib/lookahead/platform-reviews && chmod 700 /var/lib/lookahead/platform-reviews \
    && chown 10001:10001 /tmp && chmod 1777 /tmp
COPY --from=build --chown=10001:10001 /workspace/target/lookahead-domain-api-${BUILD_VERSION}.jar /opt/lookahead/app.jar
COPY --from=build --chown=10001:10001 /workspace/health /opt/lookahead/health
COPY --from=build --chown=10001:10001 /workspace/tools/container/trust/rds-global-bundle.pem /opt/lookahead/trust/rds-global-bundle.pem
COPY deployment/container/dns.security /opt/lookahead/dns.security
# ECS copies a VOLUME path's ownership into the task's empty bind mount; without it the Fargate
# mount at /tmp is root-owned 0755 and Tomcat (UID 10001) cannot create its tempDir (DLV-968). /tmp keeps the
# conventional sticky 1777 mode: Docker gives an unparameterised tmpfs the directory's mode, so local stacks that
# mount `/tmp:rw,noexec,nosuid,size=…` stay writable; only the ownership is new.
VOLUME ["/tmp"]
USER 10001:10001
ENV LOOKAHEAD_BIND_ADDRESS=0.0.0.0 PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=6 CMD ["java", "-cp", "/opt/lookahead/health", "com.lookahead.domain.health.ContainerHealthcheck"]
ENTRYPOINT ["java", "-jar", "/opt/lookahead/app.jar"]
