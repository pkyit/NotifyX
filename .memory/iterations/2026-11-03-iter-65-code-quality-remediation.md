# iter-65 · 代码质量报告（2026-11-03）缺陷修复

- **日期**：2026-11-03
- **类型**：fix（修复 `docs/code-quality-review-2026-11-03.md` 的 6 个中高风险缺陷 + 2 个低风险项）
- **触发**：11-03 代码质量评估（88/100）产出 N-1~N-14 缺陷清单，要求据实分析、评估并落实。

## 1. 问题

| # | 问题 | 证据 |
| --- | --- | --- |
| N-1 | 聚合摘要 `sink(pipeline::handle)` 在单线程调度器上同步跑完整管线 | `NotifyxAutoConfiguration.java:385` vs 387-388 defer 重放 |
| N-2 | 补偿重投自我复制失败记录 + 被自身去重器判死 | `FailureCompensationRunner.java:130-162`；`NotifyPipeline.java`（recordId=eventId） |
| N-3 | 空收件人 NPE 被当可重试，引发重试风暴 + 熔断污染 | `EmailChannel.java:94` |
| N-4 | JavaMail `updateMessageID()` 覆盖自定义 Message-ID，幂等头静默失效 | `EmailChannel.java:98-101` |
| N-5 | T1 Redis 聚合 `HSETNX level` 首事件定死，与 T0 取最高级别语义分叉 | `RedisAggregator.java` RECORD Lua |
| N-6 | Webhook `BodyHandlers.ofString()` 整 body 读进内存，无大小上限 | `WebhookChannel.java` |
| N-7 | 退避乘法 long 溢出（基数过大退避变 1ms） | `RetryPolicy.java` |
| N-13 | 重复构造 SceneRegistry，未复用同名 Bean | `NotifyxAutoConfiguration.java` |

## 2. 方案

- **N-1**：聚合 sink 与 defer 重放同构，改经有界 `taskExecutor.execute`，拒绝走丢弃日志。
- **N-2**：新增 `NotifyEvent.replay`（`transient`，不进序列化）+ `NotifyEnvelope.toReplayEvent()`；
  `DedupeFilter`/`SceneRateLimitFilter`/`GlobalRateLimitFilter` 对 replay 直接放行；
  `NotifyPipeline.persistFailure` 对 replay 短路；补偿侧改用 `toReplayEvent`。
  未采纳 `save` 的 recordId 覆盖语义（见 D-032 影响）。
- **N-3**：`EmailChannel.send` 前置校验空/空白 receiver 抛 `ChannelProtocolException`。
- **N-4**：子类化 `MimeMessage`（`MessageIdPreservingMimeMessage`）重写 `updateMessageID` 保留自定义值。
- **N-5**：RECORD Lua 改为按 INFO<WARN<ERROR<CRITICAL 序比较后 `HSET level`（未知级别 WARN 兜底）。
- **N-6**：改 `BodyHandlers.ofInputStream` + `readBody`（64KB 上限）后再 classify。
- **N-7**：`RetryPolicy.delayAfter` 改饱和左移 `saturatingLeftShift`。
- **N-13**：`notifyxNotifyKit` 注入并复用 `SceneRegistry` Bean。

## 3. 验证

| 测试 | 覆盖 |
| --- | --- |
| `FailurePersistenceTest.shouldNotPersistReplayFailure` | replay 失败不二次落盘 |
| `NotifyPipelineTest.replayBypassesDedupe` | replay 窗内跳过 DedupeFilter |
| `FailureCompensationRunnerTest`（断言 `isReplay`） | 补偿重投透传 replay 标记 |
| `EmailChannelTest.shouldRejectBlankReceiver` | 空收件人不可重试、不发起投递 |
| `EmailChannelTest.shouldPreserveMessageIdAfterSaveChanges` | saveChanges 后 Message-ID 保留 |
| `RetryPolicyTest.shouldSaturateNotOverflowOnHugeBase` | 超大基数退避饱和到上限 |

模块级：`mvn -B -ntp -pl notifyx-core,notifyx-channel-email,notifyx-channel-webhook,notifyx-spring-boot3-autoconfigure -am test`
BUILD SUCCESS。

全量：`mvn -B -ntp clean verify`（见 STATE §17，Acceptance 前复验）。

## 4. 兼容性

- `NotifyEvent.replay` 为 `transient`，不进序列化，旧 envelope 反序列化默认 false，向后兼容；
- `toEvent()` 行为不变，新增 `toReplayEvent()` 仅在补偿侧使用；
- N-3/N-4/N-6 只影响 email/webhook 渠道的错误分类与响应读取，正常投递路径语义不变；
- N-5 只改 Redis 聚合 level 语义，使其与 T0 一致（原行为本就是缺陷）。

## 5. 文档与门禁

- `docs/design.md` §9.9 补「补偿重投豁免」；
- `.memory/decisions.md` 新增 D-032；`.memory/issues.md` ISSUE-054~059 CLOSED、ISSUE-060 部分处理；
- `.memory/STATE.md` 新增 §17。

## 6. 未纳入（遗留，见 ISSUE-060）

- N-8（PriorityNotifyExecutor 队列替换非原子 + O(n) 扫描）、N-9（Kafka `.get(10s)` 同步阻塞，需文档明示）、
  N-10（RabbitMQ DLX 依赖 `async.enabled=false` 隐式约定，无启动校验）、
  N-12（MemoryRateLimiter 空 deque 常驻 map）、N-14（Aggregator flush/record 竞态边界丢 1 条计数）——
  均属需重构或文档化性质，并入后续批次；
- ISSUE-039（`dedupe.default-window` 不可达）为配置语义决策，需用户拍板，本轮未动。