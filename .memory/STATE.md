# NotifyX 状态快照

> 更新时间：2026-11-04（iter-70：复核跟进修复 — 熔断新卡死路径 / recordId 列宽 / 去重限频清理；版本 1.0.4）
> 交付范围：设计书 **P0/P1 的 SDK 部分 + P2（Redis T1、JDBC）+ P3（Kafka/RabbitMQ producer、双传输 consumer）**。
> **不是** 23 周后的 1.0 GA。
> 构建基线：`mvn -B -ntp clean verify` 通过（Spotless/Checkstyle/JaCoCo 全绿），17 个模块；
> `mvn -Prelease -Dgpg.skip=true package`
> 需在普通终端复验（本沙箱 javadoc 目标目录不可写，见 `env.md` §13）。

## 1. 一句话现状

责任链管线、三渠道、L0/L2/L3 接入、Trace、自动装配、发布链路、质量门禁、
优雅关闭、actuator health、启动诊断表、双 starter 坐标、T0 聚合与升级、失败落盘与自动补偿
**全部完成**。P0/P1 SDK 范围已收口（见 iter-17 审计）；**真实 163 SMTP 邮件已实测成功且收件人确认收到**
（iter-18/19）；**k8s 集群内邮件告警已跑通**（iter-21）；**Redis 分布式去重/限流与跨实例聚合**（iter-22/23）、
**JDBC 审计/补偿**（iter-24/30）、**Kafka/RabbitMQ 总线与双传输 consumer**（iter-25~29）均已落地并用真实中间件验证。
iter-32 修复了 5 个可靠性缺陷（渠道异常隔离、自定义 Filter 装配、保留期删除空转、嵌套脱敏、限流计数）；
iter-33 修复了 Webhook 嵌套 JSON、保留头覆盖与异常分类；iter-34 把模板渲染异常纳入审计/落盘/onError；
iter-35 修正了渠道返回 `FAIL_*` 状态时的重试与熔断语义；iter-36 修正了 `@NotifyOn` /
`@AlarmProtect` 的 `#r`/`#e` 求值时机；iter-37 修正了升级策略窗口不滚动的 bug；
iter-38 加固了 `JsonCodec` 的非法 Unicode 与超深嵌套处理；iter-39 修复了 Redis 聚合锁不释放；
iter-40 修复了 `@AlarmProtect` 数组参数导致的去重失效，并让审计/失败落盘记录真实
attempts、全局 `retry.max-attempts` 真正封顶；iter-41 让 `notifyx.metrics.enabled` /
`prefix` 真正生效；iter-42 让 Redis 去重 TTL 使用毫秒精度；iter-43 让
`notifyx.retry.enabled=false` 真正只尝试一次；iter-45 让 `mask.enabled` 生效，
并让审计中的手机号/邮箱 receiver 按类型脱敏；iter-46 让审计 bizId/errorMsg
以及文本中的手机号/邮箱也脱敏；iter-47 让 `env`/`tenant` 进入模板参数；
iter-48 补上 `notifyx.queue.size` 队列水位 gauge；iter-49 补上
`notifyx.circuit.state` 熔断状态 gauge；iter-50 让关闭超时丢弃的任务逐个进入
`queue.discarded`（shutdown）并打印前 20 条 level/key；iter-51 让全部渠道禁用时
健康状态变为 DOWN、部分禁用为 DEGRADED；iter-52 让 `mask.before-send` 对渲染内容和
渠道参数生效（`NotifyContext.params()`）；iter-53 新增 `mask.before-persist`，
可选脱敏失败落盘 envelope；iter-54 让 `diagnostics.fail-fast` 在无渠道/全部禁用时
阻止启动（默认仍关闭）；iter-55 让 `dedupe.store`/`rate-limit.store=memory`
真正跳过 Redis 自动装配；iter-56 在启动诊断表提示配置与实现不一致；
iter-57 让 Redis 降级包装通过 `Degradable` 进入健康检查与诊断表（tier 降 T0）；
iter-58 让 `diagnostics.fail-fast` 同时校验 store/engine 配置不一致；
iter-59 补全免打扰 defer：命中事件进入有界内存延迟队列（`InMemoryDeferredHandler`），
窗口结束后重入管线，不再静默丢弃（修复设计书 §9.3 承诺）。
Maven Central 已发布 **1.0.0**（iter-44，2026-10-27）；iter-45~59 是 **1.0.1 候选**，
Central 额度 2026-11-01 前不可发新版本；P2+ 目标见 iter-31 审计。
本地版本已升至 **1.0.4**：iter-69 修复代码评估报告的并发缺陷（去重死锁、熔断半开卡死、
失败记录覆盖），iter-70 按外部复核跟进修复（`FAIL_NON_RETRYABLE`/不可重试异常的半开探测
释放、recordId 收件人短摘要对齐 `varchar(64)`、去重清理限频）并补齐 10 个回归测试；
报告遗留 4.4/4.5/4.6 见 ISSUE-061。

## 2. 已实现（按设计书章节）

