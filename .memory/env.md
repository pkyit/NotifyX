# 环境事实

> 只记录**非敏感**环境信息。凭据（密码 / token / 私钥）一律不入库，见交付方口述或密钥管理。
> 本仓库是公开仓库。

## 1. 本地开发机（Windows）

| 项 | 值 |
| --- | --- |
| 工作目录 | `C:\Users\admin\Downloads\NotifyX` |
| JDK | Oracle JDK 17.0.13（`C:\Program Files\Java\jdk-17`） |
| Maven | 3.8.8（`C:\Users\admin\apache-maven`） |
| Shell | PowerShell（DSH 工具 `pwsh`） |
| 默认 locale | zh_CN，GBK 控制台 → 读中文文件需 `-Encoding UTF8` |
| git remote | `git@gitee.com:pengkaiyan/NotifyX.git`（SSH 已可用，`git ls-remote` 通） |
| git 身份 | `pkyit2 <pky1999@qq.com>` |

## 2. 交付服务器 `10.10.10.102`

| 项 | 值 |
| --- | --- |
| 用途 | 容器 / 数据库联调环境，后续 P1–P3（Redis store、MySQL/PG 审计落库、MQ）使用 |
| 系统 | Linux（root 账号） |
| 运行时 | Docker 已安装 |
| 数据库 | MySQL 与 PostgreSQL，均在 Docker 中，库口令统一（**不入库，见交付方**） |
| 凭据 | 不记录 |

## 3. Kubernetes 集群

`kubectl` 可在 Windows 宿主机直接执行（`kubectl get nodes -A` 已验证可用）。

| 节点 | 角色 | 版本 |
| --- | --- | --- |
| k8s-master1 | control-plane | v1.32.13 |
| k8s-master2 | control-plane | v1.32.13 |
| k8s-master3 | control-plane | v1.32.13 |
| k8s-node1 | worker | v1.32.13 |
| k8s-node2 | worker | v1.32.13 |
| k8s-node3 | worker | v1.32.13 |

**用途规划**：P1 之后做「多实例重复发送」验证（§11.5 选型表）、优雅关闭滚动发布验证、
Admin/Consumer 部署验证。SDK 阶段不依赖 k8s。

## 4. 构建基线（iter-18 实测）

```text
mvn -B clean verify   → BUILD SUCCESS，12 模块，188 tests，0 failures
                         Spotless 0 violation / Checkstyle 0 violation
                         JaCoCo：core 79.8%（门禁 76%）、spring 92.2%（门禁 90%）、
                                 autoconfigure 62%+（门禁 60%）、logback 85.2%（门禁 80%）、
                                 channel-webhook 84.7%（门禁 80%）、channel-email 79%（门禁 75%）
mvn -Prelease -Dgpg.skip=true package → 两个 starter 均产出 jar/sources/javadoc（保留，不执行真实发布）
构建耗时（增量）约 40 秒；首次含插件下载约 2 分钟
```

**注意**：用 `-pl <module>` 单独构建前先 `mvn install`，否则本地仓库里的旧核心 jar
会让新增的 core 类（如 `Aggregator`）找不到；或直接加 `-am`。

**注意**：core 的单测需要真实 MDC 实现，已加 `logback-classic`（test scope）+
`notifyx-core/src/test/resources/logback-test.xml` 收敛日志。

## 5. 真实 SMTP（iter-18 实测）

| 项 | 值 |
| --- | --- |
| 服务商 | 163 邮箱，`smtp.163.com:465`，SSL（`mail.smtp.ssl.enable=true`） |
| 认证 | 客户端授权码；用户名=完整邮箱地址；凭据只在使用时注入，不入库 |
| 结果 | 直连渠道两封 `SUCCESS`；sample 管线两个接收人均 `SUCCESS`（见 journal/2026-10-22） |
| 注意 | `Transport.send(message)` 依赖 `Session` 的 `Authenticator` 提交凭据；只设 `mail.smtp.auth=true` 会 535 |

## 6. Kubernetes 集群（iter-21 实测）

| 项 | 值 |
| --- | --- |
| 集群 | 3 control-plane + 3 worker，Anolis OS 8.10，containerd 1.7.29，v1.32.13 |
| 节点 IP | 10.10.10.201~203（master）/ 10.10.10.211~213（node） |
| 可用镜像 | `registry.aliyuncs.com/pkyit/java:jdk17-font`（公开、国内可拉取） |
| 限制 | 国内网络拉不到 Docker Hub；没有该仓库的推送凭据，生产镜像需交付方提供仓库 |
| 验证 | Pod 内 `kubectl cp` fat jar + `java -jar`，两条 email 审计 SUCCESS（iter-21） |
| 清理 | 验证后 namespace `notifyx-demo` 已删除 |

