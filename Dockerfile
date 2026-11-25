# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:25-jre-noble AS runtime
RUN groupadd --gid 10001 windlass \
    && useradd --uid 10001 --gid windlass --no-create-home windlass \
    && mkdir -p /app /data \
    && chown windlass:windlass /data
WORKDIR /app
COPY --from=build /workspace/target/windlass-*.jar /app/windlass.jar
ENV REF_DOCS_DIRECTORY=/data/ref-docs \
    JSON_CONF_DIRECTORY=/data/json-conf
USER 10001:10001
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/windlass.jar"]
