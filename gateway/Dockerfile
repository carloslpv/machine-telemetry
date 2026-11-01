# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests

# temurin images are multi-arch: the same file builds for a PC, a Raspberry Pi 4 (arm64) or an ARM TV box
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --groups dialout --no-create-home app \
	&& mkdir /data && chown app:app /data
WORKDIR /app
COPY --from=build /workspace/target/gateway.jar gateway.jar
USER app
ENV GATEWAY_CONFIG=/etc/machine-telemetry/gateway.yaml GATEWAY_DATA_DIR=/data
VOLUME /data
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "--sun-misc-unsafe-memory-access=allow", "--enable-native-access=ALL-UNNAMED", "-jar", "gateway.jar"]