**国内镜像清单**（`registry.cn-hangzhou.aliyuncs.com/pkyit/`，用户提供，均可拉取）：

| 镜像 | 用途 |
| --- | --- |
| `java:jdk17-font` | 运行 sample / 构建自有镜像的基座 |
| `redis:6.2.23` | T1 分布式去重/限流（iter-22 已部署） |
| `kafka:4.3.1` / `rabbitmq:3.13.0` | P3 MQ 总线 |
| `nacos:2.5.3` | 配置中心（Admin/P4 可能用） |
| `nginx:1.31.7` | 网关/静态资源 |
| `minio:RELEASE.2025-04-22T22-12-26Z` | 对象存储 |

## 7. Redis（iter-22 实测）

| 项 | 值 |
| --- | --- |
| 部署 | `deploy/k8s/redis.yaml`，namespace `notifyx-infra`，Deployment `redis`（Running，k8s-node2） |
| 连接 | `kubectl -n notifyx-infra port-forward svc/redis 16379:6379` |
| 测试 | `NOTIFYX_REDIS_HOST=127.0.0.1 NOTIFYX_REDIS_PORT=16379 mvn -pl notifyx-store-redis test -Dtest=RedisStoresLiveTest` → 5/5 |
| 注意 | demo 未设密码；生产需密码/TLS/Sentinel 或 Cluster，并限制网络访问 |

## 8. 数据库（iter-24 实测）

| 项 | 值 |
| --- | --- |
| MySQL | `10.10.10.102:3306`，8.0.46；测试库 `notifyx_test`（root/密码见交付方，不入库） |
| PostgreSQL | `10.10.10.102:5432`，16.15；测试库 `notifyx_test`（postgres/密码见交付方，不入库） |
| 验证 | `JdbcStoresLiveTest` 在 MySQL 与 PostgreSQL 各 2/2 通过（审计写入、失败记录 CAS/requeue） |
| DDL | `notifyx-store-jdbc/src/main/resources/sql/{mysql,postgresql,h2}/schema.sql` |
| 注意 | 测试库仅用于验证；生产需建独立 schema/用户并做权限最小化 |

## 9. Kafka（iter-25 实测）

| 项 | 值 |
| --- | --- |
| 部署 | `deploy/k8s/kafka.yaml`，namespace `notifyx-infra`，KRaft 单节点（Running，k8s-node3） |
| 镜像 | `registry.cn-hangzhou.aliyuncs.com/pkyit/kafka:4.3.1` |
| 连接 | `kubectl -n notifyx-infra port-forward svc/kafka 19092:9092`（advertised 为 `localhost:19092`） |
| 测试 | `NOTIFYX_KAFKA_BOOTSTRAP=127.0.0.1:19092 mvn -pl notifyx-bus-kafka -am test -Dtest=KafkaBusLiveTest` → 1/1 |
| 注意 | 单节点 replication=1，仅用于验证；生产需多副本/多 broker |

## 10. RabbitMQ（iter-28 实测）

| 项 | 值 |
| --- | --- |
| 部署 | `deploy/k8s/rabbitmq.yaml`，namespace `notifyx-infra`（Running，k8s-node2） |
| 镜像 | `registry.cn-hangzhou.aliyuncs.com/pkyit/rabbitmq:3.13.0` |
| 连接 | `kubectl -n notifyx-infra port-forward svc/rabbitmq 15672:5672` |
| 测试 | `NOTIFYX_RABBITMQ_HOST=127.0.0.1 NOTIFYX_RABBITMQ_PORT=15672 mvn -pl notifyx-bus-rabbitmq -am test -Dtest=RabbitMqBusLiveTest` → 1/1 |
| 注意 | 默认 guest/guest；生产需独立用户/vhost 与 TLS |

## 11. CI 矩阵（§17.4）

`.github/workflows/ci.yml`：Temurin 17 / 21，`fail-fast: false`（缺中间件不得启动失败，承诺 P1）。
当前仓库托管在 Gitee，GitHub Actions 不会自动触发；Gitee Go 可直接执行 `mvn -B -ntp verify`。
