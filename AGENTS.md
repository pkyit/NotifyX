# AGENTS.md

## 项目定位

- NotifyX 是 **Java 17 + Maven 多模块** 的业务语义层告警 / 通知 SDK，Spring Boot 基线为 3.5.16，CI 覆盖 JDK 17 / 21。
- 架构契约以 `docs/design.md` 为准；实现与文档冲突时，先改文档或明确记录偏离原因，不能静默改行为。
- 当前交付状态看 `.memory/STATE.md`。当前范围是设计书 P0/P1 SDK；Redis、JDBC、MQ、Admin、IM 渠道属于 P2+，未经明确要求不要自行实现。
- 修改对外行为或配置前，先查 `README.md`、`docs/design.md` 和 `.memory/decisions.md`，避免推翻既有 ADR / D-NNN。

## 常用命令

```bash
mvn -B -ntp clean verify                         # 全量构建 + 186 个测试 + Spotless/Checkstyle/JaCoCo
mvn -pl <module> -am test                        # 只测指定模块及其依赖
mvn -pl <module> -am -Dtest=<TestClass> test     # 只跑一个测试类
mvn -pl notifyx-sample spring-boot:run           # 启动示例；需先 mvn install
mvn -Prelease -Dgpg.skip=true clean package      # 本地验证 sources/javadoc 产物，不要求 GPG 私钥
```

- 交付前至少运行与改动范围匹配的 `verify`；架构、门禁、依赖、公共 API 变更必须跑全量 `mvn -B -ntp clean verify`。
- 未获得明确授权时，不要执行 `deploy`、发布 Central、打正式 tag，也不要修改版本号。
- Maven 会按需下载依赖；如果网络 / 仓库访问失败，先如实报告失败原因，不要为绕过问题修改依赖版本。

## 模块与依赖纪律

| 模块 | 角色 / 约束 |
| --- | --- |
| `notifyx-bom` | 统一版本，不放实现代码 |
| `notifyx-core` | 领域模型、SPI、管线、去重 / 限流、模板、脱敏、补偿；**只允许依赖 SLF4J**，禁止 Spring、DB、Redis、MQ、渠道 SDK |
| `notifyx-spring` | AOP 注解、SpEL、优先级执行器；只依赖 core 与 Spring |
| `notifyx-spring-boot3-autoconfigure` | 自动装配、配置属性、生命周期、诊断、健康检查；**不得直接依赖任何渠道模块** |
| `notifyx-spring-boot-starter` | 业务方唯一主坐标；空聚合，禁止污染 compile classpath |
| `notifyx-spring-boot3-starter` | 归档 alias，通过 relocation 指向主坐标；不要新增功能 |
| `notifyx-channel-log` / `email` / `webhook` | 具体渠道；只依赖 core 与自身协议依赖，渠道之间不得互相依赖 |
| `notifyx-logback` | L0 日志接入；core + Logback（provided） |
| `notifyx-sample` | 可运行验收示例；允许聚合 starter 和全部渠道，但不承载 SDK 逻辑 |

新增依赖前先确认：能否用已有接口 / 默认实现消化？是否破坏上述边界？是否只是把问题转移给业务方？

## 实现红线

- 通知永远是旁路：Filter、切面、Appender 内部异常不得抛回业务线程；`@AlarmProtect` 必须原样 rethrow 原业务异常。
- 不允许阻塞业务线程的同步 I/O、`CallerRunsPolicy`、无超时 HTTP 或无限等待；异步路径必须有界、可关闭、可观测。
- 队列、去重缓存、失败存储、补偿扫描都必须有容量上限和溢出策略，不能把内存当可靠存储。
- 渠道异常必须区分可重试 / 不可重试；认证、参数、协议类错误不能被盲目重试。
- 保留 Logback 自递归防护。CRITICAL 事件直通免打扰、限流、聚合，去重窗口减半且不超过 60 秒；升级只改渠道，不改 Level。
- 密钥、Token、收件人、业务参数进入日志、指标、审计、`toString()` 前必须脱敏；不得提交任何真实凭据。
- 指标标签禁止使用 receiver、bizId、eventId 等高基数字段。
- 不使用 `System.out`；通过日志 / 指标 / 诊断输出问题。
- 新增 public API 必须有 Javadoc，并保持向后兼容；破坏性变更必须先更新契约和 ADR。
- 行为变化必须有测试，尤其是管线、生命周期、AOP、Logback、渠道契约、失败落盘 / 补偿路径。

## 质量门禁

- `verify` 统一执行 Spotless、Checkstyle、JaCoCo。
- 覆盖率当前基线：core ≥ 65%，logback ≥ 80%，其他有测试模块 ≥ 40%；不要为了通过门禁降低断言或删除有效测试。
- Spotless 只做低风险格式治理：未使用 import、import 顺序、行尾空白、文件末尾换行；不要为了格式工具大规模重排代码。
- 文件使用 UTF-8、LF、4 空格缩进（JSON/YAML 2 空格），遵循 `.editorconfig` 与 `.gitattributes`。
- 修复测试时优先找根因；禁止条件跳过、弱化断言、修改门禁阈值来“变绿”。

## 文档与项目记忆

- 架构契约、模块边界、公共 API、配置语义变化，必须同步更新 `docs/design.md`；用户可见能力同步更新 `README.md`。
- 大改动（一个独立分支 / 一次合并）必须按 `.memory/README.md` 新增 `iterations/YYYY-MM-DD-iter-NN-<slug>.md`，并在同一提交中更新 `.memory/STATE.md`。
- 刻意偏离设计书时新增 D-NNN 到 `.memory/decisions.md`；旧结论不删除，被推翻时新增条目并标注 superseded。
- 发现缺陷 / 技术债 / 环境坑时登记 `.memory/issues.md`，必须写证据（命令输出、测试名、文件行号），不写主观猜测。
- `.memory` 是追加式记忆库；不要重写历史结论，也不要记录明文凭据。

## Git 与交付

- 大改动使用 `<type>/<slug>` 分支，例如 `feat/file-failure-store`、`fix/logback-recursion`；小修复可直接提交。
- 合并回 `master` 使用 `--no-ff`，合并信息包含 `[iter-NN]`，与 iteration 文件一一对应。
- 提交信息遵循 `.skills/skills-git-commit-standard/SKILL.md`：Gitmoji + type(scope) + 简短标题，并关联 Issue / iteration；保持原子提交。
- 不要重写 `master` 历史，不要提交构建产物、日志、IDE 配置或本地缓存。