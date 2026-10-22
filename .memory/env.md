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

## 6. CI 矩阵（§17.4）

`.github/workflows/ci.yml`：Temurin 17 / 21，`fail-fast: false`（缺中间件不得启动失败，承诺 P1）。
当前仓库托管在 Gitee，GitHub Actions 不会自动触发；Gitee Go 可直接执行 `mvn -B -ntp verify`。
