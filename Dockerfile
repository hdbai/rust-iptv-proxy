################
# Builder
FROM rust:1.76-alpine AS builder

ARG TARGET=x86_64-unknown-linux-musl

# Install build dependencies
RUN apk add --no-cache musl-dev

# Set working directory
WORKDIR /app

# Copy dependency manifests
COPY Cargo.lock Cargo.toml ./

# Create a dummy main.rs to cache dependencies
RUN mkdir -p src \
    && echo "fn main() {}" > src/main.rs \
    && cargo build --release --locked --target ${TARGET} \
    && rm -rf src target/${TARGET}/release/deps/iptv*

# Copy actual sources
COPY src ./src

# Build application
RUN cargo build --release --locked --target ${TARGET}

################
# Runtime
FROM alpine:3.19 AS runtime

ARG TARGET=x86_64-unknown-linux-musl

# Install runtime dependencies and create a non-root user
RUN apk add --no-cache ca-certificates tzdata \
    && addgroup -g 1000 appuser \
    && adduser -u 1000 -G appuser -s /bin/sh -D appuser

# Copy binary and entrypoint
COPY --from=builder /app/target/${TARGET}/release/iptv /usr/local/bin/iptv
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
