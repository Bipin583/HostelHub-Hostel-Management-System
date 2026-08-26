# Build and run the Spring Boot service.
#
# Two stages: Maven builds the jar, a JRE image runs it. The build tooling never
# reaches the runtime image, so what ships is a JRE, the application, and nothing
# that could compile or download anything.

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Dependencies resolve in their own layer, keyed on the pom alone. Editing Java
# then rebuilds without re-downloading the world; editing the pom is the only
# thing that invalidates it.
COPY pom.xml ./
RUN mvn -B --no-transfer-progress -q dependency:go-offline

COPY src ./src

# Tests are not run here. CI runs them (.github/workflows/ci.yml) where a Docker
# daemon exists for Testcontainers; running the integration suite inside an image
# build would need Docker in Docker to prove what CI has already proven.
RUN mvn -B --no-transfer-progress -q -DskipTests package

# Renamed here rather than globbed in the runtime stage, because COPY expands a
# wildcard to whatever matched and then needs a name to call it -- and `package`
# leaves both backend-0.0.1-SNAPSHOT.jar and its .jar.original behind.
#
# Spring Boot's layered-jar extraction (`-Djarmode=tools extract --layers`) would
# split dependencies from application classes and shrink the delta on a rebuild.
# It is not used because the payoff is smaller pushes to a registry this project
# does not push to, and the cost is a layout that cannot be verified without a
# Docker daemon. One certainly-correct COPY beats a clever one nobody has run.
RUN cp target/backend-*.jar app.jar

# ---------- runtime ----------
FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# An unprivileged user, because nothing this process does needs root, and a
# container escape should not land on one.
RUN addgroup -S app && adduser -S -G app app

COPY --from=build --chown=app:app /build/app.jar ./app.jar

USER app
EXPOSE 8080

# Actuator's health endpoint is already exposed with show-details: never, so it
# reports liveness without describing the datasource to whoever asks. wget is
# BusyBox's, present in the Alpine base; no curl is installed and none is needed.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health >/dev/null 2>&1 || exit 1

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes its heap from the
# container's cgroup limit instead of the host's total memory, which is the
# difference between honouring a memory limit and being OOM-killed by it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