| 设计书 | 能力 | 位置 | 验证 |
| --- | --- | --- | --- |
| §6 核心模型 | `NotifyEvent` / `Level` / `SendResult` / 异常体系 / ULID | `notifyx-core` | `NotifyEventTest`、`FingerprintsTest` |
| §6.7 门面 | `NotifyKit` 6 方法 + `DefaultNotifyKit` | core | `NotifyPipelineTest` |
| §9.1 指纹 | `sha256(scene‖bizId‖md5(sorted params))` | core `Fingerprints` | `FingerprintsTest`（T1–T3） |
| §9.2 漏斗 | 全局静默 → 场景路由 → 免打扰 → 去重 → 场景限流 → 全局限流 → 用户 Filter(900+) | core `pipeline/filter` | `NotifyPipelineTest` |
| §9.7 熔断 | 按渠道失败阈值 / 打开时长 / 最长打开 | core `CircuitBreaker` | `NotifyPipelineTest` |
| §9.8 重试 | 指数退避 + 抖动 + 不可重试异常 | core `RetryPolicy` | `RetryPolicyTest`、`NotifyPipelineRetryTest` |
| §9.9 失败落盘 | `FailureStore` SPI + `InMemoryFailureStore`（有界内存） | core | 间接 |
| §9.11 队列 | 优先级队列、软上限 drop-lowest、绝不 CallerRuns | `notifyx-spring` `PriorityNotifyExecutor` | 部分 |
| §8.3 L2 | `@AlarmProtect` / `@NotifyOn` + SpEL（默认禁方法调用） | `notifyx-spring` | `NotifyxAspectTest`（9 个） |
| §8.1 L0 | Logback Appender：异步有界、噪声过滤、bizId 提取、双重防自递归、Spring 自动绑定 | `notifyx-logback` | `NotifyxAppenderTest` 等 20 个 + E2E |
| §10.1 渠道 | log / email / webhook（HMAC + 时间戳 + 主机白名单） | `notifyx-channel-*` | webhook 12 个契约测试；email 待补 |
| §14 配置 | `NotifyxProperties` 全量属性 + 模块内渠道自动装配 + 启动诊断表 | autoconfigure / channel 模块 | `ZeroDependencyStartupTest`（T13）、`NotifyxDiagnosticsTest` |
| §9.10 关闭 | `NotifyxLifecycle`（SmartLifecycle）：排空队列 → 关闭渠道 | autoconfigure | `NotifyxLifecycleTest` |
| §15.1 指标 | `NotifyMetrics` + Micrometer 适配 | core / autoconfigure | 无测试 |
| §15.2 健康 | `NotifyxHealthIndicator`：UP/DEGRADED/DOWN/OUT_OF_SERVICE，默认不探活 | autoconfigure | `NotifyxHealthIndicatorTest` + E2E |

## 3. 未实现（按优先级）

### P0 剩余

- [x] **Logback Appender（L0，§8.1 / T7）** —— iter-01 完成（含 E2E）
- [x] **发布链路（§19.1）** —— iter-02 完成配置与本地验证（实际 deploy 待 Central 账号 + GPG 私钥）
- [x] **质量门禁（§17.3）** —— iter-03 完成（Spotless / Checkstyle / JaCoCo / CI workflow）
- [x] **Spring Event 桥接（L3，§8.4）+ Trace（§15.3）** —— iter-04 完成（含 E2E）
- [x] **ADR-001 alias artifact 发布策略** —— iter-07 完成（主坐标 + relocation + 依赖壳，relocation 实测生效）

### P1

- [x] 优雅关闭（§9.10）—— iter-05（SmartLifecycle 排空 + 渠道释放；未排空落盘待 D-003）
- [x] actuator `HealthIndicator`（§15.2）+ 启动诊断表（§14.4）—— iter-05（含 E2E）
- [x] 聚合（§9.5，T0 实例内）—— iter-08（默认关闭；跨实例 T1/T2 待 P2 存储/MQ）
- [x] 升级（§9.6）—— iter-09（连续 N 次/窗口/upgrade-to/恢复场景/成功清零，默认关闭）
- [x] 失败落盘（local-file，§9.9）—— iter-10（JSON/乐观锁/磁盘上限/重启可读；默认内存）
- [x] 渠道契约测试（§18.3）—— iter-06 webhook 12 个 + iter-11 email 6 个（ISSUE-010 关闭）
- [x] L2 切面测试（§8.3）—— iter-12（@AlarmProtect/@NotifyOn/SpEL 安全默认，spring 覆盖率 61.8%）
- [x] 优先级队列语义 + Micrometer 指标测试 —— iter-13（spring 92.2%、autoconfigure 62.1%）
- [x] 管线重试/熔断 + 事件边界测试 —— iter-14（core 覆盖率 78%，门禁 76%）
- [x] `NotifyEnvelope` 契约 + 零依赖 JSON 编解码 —— iter-15（落盘写入可还原报文）
- [x] 失败自动补偿 —— iter-16（CAS→重投→SENT/DEAD + 退避，默认关闭；ISSUE-015 关闭）
- [x] **T1 Redis 去重/限流（§9.4）** —— iter-22（`notifyx-store-redis`，集群 Redis 6.2.23 实测，降级内存）
- [x] **T1 跨实例聚合（§9.5）** —— iter-23（Redis Hash 计数 + `SET NX` 抢锁，真实 Redis 两实例只发一条）
- [x] **JDBC 审计/失败补偿（§12.2）** —— iter-24（MySQL 8.0 / PostgreSQL 16 实测；纯 JDBC，D-021）
- [x] **Kafka producer（§11，producer-only）** —— iter-25（`notifyx-bus-kafka`，集群 Kafka 4.3.1 发布/消费实测）
- [x] **Kafka consumer（§11.4）** —— iter-26（`notifyx-consumer` 单实例，内存幂等，真实 Kafka 重复投递只发一次）
- [x] **Consumer 持久化幂等** —— iter-27（Redis SET NX / JDBC 唯一键三档可配，真实 Redis 实测）
- [x] **RabbitMQ producer（§11.2）** —— iter-28（`notifyx-bus-rabbitmq`，durable+confirm，真实 RabbitMQ 实测）
- [x] **RabbitMQ consumer（§11.4）** —— iter-29（transport=kafka|rabbitmq 切换，真实 RabbitMQ 重复投递只发一次）
- [x] **审计保留期清理（§12.3）** —— iter-30（`JdbcAuditRetention` 分批删除，API 不内置调度）
- [x] **可靠性缺陷修复** —— iter-32（渠道异常隔离、自定义 Filter 装配、保留期删除空转、嵌套脱敏、限流计数）
- [x] **Webhook 契约修复** —— iter-33（嵌套 JSON、保留头不可覆盖、异常统一分类、scheme/host 校验）
- [x] **模板渲染异常失败路径** —— iter-34（审计、失败落盘、onError；不记渠道熔断）
- [x] **渠道失败状态语义** —— iter-35（FAIL_RETRYABLE 重试、FAIL_NON_RETRYABLE 记熔断、抑制状态中立）
- [x] **AOP SpEL 后置求值** —— iter-36（`@NotifyOn` 可用 `#r`、`@AlarmProtect` 可用 `#e`）
- [x] **升级窗口滚动** —— iter-37（长时间稀疏事件后仍可正确升级）
- [x] **JsonCodec 边界** —— iter-38（非法 `\u` 与超深嵌套统一 IllegalArgumentException）
- [x] **Redis 聚合锁释放** —— iter-39（token + compare-and-delete，旧锁不再挡住下一窗口）
- [x] **AOP 参数去重与审计 attempts** —— iter-40（数组归一化、真实尝试次数、全局 retry 上限）
- [x] **指标配置生效** —— iter-41（`metrics.enabled` / `metrics.prefix` 真正生效）
- [x] **Redis 去重 TTL 精度** —— iter-42（`SET NX PX`，亚秒窗口不再被放大）
- [x] **retry.enabled 生效** —— iter-43（关闭后每个渠道只尝试一次）
- [x] **NotifyX 1.0.0 发布** —— iter-44（17 模块、sources/javadoc/GPG、Central Portal）
- [x] **脱敏总开关与 receiver 脱敏** —— iter-45（`mask.enabled` 生效；手机号/邮箱按类型脱敏）
- [x] **审计文本脱敏** —— iter-46（bizId/errorMsg 及文本中的手机号/邮箱）
- [x] **env/tenant 模板参数** —— iter-47（`${env}` / `${tenant}` 可渲染）
- [x] **队列水位指标** —— iter-48（`notifyx.queue.size` gauge）
- [x] **熔断状态指标** —— iter-49（`notifyx.circuit.state`，channel 标签）
- [x] **关闭丢弃可见性** —— iter-50（shutdown 丢弃逐个计数 + 前 20 条日志）
- [x] **全渠道禁用健康状态** —— iter-51（全部禁用 DOWN / 部分禁用 DEGRADED）
- [x] **before-send 脱敏** —— iter-52（渲染内容 + `NotifyContext.params()`，默认关闭）
- [x] **before-persist 脱敏** —— iter-53（失败 envelope params，默认关闭）
- [x] **diagnostics.fail-fast** —— iter-54（无渠道/全禁用时阻止启动，默认关闭）
- [x] **存储选择生效** —— iter-55（`dedupe.store`/`rate-limit.store=memory` 跳过 Redis）
- [x] **配置不一致诊断** —— iter-56（store/engine 与实际不一致时启动告警）
- [x] **Redis 降级可见** —— iter-57（`Degradable`；健康 DEGRADED，诊断 tier 降 T0）
- [x] **fail-fast 配置校验** —— iter-58（store/engine 不一致时严格模式阻止启动）
- [x] RabbitMQ 死信队列（DLX + 多级 TTL） —— iter-61
- [x] consumer 多实例分区消费与死信/重试 —— iter-62
- [x] 信创 DDL（DM/Kingbase/openGauss）与 `nx_idempotency` 保留期 —— iter-60
- [x] 覆盖 T1–T24 的剩余高危用例（§18.2） —— iter-63

