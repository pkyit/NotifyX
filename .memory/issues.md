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
- **证据**：各模块 `target/site/jacoco/jacoco.csv`。
- **影响**：core 是指纹/限流/模板/熔断等隐蔽 bug 高发区，覆盖率不足会以「去重静默失效」形式暴露。
- **进展**：
  - iter-03：core 52.9% → 67.7%（+7 个测试类）；门禁 65%；
  - iter-06：webhook 84.7%（门禁 80%）；
  - iter-10：core 约 72%（文件存储测试）；
  - iter-11：email 79%（门禁 75%）；
  - iter-12：spring 21.6% → 61.8%（切面/SpEL 测试），门禁 20% → 60%；
  - iter-13：spring 92.2%（优先级执行器测试，门禁 90%）、autoconfigure 62.1%（指标测试，门禁 60%）；
  - iter-14：core 78%（管线重试/熔断 + 事件边界测试，门禁 65% → 76%）。
- **仍缺**：core 目标 90%（剩余主要在 defer/failureStore/onSuccess 回调、NotifyContext builder）；
  autoconfigure 的分母被大量配置 POJO getter/setter 占据，已达 62.1%。

## ISSUE-004 Spring Event 桥接（L3）与 MDC 未实现（§8.4 / §15.3）
- **状态**：CLOSED（iter-04）
- **现象**：`notifyx-spring` 只有 `NotifyxAspect` / `SpelEvaluator` / 两个注解 / 优先级执行器，
  没有 `@EventListener` 桥接，也没有 MDC 透传（traceId / scene）。
- **证据**：`notifyx-spring/src/main/java/.../spring/` 下 5 个类，无 Event 相关类。
- **影响**：§21.2 第 6 步验收缺一半；L3「彻底解耦」用法不可用。
- **关闭方式**：新增 `NotifyxEventBridge` + `@NotifyxScene` + `NotifyxEventPayload`；
  core 新增 `TraceContext`/`MdcTraceContext`，门面在调用线程抓 trace 写事件标签，
  管线渲染/审计/`NotifyContext` 全链路带上 traceId。测试：spring 6 个 + core 4 个。
  E2E：`/demo/event` 发布事件 → 通知正常投递。

## ISSUE-014 模板 `${...}` 被 Spring 占位符解析篡改（iter-04 发现并修复）
- **状态**：CLOSED（iter-04）
- **现象**：L3 示例发布 `userName=bob`，通知标题却是 `【账号开通】admin`（Windows 的 `USERNAME=admin`）；
  `params.__source` 里仍是 `userName=bob`，说明是渲染前参数被替换。
- **证据**：E2E 日志对比；`@ConfigurationProperties` 绑定默认用 `PropertySourcesPlaceholdersResolver`，
  `SystemEnvironmentPropertySource` 会把 `userName` 关联到环境变量 `USERNAME`。
- **影响**：任何模板变量名碰巧等于环境变量（`userName`/`path`/`host`/`home`…）都会被静默改写，
  属于「看起来正常、内容悄悄错」的高危问题。
- **关闭方式**：模板配置改用 identity `PlaceholdersResolver` 的 `Binder` 重新绑定（D-011），
  并在 `ZeroDependencyStartupTest` 用系统属性 `userName=admin` 固定该行为。

## ISSUE-005 优雅关闭只有执行器 destroy，缺少端到端验证（§9.10）
- **状态**：CLOSED（iter-05）
- **现象**：`PriorityNotifyExecutor.destroy()` 已实现 `shutdown → awaitTermination → shutdownNow`，
  但没有测试证明「Spring 上下文关闭时，队列中的通知要么发完要么被计数丢弃」。
- **证据**：`PriorityNotifyExecutor.java:128-140`；无 shutdown 相关测试。
- **影响**：发布重启时可能静默丢通知，且日志里没有丢弃计数。
- **关闭方式**：新增 `NotifyxLifecycle`（SmartLifecycle）在容器关闭早期排空队列并关闭渠道；
  `NotifyxLifecycleTest` 覆盖排空、幂等、普通执行器降级、phase。未排空事件目前只 warn，
  落盘补偿等 D-003 的文件实现。

## ISSUE-006 actuator Health / 启动诊断表未实现（§15.2 / §14.4）
- **状态**：CLOSED（iter-05）
- **现象**：`HealthStatus` 模型存在于 core，但没有任何 `HealthIndicator` 实现；
  `NotifyxProperties.Diagnostics` 只有 `banner` / `fail-fast` 两个开关，没有「能力表」输出。
- **证据**：grep `HealthIndicator` 无结果；`NotifyxProperties.Diagnostics` 字段仅 2 个。
- **影响**：运维无法一眼看出「当前哪些能力降级为内存实现」——这正是 §14.4 诊断表的目的。
- **关闭方式**：`NotifyxHealthIndicator`（UP/DEGRADED/DOWN/OUT_OF_SERVICE，默认不探活）
  + `NotifyxDiagnostics` 启动能力表；`NotifyxActuatorAutoConfiguration` 在无 actuator 时零影响。
  E2E：`/actuator/health` 的 notifyx 组件返回 DEGRADED + 内存降级原因。

