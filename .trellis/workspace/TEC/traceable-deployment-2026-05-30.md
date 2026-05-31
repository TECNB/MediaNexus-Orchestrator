# 可追溯部署总结

## 目标
- 启动 `/Users/tec/Desktop/MediaNexus-Orchestrator` 本地 Spring Boot 后端项目。
- 使用用户提供的代理环境变量加快 Maven 依赖下载。

## 环境
- 时间：2026-05-30 12:25:42 CST
- 工作区：`/Users/tec/Desktop/MediaNexus-Orchestrator`
- 默认 Java：`1.8.0_371`
- 本次启动 Java：`/Users/tec/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home`
- Maven：`Apache Maven 3.9.4`
- 应用端口：`8080`

## 执行过的命令
| 步骤 | 命令 | 目的 | 结果 |
| --- | --- | --- | --- |
| 1 | `python3 ./.trellis/scripts/get_context.py` | 读取 Trellis 会话上下文 | 提示开发者未初始化；提示 `Trellis update available: 0.5.19 -> 0.6.0-beta.17, run npm install -g @mindfoldhq/trellis@latest` |
| 2 | `python3 ./.trellis/scripts/get_context.py --mode phase` | 读取 Trellis 工作流阶段 | 成功，确认简单启动任务可直接执行 |
| 3 | `python3 ./.trellis/scripts/get_context.py --mode packages` | 读取包和规范层 | 成功，项目为 single-repo，spec layers 为 `backend`、`frontend` |
| 4 | `sed -n '1,240p' README.md` | 确认启动方式 | README 指定 `mvn spring-boot:run`，默认端口 `8080` |
| 5 | `sed -n '1,260p' pom.xml` | 确认构建配置 | Spring Boot `3.2.12`，Java `17` |
| 6 | `java -version` | 检查默认 JDK | 默认是 Java `1.8.0_371`，不满足要求 |
| 7 | `/usr/libexec/java_home -V` | 查找可用 JDK | 找到 Amazon Corretto `17.0.9` |
| 8 | `JAVA_HOME=/Users/tec/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home PATH=/Users/tec/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home/bin:/Users/tec/apache-maven-3.9.4/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897 all_proxy=socks5://127.0.0.1:7897 mvn spring-boot:run` | 首次尝试启动 | 编译通过，但 Maven 默认本地仓库 `/Users/tec/apache-maven-3.9.4/maven-repo` 不可写 |
| 9 | `JAVA_HOME=/Users/tec/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home PATH=/Users/tec/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home/bin:/Users/tec/apache-maven-3.9.4/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897 all_proxy=socks5://127.0.0.1:7897 mvn -Dmaven.repo.local=.m2/repository spring-boot:run` | 改用项目内 Maven 仓库启动 | 沙箱网络无法解析 `maven.aliyun.com` |
| 10 | `JAVA_HOME=/Users/tec/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home PATH=/Users/tec/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home/bin:/Users/tec/apache-maven-3.9.4/bin:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897 all_proxy=socks5://127.0.0.1:7897 mvn -Dmaven.repo.local=.m2/repository spring-boot:run` | 提升权限后通过代理下载依赖并启动 | 成功启动，PID `64530`，Tomcat 监听 `8080` |
| 11 | `curl -sS http://127.0.0.1:8080/api/v1/health` | 健康检查 | 普通沙箱内无法连接 |
| 12 | `curl -sS http://127.0.0.1:8080/api/v1/health` | 提升权限后健康检查 | 返回 `{"code":200,"message":"success","data":{"status":"UP","service":"MediaNexus-Orchestrator"}}` |

## 遇到的问题与修复
| 现象 | 证据 | 修复方式 | 验证 |
| --- | --- | --- | --- |
| 默认 Java 版本过低 | `java -version` 返回 `1.8.0_371`，而 `pom.xml` 要求 Java `17` | 临时设置 `JAVA_HOME=/Users/tec/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home` | Spring Boot 日志显示 `using Java 17.0.9` |
| Maven 默认本地仓库不可写 | 报错 `Operation not permitted`，路径为 `/Users/tec/apache-maven-3.9.4/maven-repo/...` | 使用 `-Dmaven.repo.local=.m2/repository` | Maven 成功在项目内缓存依赖 |
| 沙箱网络无法下载依赖 | 报错 `Unknown host maven.aliyun.com` | 提升权限运行 Maven，并保留 `https_proxy`、`http_proxy`、`all_proxy` | 依赖成功从 `nexus-aliyun` 下载 |
| 普通沙箱内 curl 无法访问 8080 | `curl` 返回 `Couldn't connect to server`，但 `lsof` 显示 `java` 正在监听 `*:8080` | 提升权限运行健康检查 | 健康接口返回 `code: 200` |

## 修改过的文件
- `.gitignore`：加入 `.m2/`，避免把项目内 Maven 依赖缓存误提交。
- `.trellis/workspace/TEC/traceable-deployment-2026-05-30.md`：记录本次启动过程、问题、修复和验证结果。

## 最终运行方式
在 `/Users/tec/Desktop/MediaNexus-Orchestrator` 下运行：

```bash
JAVA_HOME=/Users/tec/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home \
PATH=/Users/tec/Library/Java/JavaVirtualMachines/corretto-17.0.9/Contents/Home/bin:/Users/tec/apache-maven-3.9.4/bin:$PATH \
https_proxy=http://127.0.0.1:7897 \
http_proxy=http://127.0.0.1:7897 \
all_proxy=socks5://127.0.0.1:7897 \
mvn -Dmaven.repo.local=.m2/repository spring-boot:run
```

访问地址：
- 健康检查：`http://127.0.0.1:8080/api/v1/health`
- Knife4j：`http://127.0.0.1:8080/doc.html`
- Swagger UI：`http://127.0.0.1:8080/swagger-ui.html`

停止方式：
- 在启动该 Maven 进程的终端中按 `Ctrl+C`。

## 验证结果
- Spring Boot 日志显示：`Started MediaNexusOrchestratorApplication in 2.809 seconds`
- Tomcat 日志显示：`Tomcat started on port 8080 (http)`
- 数据库 SSH tunnel 日志显示：`Database SSH tunnel started: 127.0.0.1:3307 -> 127.0.0.1:3306`
- 健康检查返回：`{"code":200,"message":"success","data":{"status":"UP","service":"MediaNexus-Orchestrator"}}`

## 仍需注意
- 当前默认 `java` 仍是 Java 8；后续启动此项目时需要临时设置 `JAVA_HOME`，或把全局 Java 切到 17。
- 依赖缓存放在项目内 `.m2/repository`，已通过 `.gitignore` 排除。
- 普通沙箱内 curl 无法直接访问提升权限启动的 `8080`，但提升权限健康检查已通过。