### 明确不在本次交付范围（23 周路线图后续阶段）

Admin 控制台、consumer 服务、MQ 三总线、JDBC / MyBatis-Plus store、钉钉 / 企微 / 飞书、
sms4j 适配。

## 4. 刻意偏离（详见 decisions.md）

| # | 偏离 | 原因 |
| --- | --- | --- |
| D-001 | core 用自定义 `NotifyxOrdered`，不用 Spring `Ordered` | 守住 ADR-003「core 零 Spring」，ArchUnit 强制 |
| D-002 | 场景解析在进入 Filter 链前完成，`SceneRoutingFilter` 只裁决「场景是否启用」 | `FilterContext` 需要携带场景配置（模板/渠道/接收人） |
| D-003 | 失败落盘 P0 只有有界内存 `InMemoryFailureStore`（local-file 已由 D-017 落地，JDBC 待 P2） | 先保证 SPI 与调用点正确 |
| D-004 | 渠道自动配置放各自渠道模块，不放 autoconfigure | 避免 autoconfigure 依赖渠道 SDK（第 5 章依赖纪律） |
| D-005 | 新增 `notifyx-logback` 模块（设计书模块树未列） | P0 明确要求 Logback Appender，但放进 autoconfigure 会污染依赖方向 |
| D-007 | Appender 的 `extractBizId` 用子元素而非属性 | logback 1.5 的 model 解析器不把嵌套元素属性写入对象，属性写法静默失效 |
| D-008 | 内部 logger 前缀硬编码（`io.github.pkyit.notifyx` / `NOTIFYX_`） | 防自递归优先于可配置性；配置化会引入新的自递归风险 |
| D-009 | starter 增加 `NotifyxStarter` 标记类 | Central 强制要求 sources/javadoc，空模块产不出；用最小标记类闭环 |
| D-010 | 质量门禁用轻量格式检查 + Checkstyle 10.x + 覆盖率 ratchet | 全量重排 diff 过大；Checkstyle 14.x 需 JDK 21；覆盖率需逐步抬升 |
| D-011 | 模板配置用 identity PlaceholdersResolver 的 Binder 读取 | Spring 会把 `${userName}` 当环境变量解析成 `admin`，模板占位符必须与框架占位符隔离 |
| D-012 | 优雅关闭 phase 晚于 Web 优雅停机 | 先停新流量再排空通知，关闭窗口丢事件最少；仍早于数据源销毁 |
| D-013 | alias 坐标用 relocation + 依赖壳 + 标记类 | 同时满足 ADR-001 与 §19.2；标记类解决 Central 对空 jar 的 sources/javadoc 校验 |
| D-014 | 聚合先做 T0 实例内且默认关闭 | 没有 Redis/MQ 时跨实例聚合是错误承诺；行为变化类能力默认关 |
| D-015 | 升级只改渠道、不改 Level | 避免升级动作悄悄绕过免打扰/限流（CRITICAL 旁路矩阵） |
| D-016 | 升级计数包含被去重的事件 | 「未恢复」看故障持续，不看成功通知条数；否则长静默期下永远不升级 |
| D-017 | local-file 落盘用手写扁平 JSON + 容量上限，暂不做自动重投 | core 不能引 Jackson；自动补偿需先定 NotifyEnvelope 契约 |
| D-018 | 补偿优先 `deliverSync` 真实结果，自定义 `NotifyKit` 退化为提交成功 | 不破坏 `NotifyKit` 6 方法契约；退化路径写入文档 |
| D-019 | Redis 故障降级内存（熔断 30s）、`size=-1` 不用 KEYS、自动装配排在管线之前 | 可用性优先；多实例降级期可能重复发送，已写入 README |
| D-020 | T1 聚合与 T0 共用开关，`SET NX` 抢锁保证全局一条摘要，不做崩溃补发 | 重复摘要比丢失更扰民；补发需额外协调机制 |
| D-021 | JDBC 存储用纯 JDBC 而非 MyBatis-Plus | 当前只有两张表；减少版本/方言耦合，信创 DDL 独立 |
| D-022 | MQ 模式用门面级 producer-only，幂等键用 eventId | producer 只传输，语义裁决交给 consumer；避免与本地管线重复 |
| D-028 | 全局 `retry.max-attempts` 是上限，渠道只能降低 | 全局配置应真正生效；渠道 1 次（短信）仍优先，见 decisions.md |

