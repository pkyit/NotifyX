# iter-02 · 发布链路（设计书 §19.1 / §19.3）

- **日期**：2026-10-22
- **分支**：`build/release-pipeline` → `master`
- **类型**：build / chore（P0 第 2 步）
- **关联**：ISSUE-002（推进）、ISSUE-009（关闭）、D-009（新增）

## 1. 背景

§19.1 把发布链路放在 P0 第一件事：「多数开源项目不是死于设计，是死于最后一步发不出去」。
此前的 parent POM 只有依赖管理，没有 GPG、没有 Central 插件、没有 source/javadoc 绑定，
`mvn deploy` 不可能通过 Central 校验。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 发布链路完全缺失 | `pom.xml` 只有 compiler/surefire/boot 插件；无 GPG / Central / source / javadoc 绑定 | 新增 `release` profile，四件事一次配齐 |
| 2 | POM 元数据指向 GitHub，实际托管在 Gitee | `url` / `scm` / `issueManagement` 均为 `github.com/pkyit/NotifyX`，`git remote -v` 为 gitee | 统一改为 `gitee.com/pengkaiyan/NotifyX`（ISSUE-009 关闭） |
| 3 | **空聚合 starter 产不出 sources/javadoc** | 构建日志：`No sources in project. Archive not created.`、`No Javadoc in project. Archive not created.`；补 `package-info.java` 后 javadoc 报 `error: No public or protected classes found to document.` | 增加不可实例化标记类 `NotifyxStarter` + `package-info.java`，两个附件均可产出（D-009） |
| 4 | Javadoc 告警：`{@link DedupeStore}` 解析不到 | `NotifyPipeline.java:71` 用了未 import 的类型（record 里写的是全限定名） | 补 import，record 改用短名 |
| 5 | 插件版本不确定 | 直接查 Maven Central metadata：central-publishing `0.12.0`、gpg `3.2.8`、javadoc `3.10.1`、source `3.3.1` | 用实测存在的稳定版本，写进 properties |

## 3. 改动点

**`pom.xml`（parent）**：

- 元数据：`url` / `scm` / `issueManagement` / `developer.url` 改为 Gitee。
- properties：`maven-gpg-plugin.version=3.2.8`、`maven-javadoc-plugin.version=3.10.1`、
  `maven-source-plugin.version=3.3.1`、`central-publishing-plugin.version=0.12.0`、`gpg.skip=false`。
- 新增 `release` profile（**默认不激活，日常 `mvn verify` 完全不受影响**）：
  - `maven-source-plugin`：`jar-no-fork` 附加 sources；
  - `maven-javadoc-plugin`：`jar` 附加 javadoc，`doclint=none` + `failOnError=true`（语法问题必须暴露）；
  - `maven-gpg-plugin`：`verify` 阶段签名，`--pinentry-mode loopback`，支持 `-Dgpg.skip=true`；
  - `central-publishing-maven-plugin`：`publishingServerId=central`、`autoPublish=false`（**人工确认闸门**）、`waitUntil=published`。

**新增 `docs/release.md`**：前置条件（namespace/GPG/settings.xml）、本地验证、先 SNAPSHOT 后 release、
人工发布、发布后验证、版本策略、6 个常见卡点。

**`notifyx-spring-boot3-starter`**：新增 `NotifyxStarter` 标记类 + `package-info.java`。

**`NotifyPipeline`**：补 `DedupeStore` import，修 Javadoc 链接。

**README**：兼容性矩阵、发布命令、`/demo/log-error` 示例；移除「Logback Appender 规划中」的过期描述。

## 4. 验证

```text
mvn -Prelease -Dgpg.skip=true verify
→ BUILD SUCCESS，11 模块，43 tests 全绿，sources/javadoc 全部生成

产物核对（target/）：
notifyx-core / spring / autoconfigure / starter / channel-log / channel-email /
channel-webhook / logback / sample —— 9 个 jar 模块均有 -sources.jar 与 -javadoc.jar
（notifyx-bom、notifyx-parent 为 pom packaging，不需要）
```

**未验证**：真正的 `mvn -Prelease deploy` 需要 Central 账号 + GPG 私钥，本机没有；
`autoPublish=false` 要求人工到 Central Portal 点发布，无法在本地闭环。ISSUE-002 保持 OPEN。

## 5. 回滚

删除 parent POM 的 `<profiles>` 与新增 properties、删除 `docs/release.md`、
删除 starter 的两个类即可；不影响日常构建与已发布坐标结构。

## 6. 下一步

- iter-03：质量门禁 + CI（Spotless/Checkstyle/JaCoCo + GitHub Actions）。
- 需要交付方提供：Central Portal 账号、GPG 密钥，才能完成 §19.1 验收。
- ADR-001 alias artifact（`notifyx-spring-boot-starter` 依赖壳）仍待做，排在 P0 尾部。
