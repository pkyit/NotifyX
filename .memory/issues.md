# 问题点与技术债（ISSUE-NNN）

> 状态：`OPEN` / `DOING` / `CLOSED`。关闭时补「关闭方式」并保留原文。
> 每条必须有证据（文件:行、命令输出、测试名），便于回溯。

---

## ISSUE-001 仓库里没有工程记忆，历史决策不可回溯
- **状态**：CLOSED（iter-00）
- **现象**：设计书 `docs/design.md` 回答「设计成什么样」，但「实际落地偏离了什么、踩过什么坑」
  只存在于对话里；没有 `.memory/` 目录，`git log` 只有 commit 标题。
- **证据**：`git log --oneline` 仅 9 条提交，提交信息无问题点/改动点描述。
- **影响**：换人/换会话接手时无法判断哪些是刻意偏离、哪些是遗漏。
- **关闭方式**：建立 `.memory/`（README/STATE/decisions/issues/env/iterations），并把「每次合并前
  必须写记忆」固化进 `.memory/README.md` 的硬规则。

## ISSUE-002 P0 发布链路（§19.1 / §21.2 第 2 步）
- **状态**：OPEN（iter-02 已把配置与本地验证做完；真正 deploy 缺账号/密钥）
- **现象**：parent pom 只有依赖管理，没有 `central-publishing-maven-plugin`、没有 GPG、
  没有 source/javadoc jar 绑定；`mvn deploy` 不可能通过 Central 校验。
- **证据**：原 `pom.xml` 只有 compiler/surefire/javadoc(pluginManagement 但未绑定)/source/boot 插件版本。
- **影响**：P0 验收标准第 ① 条「能发到 Maven Central」无法达成——这是设计书强调的
  「多数开源项目死于最后一步发不出去」的风险 R8。
- **iter-02 进展**：新增 `release` profile（source/javadoc/gpg/central-publishing 0.12.0，
  `autoPublish=false`）；`mvn -Prelease -Dgpg.skip=true verify` 通过，9 个 jar 模块均产出
  `-sources.jar` + `-javadoc.jar`；发布手册见 `docs/release.md`。
- **仍缺（阻塞项）**：Central Portal 账号与 GPG 私钥由交付方提供；实际 `mvn -Prelease deploy`
  与人工 Publish 需在有凭据的环境执行，本地无法闭环。

## ISSUE-003 质量门禁与 CI 缺失（§17.3 / §17.4）
- **状态**：CLOSED（iter-03，CI 在 Gitee 上暂不触发）
- **现象**：仓库无 `.github/workflows/`，无 Spotless/Checkstyle/SpotBugs 配置；
  `mvn verify` 只跑单测。§17.3 要求的格式、静态分析、覆盖率门禁（core ≥ 90%）一条都没有。
- **证据**：原 `Test-Path .github` → `False`；`pom.xml` 无相关插件。
- **影响**：代码风格与空指针/资源泄漏类问题只能靠人眼；CI 不过即失败的承诺无法兑现。
- **关闭方式**：iter-03 接入 Spotless（轻量步骤）、Checkstyle 10.26.1（0 violation）、
  JaCoCo（ratchet 门禁）、`.github/workflows/ci.yml`（JDK 17/21）。
- **残留**：仓库托管在 Gitee，GitHub Actions 不会自动跑；需镜像到 GitHub 或用 Gitee Go
  执行同一条 `mvn -B -ntp verify`。覆盖率目标值见 ISSUE-013。

## ISSUE-013 覆盖率未达设计目标（core 90% / 全局 80%）
- **状态**：OPEN（P1 持续改进）
- **现象**：iter-03 实测 core 67.7%、logback 85.2%、autoconfigure 41.7%；
  设计书 §17.3 要求全局 ≥ 80%、core ≥ 90%。
- **证据**：`notifyx-core/target/site/jacoco/jacoco.csv` 汇总（816/1205 行）。
- **影响**：core 是指纹/限流/模板/熔断等隐蔽 bug 高发区，覆盖率不足会以「去重静默失效」形式暴露。
- **计划**：优先补 `NotifyPipeline` 分支（重试/熔断/失败落盘）、漏斗 6 个 Filter 的组合行为、
  模板引擎边界；每轮提升后同步抬 `jacoco.minimum`。

## ISSUE-004 Spring Event 桥接（L3）与 MDC 未实现（§8.4 / §15.3）
- **状态**：OPEN（计划 iter-04）
- **现象**：`notifyx-spring` 只有 `NotifyxAspect` / `SpelEvaluator` / 两个注解 / 优先级执行器，
  没有 `@EventListener` 桥接，也没有 MDC 透传（traceId / scene）。
- **证据**：`notifyx-spring/src/main/java/.../spring/` 下 5 个类，无 Event 相关类。
- **影响**：§21.2 第 6 步验收缺一半；L3「彻底解耦」用法不可用。
- **计划**：iter-04 增加 `NotifyxEventBridge`（实现 `ApplicationListener<NotifyxEvent>`）+
  MDC 注入（`eventId`/`scene`/`traceId`），并补测试。