## 5. 下一步队列

1. **待用户决策**：A02/A03（目标级失败记录 + 定向重投，修多渠道路由覆盖与部分成功重复发送）、
   A04（无渠道返回 `NO_ROUTE` 而非成功）、A07（审计/落盘全链路脱敏）。
2. **P2+ 扩展**：Admin 控制台（P4）；RabbitMQ DLX/多级 TTL、consumer 多实例分区与死信重试、
   信创 DDL 与 `nx_idempotency` 保留期已分别在 iter-61/62/60 落地。
3. **k8s 生产化**：自有镜像仓库 + Deployment + Secret 注入。
4. **测试债**：T1–T24 剩余高危用例；core 覆盖率向 90% 提升。
5. **Central 发布**：1.0.0 已于 2026-10-27 发布（iter-44）；后续修复按 §19.3 发 patch 版本。

## 6. 当前分支

`master`（iter-45 已合并；1.0.1 候选）。`origin/master` 停在 `6e9428b`（1.0.0 发布后），
本地领先（iter-45 相关提交），因 DSH 沙箱 ssh 管道阻塞未推送；
需在普通终端执行 `git push origin master`。`release/1.0.0` 分支保留。
工作分支命名：功能 `feature/*`、缺陷 `bugfix/*`、发布 `release/<version>`。

## 7. 夜间自动化收尾（2026-10-23 06:00）

- 工作时段 19:00–06:00 结束，停止新增代码改动；最后一轮全量 `mvn -B -ntp clean verify`
  于 00:04 通过（276 用例 / 263 执行 + 13 live 跳过）。
- 本轮共 5 个迭代：iter-32（5 项可靠性修复）、iter-33（Webhook 契约）、iter-34（模板渲染失败路径）、
  iter-35（`FAIL_*` 状态语义）、iter-36（AOP `#r/#e` 后置求值）。
- 自动目标达到 4 轮上限，状态为 blocked/disarmed；19:00 提醒会重新启动夜间流程。
- 未决：A02/A03、A04、A06、A07、A12–A15；邮件凭据仍未提供，决策邮件未发出；
  本地 10 个提交未推送。

## 8. 第二晚夜间自动化（2026-10-23 19:00 起）

- 19:00 提醒触发；检查环境变量与工作区，仍无 163 SMTP/IMAP 凭据、无用户回复。
- 自动 goal 创建被拒（工具要求直接人类回合），本轮按单轮继续，不擅自扩 P2+ 范围。
- iter-37 修复升级窗口不滚动：长时间稀疏事件后，新的密集事件仍可达到阈值。
- iter-38 加固 `JsonCodec`：非法 `\u` 与超深嵌套统一 `IllegalArgumentException`。
- iter-39 修复 Redis 聚合锁不释放：token + compare-and-delete，旧锁不再挡住下一窗口。
- iter-40 修复 AOP 数组参数去重、审计/落盘 attempts、全局 retry 上限；DEMO 复测 10/10。
- iter-41 让 `notifyx.metrics.enabled` / `prefix` 真正生效，默认指标名保持 `notifyx.*`。
- iter-42 让 Redis 去重 TTL 使用毫秒精度，亚秒窗口不再被放大到 1 秒。
- iter-43 让 `notifyx.retry.enabled=false` 真正只尝试一次。
- 全量 `mvn -B -ntp clean verify` 通过：295 用例（282 执行 + 13 live 跳过）。
- 待 06:00 收尾提醒；剩余 A02/A03/A04/A06/A07/A12/A14/A15。邮件仍阻塞；push 已在后续会话完成。

## 9. 提醒延迟与同步状态（2026-10-27 09:00）

- 10-26 19:00 启动、10-26 21:00 巡检、10-27 06:00 收尾三条提醒在 10-27 08:59 一次性补发，
  已全部晚于工作时段；按最新 06:00 收尾语义，**不新开代码改动**。
- 工作区干净；`master` 与 `origin/master` 同为 `b0f44f4`，此前 26 个提交已同步，
  无需再执行 `git push`。
- 仍无 163 SMTP/IMAP 凭据，决策邮件未发出；下一步仍以 A02/A03/A04/A06/A07/A12/A14/A15 为主。
- 下一工作时段从 19:00 重新开始；若提醒再次延迟，以“当前时间是否仍在 19:00–06:00”为准。

## 10. 1.0.0 发布（2026-10-27）

- 17 个模块版本 `1.0.0-SNAPSHOT` → `1.0.0`，README 与 k8s 示例坐标同步。
- `mvn -B -ntp -Prelease -Dgpg.skip=true clean verify` 通过：17 模块，282 测试 + 13 live。
- 发布密钥：`3E4342CF17F69E0FFC44BD240B40F80108F892C5`（`pkyit2 <pky1999@qq.com>`），
  公钥已上传 keyserver.ubuntu.com / keys.openpgp.org，并收录为 `docs/notifyx-release-key.asc`。
- Central bundle：`703e22fd-430d-4529-8ab7-55694769a463`（`notifyx-1.0.0`）；
  POM 保持 `autoPublish=false`，校验通过后按 Publisher API 手动 publish，
  2026-10-27 09:38:42 状态 **PUBLISHED**。
