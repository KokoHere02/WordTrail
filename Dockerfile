FROM maven:3.9.11-eclipse-temurin-24 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

FROM eclipse-temurin:24-jre
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 wordtrail \
    && useradd --system --uid 10001 --gid wordtrail --home-dir /app --no-create-home wordtrail
WORKDIR /app
COPY --from=build --chown=wordtrail:wordtrail /workspace/target/WordTrail-1.0.jar ./app.jar
ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER wordtrail
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
