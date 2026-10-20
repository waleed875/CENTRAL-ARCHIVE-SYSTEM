FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 archive \
    && useradd --uid 10001 --gid archive --create-home archive
WORKDIR /app
COPY --from=build /build/target/Archive-0.0.1-SNAPSHOT.jar /app/archive.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN sed -i 's/\r$//' /app/docker-entrypoint.sh \
    && chmod 755 /app/docker-entrypoint.sh \
    && mkdir -p /app/data/tmp/multipart \
    && chown -R archive:archive /app/data
USER archive
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
