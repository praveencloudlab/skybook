# OTel Java agent as a pinned OCI artifact (KUBERNETES_MODULE.md §5,
# design-review decision): pods copy the jar from this image via an
# initContainer instead of downloading from GitHub at startup - a failed
# download here is a BUILD error at image-build time, never a pod-start
# outage. Built and pushed once per agent-version bump:
#
#   docker build -t ghcr.io/praveencloudlab/skybook-otel-agent:2.14.0 docker/otel-agent
#   docker push ghcr.io/praveencloudlab/skybook-otel-agent:2.14.0

FROM curlimages/curl:8.12.1 AS fetch
ARG OTEL_AGENT_VERSION=2.14.0
# Pinned checksum, verified at BUILD time - computed from the (already
# live-verified) 2.14.0 jar the compose stack runs. A mismatch fails the
# image build; the runtime artifact is immutable and pre-verified.
ARG OTEL_AGENT_SHA256=16f8e28fa1ddcd56ed85bf633bd1d1fbc78ea7c4cc50e8c5726b2a319f5058c8
USER root
RUN curl -fsSL -o /tmp/opentelemetry-javaagent.jar \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar" \
 && echo "${OTEL_AGENT_SHA256}  /tmp/opentelemetry-javaagent.jar" | sha256sum -c -

FROM busybox:1.37
ARG OTEL_AGENT_VERSION=2.14.0
COPY --from=fetch /tmp/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar
# The initContainer runs this default command: copy the agent into the
# pod's shared emptyDir mounted at /target.
CMD ["sh", "-c", "cp /otel/*.jar /target/ && ls -la /target/"]
