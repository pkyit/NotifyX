# 发布手册（Maven Central）

> 对应设计书 §19.1。发布链路是 P0 的「第一件事」：功能写完才发现发不出去，是开源项目最常见的死法。

## 0. 前置条件（一次性）

| # | 事项 | 检查方式 |
| --- | --- | --- |
| 1 | namespace `io.github.pkyit` 已在 Central Portal 验证 | 能登录 <https://central.sonatype.com> 并看到 namespace |
| 2 | GPG 密钥已生成、已上传公钥 | `gpg --list-secret-keys`、`gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>` |
| 3 | `~/.m2/settings.xml` 里有 `central` 账号 | `<server><id>central</id><username>…</username><password>…</password></server>` |
| 4 | 版本号已按 §19.3 更新（`1.0.0-SNAPSHOT` → `1.0.0`） | `grep '<version>' pom.xml` |

> ⚠️ 凭据与私钥**绝不入库**。CI 里用 Secrets，本机用 `~/.m2/settings.xml` 与 gpg-agent。

## 1. 本地验证（无需密钥）

```bash
# 验证 release profile 能产出 sources/javadoc jar（签名跳过）
mvn -Prelease -Dgpg.skip=true clean package
```

检查每个 `target/` 下都有：

- `<artifact>-<version>.jar`
- `<artifact>-<version>-sources.jar`
- `<artifact>-<version>-javadoc.jar`

`notifyx-bom` / `notifyx-parent` 是 `pom` packaging，不要求后两者。

## 2. 先发 SNAPSHOT 验证全链路

```bash
mvn -Prelease deploy
```

`central-publishing-maven-plugin` 会把 SNAPSHOT 推到 Central Portal 的 snapshot 仓库，
不进入 release 索引。**通过这一步再发正式版**。

## 3. 发布正式版

```bash
mvn -Prelease deploy
```

- `autoPublish=false`：上传后到 Central Portal 的 *Publishing* 页面**人工点 Publish**。
  这是刻意的闸门——Central 的 release **不可撤回、不可覆盖**。
- `waitUntil=published`：等待 Portal 完成校验（约数十秒到数分钟）。

## 4. 发布后验证

```bash
# 坐标能否解析（Central 索引有延迟，通常 10~30 分钟）
mvn dependency:get -Dartifact=io.github.pkyit:notifyx-core:1.0.0
```

再检查 <https://central.sonatype.com/artifact/io.github.pkyit/notifyx-core> 的
POM、sources、javadoc 三个附件齐全。

### 两个 starter 坐标（ADR-001）

发布物里有两个 starter：

| 坐标 | 角色 | 发布要求 |
| --- | --- | --- |
| `notifyx-spring-boot-starter` | **主坐标**，README / 快速开始只推它 | 正常 jar + sources + javadoc |
| `notifyx-spring-boot3-starter` | 归档 alias，POM 带 `<relocation>` 指向主坐标 | 同样要有 sources/javadoc（jar 里只有一个标记类） |

发布后用一个临时工程验证 relocation 生效：

```xml
<dependency>
  <groupId>io.github.pkyit</groupId>
  <artifactId>notifyx-spring-boot3-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```bash
mvn dependency:tree
# 期望看到 WARNING: ... has been relocated to io.github.pkyit:notifyx-spring-boot-starter ...
```

## 5. 版本策略（§19.3）

| 变更 | 版本 |
| --- | --- |
| 删除/重命名 public API、SPI 签名、配置语义变更 | major |
| 新渠道、新 SPI（带默认实现）、新配置项（有默认值） | minor |
| Bug 修复、依赖升级、文档 | patch |
| 阶段产出 | `1.0.0-M1` / `1.0.0-RC1` |

**NotifyX 版本号不跟随 Spring Boot**：支持的 Boot 范围写在 `spring-boot.version` property 与 README 兼容矩阵里。

## 6. 常见卡点

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `401 Unauthorized` | settings.xml 里没有 `central` server | 补 server 配置，注意 id 必须等于 `publishingServerId` |
| `Invalid signature` / `gpg: signing failed` | CI 无私钥 / 私钥无口令回环 | 导入私钥，或本地 `gpg --pinentry-mode loopback` 验证 |
| `Missing sources/javadoc` | 空模块没有产物 | 本仓库给 starter 加了 `package-info.java` 兜底 |
| Javadoc 语法错误导致构建失败 | `failOnError=true` | 本地先跑 `mvn -Prelease -Dgpg.skip=true package` |
| 发布后才发现版本有 bug | — | 只能发新版本；这就是 `autoPublish=false` 的意义 |

## 7. 已发布版本

| 版本 | 日期 | 部署 ID | 签名密钥 |
| --- | --- | --- | --- |
| `1.0.0` | 2026-10-27 | `703e22fd-430d-4529-8ab7-55694769a463` | `3E4342CF17F69E0FFC44BD240B40F80108F892C5` |

- 公钥：`docs/notifyx-release-key.asc`（同时上传 keyserver.ubuntu.com 与 keys.openpgp.org）。
- 发布方式：`mvn -Prelease deploy` 上传 bundle，随后通过 Central Publisher API
  `POST /api/v1/publisher/deployment/{id}` 触发 publish；POM 保持 `autoPublish=false`。
- 发布状态：2026-10-27 09:38:42 **PUBLISHED**；17 个模块的 POM/jar/sources/javadoc/asc
  在 `repo1.maven.org` 均可访问。
- 发布产物：17 个模块的 jar/pom（jar 模块含 sources/javadoc）与 `.asc` 签名。
- 验证：`mvn dependency:get -Dartifact=io.github.pkyit:notifyx-core:1.0.0`，
  以及 Central 页面上的三个附件。
- 注意：`1.0.0` 不可撤回；后续修复必须发布新 patch 版本。
