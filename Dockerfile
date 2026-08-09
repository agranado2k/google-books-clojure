# Both stages must run the same Java major (21) and are pinned by digest:
# refresh the two digests deliberately, and together.
FROM clojure:temurin-21-tools-deps@sha256:d08d523486049ae293012a57a267d290d0400378e097ea84eddb5e8337510a7a AS build
WORKDIR /app
COPY deps.edn build.clj ./
RUN clojure -P && clojure -P -T:build
COPY src ./src
RUN clojure -T:build uber

FROM eclipse-temurin:21-jre@sha256:8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55
WORKDIR /app
RUN useradd -r -u 1001 app
USER app
# target/app.jar is uber-file in build.clj — keep the two in step.
COPY --from=build /app/target/app.jar app.jar
CMD ["java", "-jar", "app.jar"]
