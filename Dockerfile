# syntax=docker/dockerfile:1.6

## Build stage
FROM rust:1.82-slim-bookworm AS builder

# Install minimal build dependencies
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        pkg-config \
        libssl-dev \
        build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Cache dependencies by compiling a dummy binary to warm the cargo cache
COPY Cargo.toml Cargo.lock ./
RUN mkdir src \
    && echo "fn main() {}" > src/main.rs \
    && cargo build --locked --release \
    && rm -rf src

# Copy the actual source and perform the real build
COPY src ./src
RUN cargo build --locked --release

## Runtime stage
FROM debian:bookworm-slim AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && adduser --system --group --home /nonexistent iptv

WORKDIR /app

# Copy the optimized binary from the builder
COPY --from=builder /app/target/release/iptv /usr/local/bin/iptv

# Add an entrypoint to translate environment variables into CLI flags
# Normalize line endings in case the source checkout uses CRLF and ensure
# the script remains executable in the runtime image.
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/entrypoint.sh \
    && chmod +x /usr/local/bin/entrypoint.sh

USER iptv

EXPOSE 7878

ENV RUST_LOG=info

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["--help"]
