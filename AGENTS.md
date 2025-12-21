# AGENTS.md

本文件用于指导自动化代理/贡献者在本仓库内工作。

## 项目概览
- Rust IPTV 代理服务，提供 `/playlist` 与 `/xmltv`，可选 UDP/RTSP 代理。
- 入口：`src/main.rs`；核心模块：`src/iptv.rs`、`src/proxy.rs`、`src/args.rs`。

## 常用命令
```bash
# 构建（release）
cargo build -r

# 运行（示例）
cargo run -- -u <USER> -p <PASSWD> -m <MAC> -b 127.0.0.1:7878

# 启用可选特性
cargo build -r --features http2
cargo build -r --features tls
cargo build -r --features rustls
```

## 交叉编译（OpenWrt）
- 参考 `README.md` 的 OpenWrt/openssl 构建流程；如无需要不建议在本机执行。

## 测试
- 当前仓库未包含单元测试；如新增测试请使用 `#[test]` 并通过 `cargo test`。

## 约定
- 保持 Rust 2021 风格；避免引入不必要的依赖或网络调用。
- 更新 CLI/环境变量行为时同步更新 `README.md`。
