# iter-07 · ADR-001 alias artifact（主坐标 + 归档坐标）

- **日期**：2026-10-22
- **分支**：`build/starter-alias` → `master`
- **类型**：build（P0 收尾）
- **关联**：ADR-001、D-013（新增）

## 1. 背景

ADR-001 方案 C：主推 `notifyx-spring-boot-starter`，`notifyx-spring-boot3-starter` 作为
alias 长期保留，POM 用 `<relocation>` 指向主坐标，让老项目升级时在 Maven/IDE 里看到迁移提示。
此前仓库只有 `-boot3-` 一个坐标，且它承担了「业务方唯一依赖」的角色，与 ADR 相反。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 主坐标不存在，只有 Boot 3 后缀坐标 | `pom.xml` modules 只有 `notifyx-spring-boot3-starter` | `git mv` 成 `notifyx-spring-boot-starter` 作为主坐标 |
| 2 | alias 若只放 relocation、jar 里没有可文档化类型，`mvn -Prelease` 的 javadoc 会失败 | iter-02 已踩过「空模块 No public or protected classes」 | alias 保留一个标记类 + package-info，sources/javadoc 均有产物 |
| 3 | relocation 是否真的生效需要验证，不能只看配置 | — | 安装后用临时工程 `mvn dependency:tree`，确认出现 `relocated to ...notifyx-spring-boot-starter` 警告并解析到主坐标 |
| 4 | 用户可能同时引两个坐标 | relocation 优先，依赖壳兜底 | alias POM 额外依赖主坐标；README/发布手册解释二者关系 |

## 3. 改动点

- `git mv notifyx-spring-boot3-starter notifyx-spring-boot-starter`：
  artifactId / name / description 改为主坐标，依赖不变（autoconfigure + core）。
- 新增 `notifyx-spring-boot3-starter`（归档坐标）：
  - POM 带 `<distributionManagement><relocation>` 指向主坐标（含中文迁移 message）；
  - 依赖主坐标（依赖壳，relocation 未生效时也能用）；
  - 标记类 `NotifyxBoot3StarterAlias` + `package-info.java`，保证 Central 的
    `-sources.jar` / `-javadoc.jar` 校验通过。
- parent POM：modules 增加主坐标（排在 alias 前）；dependencyManagement 同时管理两个坐标。
- `notifyx-bom`：两个坐标都进 BOM。
- `notifyx-sample`：依赖改为主坐标。
- README：快速开始改主坐标 + 一段「坐标说明」；模块表拆成主坐标/alias 两行。
- `docs/release.md`：新增「两个 starter 坐标」小节与 relocation 验证步骤。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，12 模块，118 tests，0 failures（新增 alias 模块无测试，门禁跳过）

mvn -B -Prelease -Dgpg.skip=true -DskipTests clean package
→ BUILD SUCCESS；两个 starter 均有 jar + sources + javadoc：
   notifyx-spring-boot-starter-*.jar / -sources.jar / -javadoc.jar
   notifyx-spring-boot3-starter-*.jar / -sources.jar / -javadoc.jar

relocation 实测（临时工程依赖 -boot3-）：
→ WARNING: ... has been relocated to io.github.pkyit:notifyx-spring-boot-starter ...
→ dependency:tree 顶层为 notifyx-spring-boot-starter → autoconfigure → spring → core
```

## 5. 回滚

把目录改回 `notifyx-spring-boot3-starter`、删除 alias 模块与 relocation、sample/README/BOM
还原即可。已发布到 Central 的坐标**不能撤回**，所以回滚只应发生在未发布前。

## 6. 下一步

- P0 全部完成（除需要外部凭据的真实 deploy）。
- P1：聚合（§9.5）、升级（§9.6）、失败落盘补偿（D-003）、email 契约测试。
- ISSUE-013：覆盖率（core 67.7% → 90%，spring 21.6% → 60%+）。