## ISSUE-007 聚合（§9.5）与升级（§9.6）未实现
- **状态**：CLOSED（T0 版本完成；T1/T2 属 P2 路线图）
- **现象**：去重只做「窗口内静默」，没有把被去重的事件聚合成一条摘要；没有「同一场景重复 N 次
  自动升级 Level/渠道」的策略。
- **影响**：通知风暴时用户只收到第一条，无法知道「一共发生了 300 次」。
- **iter-08**：`Aggregator`（T0 实例内窗口聚合，默认关闭）+ 管线去重 REJECT 钩子 +
  生命周期关闭前汇总；摘要带 `aggregateCount` 等参数与唯一 dedupeKey。
- **iter-09**：`EscalationPolicy`（连续 N 次/窗口/upgrade-to 渠道/恢复场景/reset-on-success），
  管线按场景计数并在升级态改走更高触达渠道；诊断表展示规则数。
- **仍属 P2**：T1 Redis / T2 MQ 的跨实例聚合与计数（随 store/MQ 模块落地）。

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
- **状态**：CLOSED（iter-11）
- **现象**：email / webhook 没有任何契约测试；webhook 的 HMAC 签名只有实现没有断言。
- **影响**：§21.2 第 8 步验收「WireMock 契约测试」未达成，IM 平台改版风险（R7）无护栏。
- **iter-06**：`WebhookChannelTest` 12 个用例（JDK HttpServer，零新增依赖），
  覆盖签名独立复算、报文、状态码分类、expect-status、白名单、超时、截断；webhook 覆盖率 84.7%。
- **iter-11**：`EmailChannelTest` 6 个用例（Mockito 静态 mock `Transport.send`，不发真实流量），
  覆盖报文结构、Message-ID 幂等头、主题回落、535/550/authentication/超时四类错误映射；email 覆盖率 79%。
- **关闭说明**：WireMock 被 JDK HttpServer + Mockito 替代，满足「契约固定、无新增运行依赖」的目标。

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

## ISSUE-015 失败记录自动补偿未实现（runner 待做）
- **状态**：CLOSED（iter-16）
- **现象**：失败记录可落盘/读回/改状态，但「自动补偿任务」尚不可用。
- **iter-15**：`NotifyEnvelope`（§6.5）+ `NotifyEnvelopeCodec` + 零依赖 `JsonCodec` 已完成；
  `persistFailure` 写入可还原的完整报文，并有落盘解码测试。
- **iter-16 关闭方式**：`FailureCompensationRunner` 实现 §9.9 状态机：
  定时 `loadPending` → CAS `SENDING` → `deliverSync` → `SENT`；
  失败按 `retry-backoff` 重排、达到 `max-attempts` 转 `DEAD`；解码失败直接 `DEAD`。
  默认关闭（`notifyx.fallback.compensation.enabled=false`），开启后每实例扫描 + 乐观锁抢占。
- **已知限制**：自定义 `NotifyKit`（非 `DefaultNotifyKit`）没有同步结果，按「提交成功」处理（D-018）；
  单实例扫描的 IO 放大在多实例下存在，ShedLock/MQ 单消费者属 P2/P3。
- **关联**：D-003（部分落地）、D-017、D-018。

## ISSUE-016 EmailChannel 未向 SMTP 提交凭据（真实环境暴露）
- **状态**：CLOSED（iter-18）
- **现象**：配置了用户名/密码，真实 163 SMTP 发送仍报 `ChannelAuthException: SMTP authentication failed`；
  同一账号用 Python `smtplib` 登录返回 `235 Authentication successful`。
- **根因**：只设置了 `mail.smtp.auth=true`，`Session` 没有 `Authenticator`；
  `Transport.send(message)` 不接收 user/password，JavaMail 不会提交任何凭据。
- **修复**：`Session.getInstance(props, authenticator(properties))`，用户名非空时提供
  `PasswordAuthentication`；匿名中继保持不带凭据。
- **证据**：修复后直接渠道两封 `SUCCESS`；sample 完整链路两条审计 `status=SUCCESS`。
- **教训**：静态 mock `Transport.send` 的契约测试覆盖不到 SMTP 协议交互；认证类配置必须有
  「Session/Authenticator」层面的断言，或定期做真实/伪 SMTP 冒烟。
- **关联**：iter-18、journal/2026-10-22。

## ISSUE-017 审计记录的 level 不是生效级别
- **状态**：CLOSED（iter-20）
- **现象**：真实邮件验证时，场景配置 `level: ERROR`，审计输出 `level=WARN`。
- **根因**：管线已按「事件与场景取高」算出生效级别用于渲染/优先级，但 `audit()` 写的是
  `event.level()`（事件原始级别）。
