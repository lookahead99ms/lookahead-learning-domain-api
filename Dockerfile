FROM eclipse-temurin:25-jdk-noble@sha256:80f67fb3d6f76e2d817d98a4cc2a9cf05f054d4e6adc5e2144bde671d722e63f AS build
ARG RELEASE_VERSION=dev
ARG BUILD_VERSION=0.0.1-SNAPSHOT
ARG SOURCE_REVISION=local
WORKDIR /workspace
# Keep the wrapper on the checksum-pinned ZIP distribution (its tar fallback
# has different bytes). This package is needed only in the build stage.
# DLV-969: retain Acquire::Retries=0 as defense in depth; the pinned base now includes the APT fix.
RUN apt-get -o Acquire::Retries=0 update \
    && apt-get -o Acquire::Retries=0 install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
COPY .mvn .mvn
COPY mvnw ./
RUN ./mvnw --batch-mode --no-transfer-progress --version
COPY pom.xml ./
COPY src src
# DLV-914: the jar is lookahead-domain-api-<version>.jar and /actuator/info reports version, release tag and commit.
# DLV-968: the fetched-contract tests read files that scripts/fetch-contracts.sh takes from Content on the pull
# request; the build context carries no fetched file and the CI Maven step has already run them, so they are excluded here.
RUN ./mvnw --batch-mode --no-transfer-progress "-Drevision=$BUILD_VERSION" "-Drelease.tag=$RELEASE_VERSION" "-Dsource.revision=$SOURCE_REVISION" -DexcludedGroups=fetched-contract verify \
    && test -f "/workspace/target/lookahead-domain-api-$BUILD_VERSION.jar" \
    && mkdir /workspace/health \
    && javac --release 21 -d /workspace/health src/main/java/com/lookahead/domain/health/ContainerHealthcheck.java

FROM eclipse-temurin:25-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c AS runtime
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
