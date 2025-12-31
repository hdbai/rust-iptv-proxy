################
# Builder
FROM maven:3.9.8-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml ./
RUN mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

################
# Runtime
FROM eclipse-temurin:21-jre

WORKDIR /app

RUN addgroup --system appuser && adduser --system --ingroup appuser appuser

COPY --from=builder /app/target/iptv-proxy-0.1.0.jar /usr/local/bin/iptv-proxy.jar
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER appuser

EXPOSE 7878

ENV IPTV_USER="" \
    IPTV_PASSWD="" \
    IPTV_MAC="" \
    IPTV_IMEI="" \
    IPTV_BIND=0.0.0.0:7878 \
    RUST_LOG=info

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
