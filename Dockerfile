# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
# Tests run as part of every image build, including pull requests.
RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode --no-transfer-progress verify

FROM eclipse-temurin:25-jre-noble AS runtime
RUN groupadd --gid 10001 windlass \
    && useradd --uid 10001 --gid windlass --no-create-home windlass \
    && mkdir -p /app /data \
    && chown windlass:windlass /data
WORKDIR /app
COPY --from=build /workspace/target/windlass-*.jar /app/windlass.jar
ENV DATABASE_URL=jdbc:sqlite:/data/notifications.db
USER 10001:10001
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/windlass.jar"]
