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

## 4. 构建基线（iter-00 实测）

```text
mvn -B clean verify   → BUILD SUCCESS，11 模块，73 tests，0 failures
                         Spotless 0 violation / Checkstyle 0 violation
                         JaCoCo：core 67.7%（门禁 65%）、logback 85.2%（门禁 80%）、autoconfigure 41.7%（门禁 40%）
构建耗时（增量）约 40 秒；首次含插件下载约 2 分钟
```

## 5. CI 矩阵（§17.4）

`.github/workflows/ci.yml`：Temurin 17 / 21，`fail-fast: false`（缺中间件不得启动失败，承诺 P1）。
当前仓库托管在 Gitee，GitHub Actions 不会自动触发；Gitee Go 可直接执行 `mvn -B -ntp verify`。