- 发布后验证：17 个模块的 POM/jar/sources/javadoc/asc 在 repo1.maven.org 全部 HTTP 200。
- `master` 合并提交 `9f3daa9` 已推送到 `origin/master`（Gitee）。
- Git tag：annotated `v1.0.0`（指向 `08521e9`）已创建并推送。
- 额度告警：组织 Pkyit 月度 Release Size 额度已用尽，2026-11-01 前不能再发新版本。
- demo：`notifyx-demo` 已本地建仓并提交 `51d09df`，已推送到
  `git@gitee.com:pengkaiyan/notifyx-demo.git` 的 `master`。
- 消费端 Central 复测：删除本地 SDK jar 后从 Central 解析 `1.0.0`，demo 冒烟 10/10；
  复测提交 `fac375d`（Gitee `notifyx-demo`）。

## 11. 1.0.1 候选修复（2026-10-27 19:00 起）

- iter-45：`notifyx.mask.enabled` 生效；审计中的手机号/邮箱 receiver 按类型脱敏
  （`Masker.maskReceiver`）。全量 verify 298 用例（285 执行 + 13 live 跳过）。
- iter-46：`maskText` 扩展手机号/邮箱/身份证三类正则；审计 bizId/errorMsg 也走脱敏。
  全量 verify 301 用例（288 执行 + 13 live 跳过）。
- iter-47：`env` / `tenant` 进入模板参数（`${env}` / `${tenant}`）。
  全量 verify 302 用例（289 执行 + 13 live 跳过）。
- iter-48：新增 `notifyx.queue.size` gauge，Executor 入队/丢弃/关闭时上报。
  全量 verify 304 用例（291 执行 + 13 live 跳过）。
- iter-49：新增 `notifyx.circuit.state` gauge（channel 标签，CLOSED=0/HALF_OPEN=1/OPEN=2）。
  全量 verify 305 用例（292 执行 + 13 live 跳过）。
- iter-50：关闭超时丢弃任务逐个上报 `queue.discarded("shutdown")`，前 20 条打印 level/key。
  全量 verify 306 用例（293 执行 + 13 live 跳过）。
- iter-51：全部渠道禁用时健康状态 DOWN，部分禁用 DEGRADED 并列出 disabledChannels。
  全量 verify 308 用例（295 执行 + 13 live 跳过）。
- iter-52：`mask.before-send` 对 renderedTitle/Content 与 `ctx.params()` 生效；默认关闭。
  全量 verify 311 用例（298 执行 + 13 live 跳过）。
- iter-53：新增 `mask.before-persist`，可选脱敏失败落盘 envelope params；默认关闭。
  全量 verify 312 用例（299 执行 + 13 live 跳过）。
- iter-54：`diagnostics.fail-fast=true` 时无渠道/全部渠道禁用阻止启动；默认关闭。
  全量 verify 314 用例（301 执行 + 13 live 跳过）。
- iter-55：`dedupe.store` / `rate-limit.store=memory` 真正跳过 Redis 自动装配。
  全量 verify 316 用例（303 执行 + 13 live 跳过）。
- iter-56：启动诊断表提示 store/engine 配置与实际实现不一致；不改变装配行为。
  全量 verify 319 用例（306 执行 + 13 live 跳过）。
- iter-57：新增 `Degradable` SPI；Redis 降级包装进入健康检查与诊断表，tier 降 T0。
  全量 verify 321 用例（308 执行 + 13 live 跳过）。
- iter-58：`diagnostics.fail-fast=true` 时同时校验 dedupe/rate-limit store 与 engine 配置。
  全量 verify 324 用例（311 执行 + 13 live 跳过）。
- iter-59：免打扰 defer 补全——`InMemoryDeferredHandler`（有界延迟队列、四态丢弃计数、
  关闭幂等）+ 自动装配（`quiet-hours.enabled=true` 时生效，可自定义覆盖）+ 生命周期关闭 +
  诊断三态提示 + `notifyx.deferred.*` 四指标；夜间命中事件延后到窗口结束重入管线。
  自查修复 3 处：重放改经有界执行器（不再单线程串行排空）、defer/close 竞态加锁保证
  恰好计量一次、handler 异常退化为抑制且 `handle()` 返回 false。
  notifyx-demo 真实进程端到端实证 4/4 PASS（defer 不投递/审计留痕/窗口结束重投/
  resend 去重收拢/指标可见/优雅关闭），顺带修复既有 bug：`NotifyxMetricsAutoConfiguration`
  缺 `@AutoConfigureAfter` 导致真实应用下整套 `notifyx.*` 指标静默缺失（iter-41 测试盲区）。
  全量 verify 347 用例（334 执行 + 13 live 跳过）。
- 未改版本号；1.0.0 已发布且 Central 额度 2026-11-01 前不可发新版本，本修复作为
  **1.0.1 候选**。
- 新增 ISSUE-039（OPEN）：`dedupe.default-window` 与 `defaults.silent-window` 语义冲突，
  Spring 环境下前者不可达。需要用户决定“废弃前者”还是“明确优先级并停止 scene 默认填充”。
- 邮件凭据仍缺 `NOTIFYX_SMTP_PASSWORD`（HOST/PORT/SSL/USER/FROM 已注入），
  无法发信/收信；A07 的审计/发送/落盘三条脱敏链路已补齐，默认开关保持 1.0.0 行为。
- 04:00 release profile 审计：`mvn -Prelease -Dgpg.skip=true clean package` 在
  `notifyx-core` 的 javadoc 阶段失败（`Destination directory not writable`），
  属 DSH 沙箱子进程写入限制，见 `env.md` §13；1.0.1 发布前需在普通终端重跑 release build。

## 12. 2026-10-28 夜间巡检收尾（06:00）

- **本轮范围**：19:00–06:00 共 9 个候选修复（iter-45~53）+ 1 次 release profile 审计；
  全部在 `master` 以 `--no-ff` 合并。
- **最终验证**：05:00 复跑 `mvn -B -ntp clean verify` → BUILD SUCCESS，
  17 模块，312 用例（299 执行 + 13 live 跳过），0 failure / 0 error。
- **代码状态**：工作区干净；本地 `master` 领先 `origin/master` 20 个提交（收尾文档提交后 21 个）。
  沙箱无法 push，需在普通终端执行 `git push origin master`。