## ISSUE-005 优雅关闭只有执行器 destroy，缺少端到端验证（§9.10）
- **状态**：OPEN（计划 iter-05）
- **现象**：`PriorityNotifyExecutor.destroy()` 已实现 `shutdown → awaitTermination → shutdownNow`，
  但没有测试证明「Spring 上下文关闭时，队列中的通知要么发完要么被计数丢弃」；
  `DefaultNotifyKit` 的异步提交与执行器关闭顺序未验证。
- **证据**：`PriorityNotifyExecutor.java:128-140`；无 shutdown 相关测试。
- **影响**：发布重启时可能静默丢通知，且日志里没有丢弃计数。
- **计划**：iter-05 补 `GracefulShutdownTest`（ApplicationContextRunner + 慢渠道 + close），
  校验丢弃计数写入 `NotifyMetrics`。

## ISSUE-006 actuator Health / 启动诊断表未实现（§15.2 / §14.4）
- **状态**：OPEN（计划 iter-05）
- **现象**：`HealthStatus` 模型存在于 core，但没有任何 `HealthIndicator` 实现；
  `NotifyxProperties.Diagnostics` 只有 `banner` / `fail-fast` 两个开关，没有「能力表」输出。
- **证据**：grep `HealthIndicator` 无结果；`NotifyxProperties.Diagnostics` 字段仅 2 个。
- **影响**：运维无法一眼看出「当前哪些能力降级为内存实现」——这正是 §14.4 诊断表的目的。
- **计划**：iter-05 实现 `NotifyxHealthIndicator` 与启动 banner 的能力表。

## ISSUE-007 聚合（§9.5）与升级（§9.6）未实现
- **状态**：OPEN（P1 后续）
- **现象**：去重只做「窗口内静默」，没有把被去重的事件聚合成一条摘要；没有「同一场景重复 N 次
  自动升级 Level」的策略。
- **影响**：通知风暴时用户只收到第一条，无法知道「一共发生了 300 次」。
- **计划**：P1 后续迭代（在去重存储上扩展聚合桶，保持 O(1) 内存）。

## ISSUE-008 `docs/design.md` 与 `docs/NotifyX架构设计书.md` 内容重复
- **状态**：OPEN（待确认）
- **现象**：两个文件都是 212154 字节，内容疑似完全相同；README 只引用 `design.md`。
- **证据**：`Get-ChildItem docs` 显示两文件同大小；git 历史里有一条「删除文件 NotifyX架构设计书.md」。
- **影响**：双份文档必然漂移，评审时引用哪一份会产生歧义。
- **计划**：保留 `design.md` 作为唯一基线，另一个改为指向它的短文件（或在 README 说明关系）。

## ISSUE-009 POM 元数据与真实托管地址不一致
- **状态**：CLOSED（iter-02）
- **现象**：`pom.xml` 的 `url` / `scm` / `issueManagement` 全部指向 `github.com/pkyit/NotifyX`，
  而实际 remote 是 `git@gitee.com:pengkaiyan/NotifyX.git`。
- **影响**：发布到 Central 后，用户点「源码」会 404；SCM 校验（Central 要求）可能失败。
- **关闭方式**：url / scm / developer.url / issueManagement 统一改为 Gitee 实际地址。

## ISSUE-010 渠道契约测试缺失（§18.3）
- **状态**：OPEN（P0 后续）
- **现象**：email / webhook 没有任何 WireMock 契约测试；webhook 的 HMAC 签名只有实现没有断言。
- **影响**：§21.2 第 8 步验收「WireMock 契约测试」未达成，IM 平台改版风险（R7）无护栏。
- **计划**：随 iter-02/03 之后补 `notifyx-channel-webhook` 契约测试（JDK HttpServer 或 WireMock，避免新增重依赖）。

## ISSUE-011 中文日志/注释在 Windows GBK 控制台下显示乱码（非代码缺陷）
- **状态**：CLOSED（记录性）
- **现象**：`pwsh Get-Content` 读取 UTF-8 中文 pom 时显示 `鍏滃簳...`；`java -version` 输出也被当作错误流。
- **证据**：`read` 工具读取同一文件正常。属于 PowerShell 5.1 默认 ANSI 解码问题。
- **处理**：脚本里显式 `-Encoding UTF8`；写文件统一 UTF-8 无 BOM。

## ISSUE-012 Appender 内部 logger 前缀硬编码，无法配置
- **状态**：OPEN（P1 诊断表时评估，见 D-008）
- **现象**：`NotifyxAppender` 过滤 `io.github.pkyit.notifyx` 与 `NOTIFYX_` 开头的 logger。
  若业务方（或示例工程）把代码放在 `io.github.pkyit.notifyx.*` 下，这些日志会被一起静默丢弃。
- **证据**：iter-01 E2E 中 `/demo/log-error` 无投递；sample 包名为 `io.github.pkyit.notifyx.sample`。
  改用 `com.example.legacy.OrderService` 后立即恢复。
- **影响**：罕见但难排查；示例工程已规避。
- **计划**：P1 增加 `notifyx.logback.exclude-logger-prefixes`（或 Appender 属性），并校验不能移除默认前缀。
