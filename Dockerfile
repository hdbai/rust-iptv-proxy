################
##### Builder
FROM rust:1.76-alpine as builder

# 安装必要的构建工具
RUN apk add --no-cache musl-dev

# 设置工作目录
WORKDIR /app

# 复制项目依赖描述文件
COPY Cargo.lock Cargo.toml ./

# 创建一个假的 main.rs 以缓存依赖
RUN mkdir -p src && echo "fn main() {}" > src/main.rs

# 构建依赖
RUN cargo build --release --target x86_64-unknown-linux-musl

# 删除临时的 main.rs 和构建缓存
RUN rm -rf src && rm -rf target/x86_64-unknown-linux-musl/release/deps/iptv*

# 复制实际源代码
COPY src ./src

# 构建应用
RUN cargo build --release --target x86_64-unknown-linux-musl

################
##### Runtime
FROM alpine:3.19 AS runtime

# 安装运行时依赖
RUN apk add --no-cache ca-certificates tzdata

# 创建非特权用户
RUN addgroup -g 1000 appuser && \
    adduser -u 1000 -G appuser -s /bin/sh -D appuser

# 复制二进制文件
COPY --from=builder /app/target/x86_64-unknown-linux-musl/release/iptv /usr/local/bin/iptv

# 设置权限
RUN chmod +x /usr/local/bin/iptv && \
    chown appuser:appuser /usr/local/bin/iptv

# 暴露端口
EXPOSE 7878

# 设置环境变量
ENV IPTV_USER=""
ENV IPTV_PASSWD=""
ENV IPTV_MAC=""
ENV IPTV_IMEI=""
ENV RUST_LOG=info
ENV IPTV_BIND=0.0.0.0:7878

CMD /usr/local/bin/iptv -u "${IPTV_USER}" -p "${IPTV_PASSWD}" -m "${IPTV_MAC}" -i "${IPTV_IMEI}"
