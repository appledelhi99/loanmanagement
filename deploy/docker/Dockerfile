# syntax=docker/dockerfile:1
#
# One image recipe for every Spring Boot service in this repository.
# Build from the repository root and name the Maven module to package:
#
#   docker build -f deploy/docker/Dockerfile \
#     --build-arg MODULE=services/config-server -t lmp/config-server:local .
#
# Stage 1 builds the module (and only the modules it needs) with the Maven Wrapper.
# Stage 2 splits the jar into Spring Boot layers so a code change rebuilds only the small top layer.
# Stage 3 is the runtime: JRE only, non-root user, no build tools.

ARG JDK_IMAGE=eclipse-temurin:25.0.4.1_1-jdk-noble
ARG JRE_IMAGE=eclipse-temurin:25.0.4.1_1-jre-noble

FROM ${JDK_IMAGE} AS build
ARG MODULE
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    test -n "${MODULE}" \
    && sh ./mvnw -B -ntp -q -pl "${MODULE}" -am package -DskipTests \
    && cp "${MODULE}/target/${MODULE##*/}.jar" /workspace/application.jar

FROM ${JDK_IMAGE} AS layers
WORKDIR /layers
COPY --from=build /workspace/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM ${JRE_IMAGE}
LABEL org.opencontainers.image.source="https://github.com/appledelhi99/loanmanagement"
RUN groupadd --system --gid 10001 spring \
    && useradd --system --uid 10001 --gid spring --no-create-home --home-dir /nonexistent \
       --shell /usr/sbin/nologin spring
COPY --chmod=0755 deploy/docker/healthcheck.sh /usr/local/bin/healthcheck
WORKDIR /application
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/application/application.jar"]
