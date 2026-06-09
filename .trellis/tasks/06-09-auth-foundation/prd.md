# 登录注册认证基础能力

## 背景

MediaNexus-Orchestrator 需要先补齐真实账号认证能力，供 MediaNexus 前端登录页和注册页后续接入。第一批只实现认证基础，不做业务接口登录墙、额度限制、任务用户隔离和管理员管理接口。

## 范围

- 新增正式 `users` 表。
- 注册等同于注册码注册，没有公开普通注册路径。
- 注册接口：`POST /api/v1/auth/register`。
- 登录接口：`POST /api/v1/auth/login`，支持用户名或邮箱作为 `account`。
- 登出接口：`POST /api/v1/auth/logout`。
- 当前用户接口：`GET /api/v1/auth/me`。
- Sa-Token 发放登录 token。
- 后端从 `Authorization: Bearer <token>` 读取 token。
- 密码用 BCrypt 存储，明文密码校验规则为 8-32 字符，不强制复杂度。
- 用户名和邮箱按小写归一化保存与登录匹配。
- 角色字段保留 `ADMIN` / `USER`，但第一批不做管理员初始化和管理接口。
- 删除 `test-users` 演示 CRUD 及其建表初始化。
- 更新 README，移除旧 Test User 说明，补认证配置/API、BCrypt hash 生成命令、管理员 SQL 模板。

## 非目标

- 不做业务接口登录墙。
- 不做每日额度。
- 不做 `user_action_usage`。
- 不做磁力任务 `user_id` 隔离。
- 不做禁用用户。
- 不做管理员自动初始化。
- 不做管理员管理接口。
- 不提交、不推送，除非用户后续明确要求。

## 验收

- Auth DTO、controller、service、mapper、model 能编译。
- 注册码缺失、错误、重复用户名/邮箱、登录失败有清晰业务错误。
- 登录/注册成功返回 `token` 和 `user`。
- `/auth/me` 可通过 Bearer token 读取当前用户。
- README 与当前能力一致，不再描述 `test_users` 演示接口。
