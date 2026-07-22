# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /workspace
# The root reactor pom.xml lists all 9 modules - Maven validates every
# declared <module> directory exists before it even looks at -pl/-am, so
# the whole backend/ context is needed here even though -am only actually
# *builds* skybook-common (this module's one real dependency) plus this
# module itself.
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl api-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# spring-boot-maven-plugin repackages with a classifier (root pom.xml) so
# this module's plain jar stays the main artifact for Maven/Failsafe - the
# executable fat jar is the -exec one, the only jar actually runnable here.
COPY --from=builder /workspace/api-gateway/target/*-exec.jar app.jar
EXPOSE 8080
# Run as a non-root user (SECURITY_HARDENING_MODULE.md §8); the read-only
# rootfs + writable /tmp tmpfs is applied at runtime in docker-compose.yml.
RUN useradd -r -u 1001 skybook && chown -R skybook /app
USER skybook
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