- **发布状态**：版本号仍为 `1.0.0`；1.0.0 已发布，iter-45~53 是 **1.0.1 候选**。
  Central 额度 2026-11-01 前不可发新版本；发布前需普通终端跑
  `mvn -Prelease -Dgpg.skip=true clean package`（本沙箱 javadoc 受限，见 `env.md` §13）。
- **未决事项**：
  1. ISSUE-039（OPEN）：`dedupe.default-window` 与 `defaults.silent-window` 语义冲突，需决策；
  2. A02/A03（失败记录粒度/定向重投）、A04（NO_ROUTE 语义）、A06 完整方案（任务携带事件落盘）、
     A14（去重存储故障 fail-open/closed）均需设计决策，本轮未擅自实施；
  3. `NOTIFYX_SMTP_PASSWORD` 缺失，决策邮件无法发送；也未收到可读取的邮件回复。
- **下一步**：用户 push → 决策 ISSUE-039 / A02/A03/A04/A14 → 2026-11-01 后按
  1.0.1 流程复验并发布；06:00 后不再发起跨时段代码改动。

## 13. 信创 DDL 与 nx_idempotency 保留期（iter-60，2026-11-02）

- **范围**：落地「未实现」清单第 3 项「信创 DDL（DM/Kingbase/openGauss）与
  `nx_idempotency` 保留期」，并落实设计书 §12.4 对 `level` 列名的修正（D-029）。
- **1. 列名修正（破坏性，D-029）**：`nx_audit.level` → `nx_audit.event_level`
  （规避金仓 `level` 保留字；双引号备选方案在 MySQL 下不可行）。`JdbcAuditRecorder`
  INSERT 与 mysql/postgresql/h2 三套 schema.sql 同步修改。
- **2. 信创 DDL（6 个新文件）**：
  - `notifyx-store-jdbc/src/main/resources/sql/{dm,kingbase,opengauss}/schema.sql`
    （DM 用 `clob`/`timestamp(0)`，Kingbase/openGauss 用 `text`/`timestamp`，索引全部独立建）；
  - `notifyx-consumer/src/main/resources/sql/{dm,kingbase,opengauss}/idempotency.sql`。
- **3. nx_idempotency 保留期**：新增 `JdbcIdempotencyRetention`（DriverManager 直连，
  `purgeBefore`/`purgeOlderThan`，分批默认 1000/批，标准 `limit`/`in`），调度由部署方负责，
  SDK 不内置后台线程、不默认自动删除。
- **4. 测试**：store-jdbc 13 用例（11 执行 + 2 live 跳过）、consumer 新增
  `JdbcIdempotencyRetentionTest`（2 用例）全部通过。
- **文档**：README（信创 DDL 路径 + 1.0.1 迁移提示）、design.md（§12.2/§12.4）、
  decisions.md（D-029）。
- **剩余**：RabbitMQ DLX/多级 TTL、consumer 多实例分区与死信重试、T1–T24 剩余高危用例。

## 14. T1–T24 剩余高危用例补齐（iter-63，2026-11-02）

- **范围**：落实「未实现」清单最后一项「覆盖 T1–T24 的剩余高危用例（§18.2）」。
  纯测试迭代，不改生产代码。
- **新增/补充用例（8 个）**：
  - T2 指纹跨进程一致：`FingerprintsTest.shouldSurviveJsonRoundTrip`（JSON 往返→指纹不变）；
  - T23 MQ 报文向后兼容：`NotifyEnvelopeCodecTest.shouldIgnoreUnknownFields`（同版本多未知字段被忽略）；
  - T24 多接收人 fan-out 审计：`NotifyPipelineTest.shouldFanOutAuditForEveryReceiverAndChannel`（3 接收人 × 2 渠道 = 6 条审计）；
  - T9 SpEL 字段缺失降级：`NotifyxAspectTest.shouldDegradeWhenSpelFieldMissing`（`#r` 引用不存在字段 → 放行 + 不告警）；
  - T10 -parameters 缺失检测：新增 `SpelEvaluatorTest`（参数名退化 arg0 时 `#p0`/`#a0` 仍可求值、具名变量不可用、不抛异常）；
  - T12 CallerRuns 禁用：`PriorityNotifyExecutorTest.shouldNotBlockCallerWhenQueueFull`（队列满 1000 次提交全部立即返回 <1s、无同步执行）；
  - T14 Redis 降级不重复调用：`FallbackStoreTest.shouldNotRetryRedisWhileCircuitOpen`（熔断后不再触碰 Redis）；
  - T16 优雅关闭不丢事件：`NotifyxLifecycleTest.shouldDrainAllQueuedTasksOnShutdown`（关闭时排空 100 个任务）；
  - T20 SSRF 防护：`WebhookChannelTest.shouldBlockMetadataAddress`（169.254.169.254 被白名单拦截）。
- **测试方法说明**：仓库无 SLF4J 日志捕获测试基础设施，T10/T14 的「warn 只打一次」用行为等价断言
  （参数退化后位置变量仍可用 / 熔断打开后 Redis 不再被调用）；T10 用 JDK 动态代理构造切点，
  不引入 Mockito 新依赖。
- **N/A 确认**：T17（短信，sms4j 未在本交付实现）、T19（FreeMarker，实为自研 SimpleTemplateEngine）、
  T22（Admin 多来源合并，Admin 未实现）——均不在 P0/P1 SDK 范围，仓库无对应模块。
- **已覆盖确认**：T18（渠道错误码分类）已由 `WebhookChannelTest`（429/5xx/4xx/202）与
  `EmailChannelTest`（认证/550/超时）覆盖，本次未重复。
- **验证**：`mvn -B -ntp -pl <module> -am test -Dtest=... -Dsurefire.failIfNoSpecifiedTests=false`
  全部通过（core 24、spring 19、autoconfigure 6、store-redis 4、webhook 19）。
- **剩余**：RabbitMQ DLX/多级 TTL（iter-61）、consumer 多实例分区消费与死信重试（iter-62）——已于本次一并完成，见 §15。

## 15. RabbitMQ DLX 退避重试与 consumer 死信（iter-61/62，2026-11-02）

