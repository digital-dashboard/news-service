FROM dhi.io/amazoncorretto:21-alpine3.23

LABEL authors="jmulenga" \
      org.opencontainers.image.title="argus"

WORKDIR /app

# Assumes the pipeline already ran `mvn clean verify` in this workspace.
COPY --chown=65532:65532 target/argus.jar argus.jar
COPY --chown=65532:65532 target/classes/com/j11a/argus/probe/HealthProbe.class probe/com/j11a/argus/probe/HealthProbe.class

# Also read by the HEALTHCHECK JVM; its explicit -Xmx32m overrides MaxRAMPercentage.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"

EXPOSE 8080
USER 65532:65532

# The image has no shell, curl or wget, so the probe is a Java class. Stacks must not override this.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xmx32m", "-cp", "/app/probe", "com.j11a.argus.probe.HealthProbe"]

ENTRYPOINT ["java", "-jar", "argus.jar"]