- **修复**：`dispatch → sendOne → audit` 传递生效级别；`reject`/`defer` 用原始级别（未进入渲染）。
- **证据**：新增 `EffectiveLevelAuditTest` 2 个用例（场景更高 / 事件更高），修复前会失败。
- **关联**：iter-20、journal/2026-10-22。

## ISSUE-018 仓库缺少架构级代码审计与代理工作说明
- **状态**：CLOSED（iter-18）
- **现象**：仓库没有面向 AI / 新接手者的工作规则，也没有一份以架构师视角系统梳理当前 SDK 风险的审计报告。
- **证据**：新增 `AGENTS.md` 与 `docs/architecture-review-2026-10-22.md`。
- **影响**：接手者难以快速理解模块边界、构建命令、质量门禁和当前发布风险。
- **关闭方式**：新增 `AGENTS.md` 作为仓库工作说明；新增架构审计报告，梳理 P0/P1/P2 风险与修复路线。

## ISSUE-019 工作区缺少统一编辑器配置与本地忽略规则
- **状态**：CLOSED（iter-18）
- **现象**：仓库缺少 `.editorconfig`，且 `.gitignore` 未覆盖 `*.log.gz` 与 `/.workbuddy/`。
- **证据**：新增 `.editorconfig`；`.gitignore` 新增 `*.log.gz` 与 `/.workbuddy/`。
- **影响**：跨编辑器缩进 / 换行 / 编码不一致，本地工具产物可能误入版本库。
- **关闭方式**：新增 `.editorconfig` 并补充 `.gitignore` 忽略规则。

## ISSUE-020 渠道抛非 ChannelException / 返回 null 时绕过审计、熔断与落盘
- **状态**：CLOSED（iter-32）
- **现象**：`NotifyPipeline.sendOne()` 只捕获 `ChannelException`；渠道抛 `IllegalStateException`、`NullPointerException`
  或返回 `null` 时，异常落到 `handle()` 外层 catch，导致本次发送没有审计、没有失败落盘、熔断器不记失败；
  半开状态的 `probeInFlight` 还可能永久为 true。
- **证据**：`NotifyPipelineFailureContainmentTest` 三个用例；修复前“持续抛非 ChannelException”会返回 true
  （被当成成功），且 `FailureStore` 为空。
- **关闭方式**：`sendOne` 捕获 `RuntimeException` 并按可重试失败处理，`null` 结果同样转失败；中断时退出重试循环；
  新增 `channel produced no result` 防御性兜底。

## ISSUE-021 用户 EventFilter Bean 未接入责任链
- **状态**：CLOSED（iter-32）
- **现象**：README 承诺“定义成 Bean 即被发现”，但 `notifyxNotifyKit()` 只装配内置 Filter，
  用户自定义 `EventFilter` 不会执行。
- **证据**：`CustomFilterWiringTest`；修复前 `filters=4`，自定义 Filter 的调用列表为空。
- **关闭方式**：Bean 方法注入 `ObjectProvider<EventFilter>`，合并后按 `EventFilter.order()` 升序排序。

## ISSUE-022 保留期删除失败时无限重试
- **状态**：CLOSED（iter-32）
- **现象**：`JdbcAuditRetention.purgeBefore()` 在 `deleteBatch()` 返回 0（权限 / 锁 / 只读库）时，
  只要 select 仍返回 `batchSize` 条记录就会一直循环，永不自退。
- **证据**：`JdbcAuditRetentionTest.shouldStopWhenDeleteFails`（H2 触发器拒绝删除）；
  修复前该用例会在 2s 超时内持续 select 同一批 id。
- **关闭方式**：单批删除 0 行时记录 warn 并结束本轮，返回已删除总数。

## ISSUE-023 `maskParams` 未递归处理嵌套结构
- **状态**：CLOSED（iter-32）
- **现象**：`DefaultMasker.maskParams()` 只处理顶层字符串；嵌套 `Map` / `List` 原样返回，
  与接口“按 key 名递归匹配”的契约不符。
- **证据**：`DefaultMaskerTest.shouldMaskNestedParams`；修复前嵌套 `phone/email` 不会被脱敏。
- **关闭方式**：递归 Map / Iterable，限制深度 10，返回新结构且不修改入参。

## ISSUE-024 `currentCount` 返回窗口外的陈旧计数
- **状态**：CLOSED（iter-32）
- **现象**：`MemoryRateLimiter.currentCount()` 直接返回 deque 大小，不按 `window` 清理过期时间戳；
  指标/诊断会看到已经过期的计数。
- **证据**：`MemoryRateLimiterTest.currentCountShouldPruneExpired`；修复前窗口滑过后仍返回 1。
- **关闭方式**：`currentCount` 在 `computeIfPresent` 中清理过期元素，空 deque 移除 key。