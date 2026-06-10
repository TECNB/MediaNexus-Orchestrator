# 修复数据库 SSH 隧道启动

## Goal

修复 Java 后端启动时内置数据库 SSH 隧道无法连接的问题。用户已确认同一台机器上手动 `ssh root@107.172.224.11` 可以密码登录；本次复现确认 JSch 在读取服务端 SSH banner 前被关闭，但系统 OpenSSH 可以稳定登录和转发，因此内置隧道收敛为 OpenSSH-only。

## What I Already Know

- `logs/dev-run.log` 显示 Spring Boot 已初始化 Tomcat，随后 `databaseSshTunnelLifecycle` 启动失败。
- 根因是 `com.jcraft.jsch.JSchException: connection is closed by foreign host`，失败发生在 `Session.connect(...)` 读取 SSH banner 时，尚未进入 MySQL 连接阶段。
- `.env` 开启了 `MEDIANEXUS_DB_SSH_TUNNEL_ENABLED=true`，目标为 `root@107.172.224.11:22`。
- 本机 `3307` 和 `8080` 未发现监听占用。
- 用户手动 SSH 可以登录远端 Ubuntu 24.04。
- OpenSSH askpass 探针确认 `.env` 中的 SSH 密码可用。
- 启动验证确认 OpenSSH 可建立 `127.0.0.1:3307 -> 127.0.0.1:3306` 隧道，健康检查返回 200。
- 正式部署会和数据库同机，届时应关闭 `MEDIANEXUS_DB_SSH_TUNNEL_ENABLED` 并把数据库 URL 指向服务器本机 MySQL 端口。

## Requirements

- 让内置 SSH 隧道在密码登录可用的环境下更稳定地完成认证。
- 内置 SSH 隧道直接使用系统 OpenSSH，不再保留 JSch 路径。
- 正式部署关闭内置隧道时，不校验 SSH 连接字段，也不启动任何 SSH 进程。
- 保持隧道功能为条件启用；禁用时不得影响应用启动。
- 增强失败日志，但不得输出密码、token 或其他敏感配置。
- 不引入新的框架或大型依赖。

## Acceptance Criteria

- [x] 内置隧道使用系统 OpenSSH 建立本地端口转发。
- [x] `MEDIANEXUS_DB_SSH_TUNNEL_ENABLED=false` 时不启动 OpenSSH，也不要求 SSH 凭据。
- [x] SSH tunnel 失败日志包含目标主机、端口、用户和本地转发目标，便于定位，但不泄露密码。
- [x] 改动范围保持在 SSH tunnel 生命周期代码内。

## Out of Scope

- 不改数据库 schema、MyBatis mapper 或业务服务。
- 不改 `.env` 中的真实密钥。
- 不运行全量 Maven build/test。
- 不新增 SSH 客户端库，并移除 JSch 依赖。

## Technical Notes

- 关键文件：`src/main/java/com/medianexus/orchestrator/config/DatabaseSshTunnelLifecycle.java`。
- 项目规范要求条件集成的必填项在启用并启动时校验。
- 日志规范要求不要输出密码、API key、authorization token 等敏感值。