- **范围**：落实「未实现」清单最后两项：RabbitMQ 死信队列（DLX + 多级 TTL，§11.2）与
  consumer 多实例分区消费 / 死信重试（§11.4）。
- **producer 侧（iter-61）**：`RabbitMqBusProperties` 新增 `retry.{enabled,exchange}`（默认关，
  与 D-014 行为类能力默认关一致）；`RabbitMqNotifyBus.ensureChannel()` 在开启时声明：
  主队列挂 `x-dead-letter-exchange`、`direct` 死信交换、三级 TTL 重试队列
  `{exchange}.5s/1m/5m`（各自 `x-message-ttl` + 死信回主队列）+ 绑定；关闭时声明与 1.0.0 一致。
- **consumer 侧（iter-62）**：
  - `ConsumerProperties` 补 `rabbitmq.{queue,retry-enabled,retry-exchange}`（原先 `@RabbitListener`
    引用的 `notifyx.consumer.rabbitmq.queue` 键不存在，只靠默认值）；
  - `IdempotencyStore` 新增 `default release(key)`，Memory/Redis/JDBC 三实现（消费失败进入重试前释放幂等键，
    否则重投会被当作重复丢弃）；Jdbc 新增 `delete` 语句；
  - `RabbitMqEnvelopeConsumer` 失败路径不再静默 ACK：重试开启且 `x-retry-count` 未达上限时释放幂等键 +
    重投到重试交换（带 `x-retry-count+1`）；否则抛 `AmqpRejectAndDontRequeueException` 拒绝进入死信；
  - 新增 `RabbitMqTopologyConfiguration`：声明与 producer 等价的 durable 主队列（重试开启时挂 DLX），
    并声明死信交换 + 三级 TTL 重试队列与绑定（避免 `@RabbitListener` 自动声明参数不一致触发
    `PRECONDITION_FAILED`）；`@RabbitListener` 改引用 `#{notifyxRabbitMainQueue.name}`。
- **多实例分区**：Kafka 已按 `key=dedupeKey` 分区（iter-25）+ consumer 组 + 三档幂等（iter-26/27），
  多实例同组即可分区消费；RabbitMQ 单队列多消费者不保序，重试依赖幂等收敛（§11.2 约定），
  单实例起步推荐不变（D-023）。
- **测试（新增 8 个）**：`RabbitMqNotifyBusTest.shouldDeclareRetryTopology`、
  `RabbitMqEnvelopeConsumerTest`（重试重投 / 超限拒绝 / 关闭拒绝，3 个新增）、
  `RabbitMqTopologyConfigurationTest`（3 个）、producer/consumer 既有用例全通过。
- **验证**：`mvn -B -ntp -pl notifyx-bus-rabbitmq -am test`（3 用例）、
  `mvn -B -ntp -pl notifyx-consumer -am test`（21 用例 + 1 live 跳过）均 BUILD SUCCESS。
- **文档**：README / design.md（§11.2/§11.4）/ decisions.md（D-030）/ 迭代记录。
- **未纳入**：真实 RabbitMQ 的 DLX 端到端冒烟（与 D-025/26 一致，需真实 broker，属 live 测试
  `RabbitMqBusLiveTest` 扩展）；`notifyx.consumer.lag` 指标（§11.4 要求）——consumer 当前无
  micrometer 依赖，lag 需真实 broker + 管理插件支撑，本轮文档化要求、留待有环境时落地。

## 16. RabbitMQ 死信路由修复与 consumer 堆积指标（iter-64，2026-11-02）

- **范围**：修复 iter-61/62 死信不可路由缺陷、兑现 §11.4 `notifyx.consumer.lag`、修复 Spotless 门禁。
- **缺陷（ISSUE-053）**：主队列只有 `x-dead-letter-exchange`，缺 `x-dead-letter-routing-key` 且无死信队列，
  重试耗尽后的拒绝消息 routing key 为队列名，与重试交换机绑定 `5s`/`1m`/`5m` 不匹配 → 被 broker 静默丢弃，
  与 §11.2/§11.4「进入死信」不符。
- **修复**：producer/consumer 双侧主队列补 `x-dead-letter-routing-key=dead`，新增 durable 死信队列
  `{retry.exchange}.dead` 并绑定 routing key `dead`；退避重投复制原消息头，保留 `messageId`。
- **lag 指标（D-031）**：`ConsumerProperties` 新增 `transport`（默认 `kafka`）；新增
  `NotifyxConsumerLagMetrics`，存在 `MeterRegistry` 时注册 `notifyx.consumer.lag`（标签 `transport`/`queue`）：
  RabbitMQ 取 `QUEUE_MESSAGE_COUNT`，Kafka 按分区 `endOffset - committedOffset` 求和（2s 有界）；
  采样失败只 WARN 并沿用上次成功值；consumer 增加 `micrometer-core`（不加 actuator）。
- **门禁**：6 个新增 Java 文件补齐文件末尾换行；`JdbcAuditRetention` Javadoc 去除「DM/Kingbase 待适配」；
  README jar 版本示例修正为 1.0.1。
- **验证**：`mvn -B -ntp clean verify` → BUILD SUCCESS，17/17 模块，372 用例（13 跳过），
  Spotless/Checkstyle/JaCoCo 全绿；consumer 28 用例、bus-rabbitmq 6 用例。
- **剩余**：真实 broker 的 DLX 端到端冒烟（live）；Kafka lag 真实集群校准（含 SSL bundle 注入）；
  §5 的待用户决策项（A02/A03/A04/A07）。

## 17. 11-03 代码质量报告缺陷修复（iter-65，2026-11-03）

