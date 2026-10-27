# NotifyX 状态快照

> 更新时间：2026-10-27 09:35（iter-44：NotifyX 1.0.0 发布）
> 交付范围：设计书 **P0/P1 的 SDK 部分 + P2（Redis T1、JDBC）+ P3（Kafka/RabbitMQ producer、双传输 consumer）**。
> **不是** 23 周后的 1.0 GA。
> 构建基线：`mvn -B clean verify` 通过（Spotless/Checkstyle/JaCoCo 全绿），
> 17 个模块，282 测试执行 + 13 live 跳过（共 295 用例）；`mvn -Prelease -Dgpg.skip=true package` 通过，两个 starter 均有 sources/javadoc。

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
`notifyx.retry.enabled=false` 真正只尝试一次。
Maven Central 已发布 **1.0.0**（iter-44，2026-10-27）；P2+ 目标见 iter-31 审计，后续为 DLX/多实例/信创 DDL/Admin 等扩展。

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
- [ ] RabbitMQ 死信队列（DLX + 多级 TTL）
- [ ] consumer 多实例分区消费与死信/重试
- [ ] 信创 DDL（DM/Kingbase/openGauss）与 `nx_idempotency` 保留期
- [ ] 覆盖 T1–T24 的剩余高危用例（§18.2）

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
2. **P2+ 扩展**：RabbitMQ DLX/多级 TTL、consumer 多实例分区与死信重试、信创 DDL
   （DM/Kingbase/openGauss）与 `nx_idempotency` 保留期、Admin 控制台（P4）。
3. **k8s 生产化**：自有镜像仓库 + Deployment + Secret 注入。
4. **测试债**：T1–T24 剩余高危用例；core 覆盖率向 90% 提升。
5. **Central 发布**：1.0.0 已于 2026-10-27 发布（iter-44）；后续修复按 §19.3 发 patch 版本。

## 6. 当前分支

`master`（iter-44 已合并并推送到 `origin/master`，合并提交 `9f3daa9`；
`release/1.0.0` 分支保留）。
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