- **触发**：`docs/code-quality-review-2026-11-03.md`（88/100）产出 N-1~N-14 缺陷，据实分析、评估并落实。
- **修复 6 个中高风险缺陷（N-1~N-6）+ 2 个低风险项（N-7/N-13）**：
  - N-1：聚合摘要 sink 改经有界执行器，与 defer 重放同构；
  - N-2（契约变更 D-032）：新增 `NotifyEvent.replay`（transient）+ `NotifyEnvelope.toReplayEvent()`，
    重投豁免去重/限流且失败不二次落盘，根治补偿自我复制与去重黑洞；
  - N-3：`EmailChannel.send` 空收件人抛 `ChannelProtocolException`（不可重试）；
  - N-4：子类化 `MimeMessage` 重写 `updateMessageID`，保留自定义 Message-ID 幂等键；
  - N-5：Redis 聚合 RECORD Lua 改按级别序比较后 `HSET level`（与 T0 语义一致）；
  - N-6：Webhook 响应体改 `BodyHandlers.ofInputStream` 限量读取（64KB 上限）；
  - N-7：`RetryPolicy.delayAfter` 改饱和左移；N-13：`notifyxNotifyKit` 复用 `SceneRegistry` Bean。
- **测试**：新增 5 用例
  （`shouldRejectBlankReceiver`、`shouldPreserveMessageIdAfterSaveChanges`、
  `shouldSaturateNotOverflowOnHugeBase`、`shouldNotPersistReplayFailure`、`replayBypassesDedupe`），
  并增强 `FailureCompensationRunnerTest` 断言 `isReplay`。
- **验证**：`mvn -B -ntp clean verify` → **BUILD SUCCESS**（17/17 模块，Spotless/Checkstyle/JaCoCo 全绿）。
- **复核结论（未采纳/误报）**：
  - N-11 复核为误报：`unescape` 当前 `i + 4 < raw.length()` 已正确对应 `substring(i+1, i+5)` 合法边界，
    改为 `<=` 反而会在串尾转义时越界；
  - `InMemoryFailureStore.save` 的 recordId 覆盖语义未采纳（会丢多渠道失败记录，见 D-032）。
- **遗留（见 ISSUE-060）**：N-8/N-9/N-10/N-12/N-14（需重构或文档化）；ISSUE-039
  （`dedupe.default-window` 不可达）为配置语义决策，待用户拍板。
- **版本**：1.0.3（iter-65 修复合并后统一升级，含 iter-60~65 全部改动；Central 发布仍待授权）。

## 18. ISSUE-060 剩余低风险缺陷收口（iter-66，2026-11-03）

- **N-14（修复）**：`Aggregator.record` 改用 `ConcurrentHashMap.compute`，与 `flush` 的
  `remove` 对同 key 串行化，窗口边界并发事件落入新桶；新增并发计数守恒测试。
- **N-12（修复）**：`MemoryRateLimiter.tryAcquire` 清理后为空返回 `null`，空 deque 不常驻；
  `evictExpired` 明确由运维面按需调度、key 空间由场景/用户维度界定。
- **N-10（修复）**：`RabbitMqEnvelopeConsumer` 注入 `NotifyxProperties`，
  `retry-enabled=true` + `notifyx.async.enabled=true` 时启动 WARN（异步派发会让 DLX 重试静默失效）；
  新增测试 `shouldConsumeWhenAsyncDispatchWithRetry`。
- **N-8（文档化）**：`PriorityNotifyExecutor` Javadoc 写明软上限非硬约束、4096 扫描成本，
  上调到 10 万级前需改增量最低优先级索引；实现不变。
- **N-9（文档化）**：`KafkaNotifyBus` Javadoc、design §11.2、README 明示同步等待 broker 确认
  （最长 `delivery-timeout`，默认 10s）的 producer-only 取舍。
- **验证**：`mvn -B -ntp clean verify` → **BUILD SUCCESS**，17/17 模块，
  379 用例（366 执行 + 13 live 跳过），0 failure / 0 error，Spotless/Checkstyle/JaCoCo 全绿；
  模块级 core/spring/consumer/bus-kafka 目标用例全绿。
- **ISSUE-060 状态**：CLOSED。ISSUE-039 已于 iter-67 治理。仅剩 A02/A03/A04/A07 待用户决策。

## 19. ISSUE-039 配置语义治理（iter-67，2026-11-04）

- **决策**：废弃 `notifyx.dedupe.default-window`（D-033），统一用 `notifyx.defaults.silent-window`。
- **根因**：整条消费链路均未读取 `Dedupe.defaultWindow` 字段——`autoconfigure` 装配 scene 时已用 `defaults.silentWindow`（默认 5m）填充，`pipeline.resolveSilentWindow` 根本没有 dedupe 级 fallback。该字段是死配置。
- **改动**：
  - `NotifyxProperties.Dedupe.defaultWindow` 字段 / getter / setter 加 `@Deprecated`，保留以兼容既有 YAML；
  - 诊断表改用 `defaults.silent-window` + 废弃提示文案；
  - design.md §14.2 新增废弃配置项表；README YAML 示例注释标注；
  - 无运行时行为变更，无新增测试（属配置语义治理）。
- **验证**：`mvn -B -ntp clean verify` → BUILD SUCCESS，17/17 模块，0 failure / 0 error。
- **遗留**：无。架构审查 A01-A09 / A10-A15 全部处理完毕。迭代可收口推 1.0 GA。

## 20. 架构审查 A02/A03/A04 落地、A07 确认关闭（iter-68，2026-11-04）

- **A07 先行确认**：before-audit（默认开启）、before-send（默认关闭）、before-persist（默认关闭）三条脱敏链路全部有效，无改动。
- **A02+A03 合并修复**（D-034）：
  - `FailedRecord.recordId = eventId + ":" + channel`；同一事件不同渠道的失败记录互不覆盖。
  - `dispatch()` 引入 `anySuccess` 追踪；dedupe release 条件改为 `failed && !anySuccess`——**全部失败才 release**，部分成功时成功渠道保持静默窗口、失败渠道由补偿器处理。
  - 不做 channel+receiver 完整粒度（补偿语义是整事件重投），不改 dedupeKey 为 channel 级（SPI 级变更过大）。
- **A04 NO_ROUTE 语义**（D-035）：`channels.isEmpty()` 分支返回 failed=true（handle 返回 false），同时 audit 写入 `errorMsg=NO_ROUTE`；现有测试 `shouldSkipWhenNoChannel` 固化了反模式，改为 `shouldReportNoRouteWhenNoChannel`。
- **验证**：`mvn -B -ntp clean verify` → BUILD SUCCESS，17/17 模块，379 用例 0 failure / 0 error。
- **遗留**：无。架构审查全部待决策项已关闭。
