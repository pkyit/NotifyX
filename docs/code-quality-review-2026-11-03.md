# NotifyX 代码质量评估报告（2026-11-03）

> 审计对象：`master@93656b8`（1.0.1 候选，iter-64 之后），17 个模块，工作区干净。
> 审计方式：全量 `mvn -B -ntp clean verify` 基线验证 + 约 30 个关键源文件逐行精读 + 与 `.memory/issues.md` 53 条历史缺陷交叉比对（只报告历史未记录的新发现）。
> 结论先行：**主干质量过硬，架构纪律全部守住；但三条低频旁路路径（聚合摘要、失败补偿、MQ 消费重试）存在 6 个中高风险缺陷**。这些路径默认关闭或低频触发，因此既有测试未暴露，一旦启用即会显现。总体评分 **88 / 100（良好，局部需修复）**，高于 2026-10-22 审计（76 分）——上一轮 16 个 P0/P1 问题已在此后迭代中全部闭环。

---

## 1. 总体评价

| 维度 | 评价 | 说明 |
| --- | --- | --- |
| 架构分层 | ★★★★★ | core 零 Spring（grep 验证）、渠道互不可见、starter 空聚合、依赖方向单向，全部守住 |
| 可靠性语义 | ★★★★☆ | 结果状态机（SUCCESS/FAIL_*/SUPPRESSED/DEDUPED/…）已显式化；补偿重投路径仍有自我放大缺陷 |
| 并发与资源 | ★★★★☆ | 有界队列/缓存/落盘齐全，无 CallerRuns，try-with-resources 全覆盖；个别 check-then-act 竞态 |
| 安全边界 | ★★★★☆ | 脱敏三链路（audit/send/persist）生效、SpEL 只读上下文、webhook 签名+白名单；响应体无界是唯一缺口 |
| 配置契约 | ★★★★☆ | iter-41~58 已把「绑定不生效」问题基本清零；仅剩 ISSUE-039 一处语义冲突待决策 |
| 测试与门禁 | ★★★★☆ | 372 用例全绿、Spotless/Checkstyle 0 违规、JaCoCo 达标；旁路路径（聚合/补偿）覆盖仍薄 |

**一句话结论**：NotifyX 的核心投递链路已达到生产可用水平；本轮发现的问题全部集中在「默认关闭的旁路能力」上，修复成本低（合计约 100~150 行改动 + 测试），不阻塞 1.0.1 发布，但建议在发布前完成 N-1/N-2/N-3 三项。

---

## 2. 基线验证结果

### 2.1 构建与门禁（2026-11-03 实测）

```text
mvn -B -ntp clean verify  →  BUILD SUCCESS（17/17 模块，3 分 17 秒）
Spotless：0 needs changes    Checkstyle：0 violations    JaCoCo：全部门禁达标
```

| 模块 | 指令覆盖率 | 分支覆盖率 | 门禁 |
| --- | --- | --- | --- |
| 最高模块 | 93.7% | 85.0% | — |
| 模块均值 | ~84% | ~66% | — |
| 最低模块 | 65.2% | 49.3% | ≥40% 达标 |

### 2.2 架构合规矩阵（逐条实测）

| 设计约束 | 验证方式 | 结果 |
| --- | --- | --- |
| core 零 Spring/JPA/MyBatis 依赖 | grep 全模块 import | ✅ 0 命中 |
| 无 `System.out`/`System.err` | grep 全仓库 | ✅ 0 命中 |
| 渠道模块互不依赖 | pom 依赖树 | ✅ |
| 队列禁用 CallerRunsPolicy | `PriorityNotifyExecutor` 用 AbortPolicy + 软上限替换 | ✅ |
| Redis 无 KEYS/SCAN | grep + Lua 脚本审查（SET NX PX / ZSET / Hash） | ✅ |
| JDBC 资源释放 | 全部 try-with-resources | ✅ |
| 渠道超时 | email（connectiontimeout/timeout/writetimeout）、webhook（connect/read）均配置 | ✅ |
| 熔断隔离 | 每渠道独立 `CircuitBreaker`（ConcurrentHashMap 持有） | ✅ |
| 脱敏链路 | mask.enabled / before-audit / before-send / before-persist 四开关均真实生效 | ✅ |
| CRITICAL 旁路矩阵 | 免打扰/限流直通、去重窗口减半且 ≤60s | ✅ |
| Logback 防自递归 | logger 前缀过滤 + ThreadLocal 重入保护双防线 | ✅ |

---

## 3. 关键发现汇总

| 编号 | 严重度 | 问题 | 位置 | 登记 |
| --- | --- | --- | --- | --- |
| N-1 | 中高 | 聚合摘要 sink 直连管线，阻塞单线程调度器 | `NotifyxAutoConfiguration.java:385` | ISSUE-054 |
| N-2 | 中高 | 补偿重投自我复制失败记录 + 去重黑洞 | `FailureCompensationRunner.java:154-162` | ISSUE-055 |
| N-3 | 中高 | EmailChannel 空收件人 NPE 重试风暴 | `EmailChannel.java:94` | ISSUE-056 |
| N-4 | 中 | 自定义 Message-ID 被 JavaMail 静默覆盖 | `EmailChannel.java:98` | ISSUE-057 |
| N-5 | 中 | T0/T1 聚合 level 语义不一致 | `Aggregator.java:93` vs `RedisAggregator.java:50` | ISSUE-058 |
| N-6 | 中 | Webhook 响应体读取无大小限制 | `WebhookChannel.java:104` | ISSUE-059 |
| N-7 | 低 | RetryPolicy 退避乘法 long 溢出 | `RetryPolicy.java:78` | ISSUE-060 |
| N-8 | 低 | 执行器队列满替换路径竞态 + O(n) 扫描 | `PriorityNotifyExecutor.java:76-109` | ISSUE-060 |
| N-9 | 低 | Kafka producer 同步 `.get()` 阻塞调用线程 | `KafkaNotifyBus.java:71` | ISSUE-060 |
| N-10 | 低 | RabbitMQ 重试拓扑依赖 `async.enabled=false` 隐式约定 | `RabbitMqEnvelopeConsumer.java:86-99` | ISSUE-060 |
| N-11 | 低 | LocalFileFailureStore `\u` 转义边界差一 | `LocalFileFailureStore.java:510` | ISSUE-060 |
| N-12 | 低 | MemoryRateLimiter 空 deque 常驻、evictExpired 无调用方 | `MemoryRateLimiter.java:33-43,71` | ISSUE-060 |
| N-13 | 低 | notifyxNotifyKit 重复构造 SceneRegistry | `NotifyxAutoConfiguration.java:355` | ISSUE-060 |
| N-14 | 低 | Aggregator flush/record 竞态丢计数 | `Aggregator.java:132-142` | ISSUE-060 |

---

## 4. 中高风险问题详析

### N-1. 聚合摘要 sink 直连管线，阻塞单线程调度器

**位置**：`NotifyxAutoConfiguration.java:385`、`Aggregator.java:58,117`、`RedisAggregator.java:83,134`

**现象**：`notifyxNotifyKit` 装配时执行 `aggregator.sink(pipeline::handle)`。T0 `Aggregator` 与 T1 `RedisAggregator` 的窗口 flush 都运行在各自的**单线程** `ScheduledExecutorService` 上，`sink.accept(summary)` 会同步执行完整管线：渲染 → 渠道发送 → 重试循环（含 `Thread.sleep` 退避，`NotifyPipeline.java:519-527`）→ 审计 → 落盘。

**触发场景**：聚合开启（`notifyx.aggregate.enabled=true`）且摘要目标渠道慢或持续失败（如 SMTP 超时、webhook 5xx）。一条摘要可占用调度线程数秒至数十秒（3 次尝试 × 2s/4s 退避 + 渠道超时），**期间该实例所有其他场景的窗口摘要全部排队延迟**；`RedisAggregator` 还在同一线程上叠加多次 Redis 往返。

**影响**：聚合摘要的时效性承诺被破坏——风暴场景下恰恰是最需要摘要的时候，摘要反而被最慢的渠道卡住。同时违背红线「异步路径必须有界、可关闭、可观测」中的有界时延语义。

**关键证据**：iter-59 已为 `InMemoryDeferredHandler` 修复过完全相同的问题，修复注释就在同一方法内（`NotifyxAutoConfiguration.java:387-388`：「重放必须经有界执行器：单线程直接 handle 会串行排空……」），但聚合器 sink 被遗漏。

**修复建议**：聚合 sink 与 defer 重放同构，改经 `taskExecutor.execute(level, key, () -> pipeline.handle(event))`，拒绝时走既有丢弃计数。预计改动 <10 行 + 1 个装配测试。

---

### N-2. 补偿重投自我复制失败记录 + 去重黑洞

**位置**：`FailureCompensationRunner.java:130-162`、`NotifyPipeline.java:407-433`、`InMemoryFailureStore.java:48-53`

**现象**：补偿链路 `compensate → deliver → DefaultNotifyKit.deliverSync → pipeline.handle` **完整重入管线**，产生两个连锁缺陷：

1. **失败记录自我复制**。重投再次失败时，`sendOne` 的 `persistFailure` 会以**同一个 `eventId`** 再生成一条 `FailedRecord`（`NotifyPipeline.java:426`）。三种存储的表现：
   - `InMemoryFailureStore.save()` 无 recordId 唯一性约束，deque 中累积同 eventId 重复记录，每条都会被后续轮次 `loadPending` 扫到并重投——对持续失败的渠道形成**逐轮放大**的重复投递（容量 10K 有界，不 OOM，但浪费发送、污染审计）；
   - `JdbcFailureStore` 靠主键冲突挡住，退化为每次补偿一条 `PK duplicate` warn 噪音；
   - `LocalFileFailureStore` 按小时分片，新记录可能落到不同分片，产生同 recordId 双文件，索引只保留其一，另一文件成为僵尸。
2. **去重黑洞**。重投事件会重新经过 `DedupeFilter`：若仍在静默窗口内，被判 `DEDUPED` → `handle` 返回 `false` → 补偿视为投递失败 → `requeue` 退避 → 再投再被去重 → 达到 `maxAttempts` 后转 **DEAD**。即「本该补偿成功的通知」被系统自己的去重器判死。

**触发场景**：开启 `notifyx.fallback.compensation.enabled=true`，渠道持续故障且静默窗口 ≥ 补偿退避（默认 5m 对 5m，边界恰好命中）。

**影响**：补偿是「通知不丢」的最后防线，该缺陷让防线自身产生重复投递与静默误杀，属于语义级缺陷而非性能问题。

**修复建议**：
- 补偿重投携带豁免标记（如 `NotifyContext` 属性或管线入口参数），跳过 dedupe/rate-limit 过滤器（静默语义对补偿无意义）；
- 补偿路径下的失败**不再调用 `persistFailure`**（原记录已在补偿状态机内，requeue/DEAD 即可）；
- `InMemoryFailureStore.save` 增加 recordId 覆盖语义（或补偿侧生成新 recordId 并维护原记录引用）。
- 预计改动 40~60 行 + 3 个测试（重复落盘、去重豁免、DEAD 收敛）。

---

### N-3. EmailChannel 空收件人 NPE 重试风暴

**位置**：`EmailChannel.java:94`、`NotifyPipeline.java:225-226,343-355`

**现象**：场景未配置接收人（且事件未携带）时，`dispatch` 以 `receiver=null` 调用 `sendOne`；`EmailChannel` 执行 `InternetAddress.parse(ctx.receiver(), false)` 抛出 `NullPointerException`。NPE 不是 `ChannelException`，落入管线 `catch (RuntimeException)` 分支，被**按可重试失败处理**：重试 `maxAttempts` 次、每次记熔断失败、最终失败落盘 + 补偿。

**触发场景**：配置错误——邮件渠道的场景忘配 `receivers`。

**影响**：一个静态配置错误转化为运行期重试风暴：每条事件 ×3 次无效 SMTP 会话、熔断器被打开波及其他正常场景、失败存储被垃圾记录填充、补偿器反复重投。与「渠道异常必须区分可重试/不可重试；参数类错误不能被盲目重试」的红线直接冲突。

**修复建议**：`EmailChannel.send` 开头校验 `ctx.receiver()` 为空/空白即抛 `ChannelProtocolException`（不可重试，一次即止并落审计）。1 行校验 + 1 个契约测试。

---

### N-4. 自定义 Message-ID 被 JavaMail 静默覆盖

**位置**：`EmailChannel.java:98-101`

**现象**：发送前 `message.setHeader("Message-ID", "<eventId@notifyx>")`，但 `Transport.send(message)` 内部会调用 `saveChanges() → updateHeaders()`，`MimeMessage.updateMessageID()` **无条件重新生成 Message-ID**，手工设置的幂等头必然被覆盖。这是 Jakarta Mail 的经典陷阱，保住自定义 Message-ID 的标准做法是子类化 `MimeMessage` 并重写 `updateMessageID()`。

**证据链**：`EmailChannelTest` 使用 Mockito 静态 mock `Transport.send`，mock 不执行真实 `saveChanges`，因此契约测试永远看不到该覆盖——与 ISSUE-016（Authenticator 缺失）同属「静态 mock 覆盖不到协议交互」的测试盲区。

**影响**：若下游邮件网关/收件方按 Message-ID 做幂等去重，NotifyX 的幂等意图静默失效（重发会被当成新邮件）。`X-Notifyx-Event-Id` 头仍在，可作为替代幂等键，但需要在文档中明示。

**修复建议**：用内部 `MimeMessage` 子类重写 `updateMessageID()` 保留自定义值；或退一步，在 README/设计书注明幂等以 `X-Notifyx-Event-Id` 为准。

---

### N-5. T0/T1 聚合 level 语义不一致

**位置**：`Aggregator.java:93`（T0）vs `RedisAggregator.java:50`（T1）

**现象**：
- T0 内存聚合：`if (level.atLeast(bucket.level)) bucket.level = level` —— 窗口内取**最高**级别；
- T1 Redis 聚合：Lua 中 `HSETNX level` —— **第一个事件定死**，后续更高级别被忽略。

**触发场景**：故障演进——窗口内先来 WARN 事件，随后升级为 ERROR/CRITICAL。

**影响**：同一故障在 T0 环境摘要显示 ERROR、在 T1 环境摘要停留在 WARN。能力梯度的承诺是「只换存储、不换语义」，此处语义分叉会让按级别路由/升级的下游规则在不同环境表现不同，且极难排查。

**修复建议**：Lua 脚本改为比较写入（`HGET level` → 按 INFO<WARN<ERROR<CRITICAL 序比较 → 更高才 `HSET`），或 Java 侧读改写（聚合是低频路径，可接受非严格原子）。

---

### N-6. Webhook 响应体读取无大小限制

**位置**：`WebhookChannel.java:104`

**现象**：`client.send(request, HttpResponse.BodyHandlers.ofString())` 先把**整个响应体**读进内存字符串，之后 `classify` 才做 1KB `truncate`。`readTimeout` 只约束相邻字节间隔，不约束总量。

**触发场景**：接收端故障返回超大错误页、网关返回巨型 HTML、或恶意/被劫持的接收端慢速滴灌大 body。

**影响**：一次发送即可造成堆压力甚至 OOM；慢速大 body 还会长时间占用发送线程（间接消耗执行器容量）。与「异步路径必须有界」红线冲突。

**修复建议**：改用 `BodyHandlers.ofInputStream()` 并限量读取（如 64KB，足够分类与截断），或用 `BodySubscribers.mapping` 做流式截断。

---

## 5. 低风险问题清单

| 编号 | 位置 | 问题与建议 |
| --- | --- | --- |
| N-7 | `RetryPolicy.java:78` | `base.toMillis() * (1L << min(20, attempt-1))`：base > ~106 天时乘法溢出为负，`Math.min` 选中负值，最终退避变 1ms。仅不现实配置可触发。建议先 cap 再乘 |
| N-8 | `PriorityNotifyExecutor.java:76-109` | 队列满替换路径 `size` 检查与 `remove(lowest)` 之间非原子：并发下软上限可短暂突破或一次丢两条；`lowestPriority()` 每次 O(n) 全队列遍历。后果有界（软上限非硬约束），容量 4096 下可接受；若未来上调容量需重构 |
| N-9 | `KafkaNotifyBus.java:71` | `producer.send(record).get(10s)` 同步等待——MQ 模式下业务线程最长阻塞 10s/条，且 `MqNotifyKit.sendAsync` 与 `send` 语义完全相同。属 producer-only 的设计取舍（换取失败即落盘的确定性），但命名有误导，建议文档明示或提供真异步选项 |
| N-10 | `RabbitMqEnvelopeConsumer.java:86-99` + consumer `application.yml` | 失败重试分支依赖 `notifyKit.send` 同步抛异常；sample 配置靠 `async.enabled: false` 保证，但无文档强约束。若部署方开启 async，消息照常 ACK，DLX 重试拓扑静默失效。建议在 consumer 文档/启动诊断中校验并告警 |
| N-11 | `LocalFileFailureStore.java:510` | `unescape` 中 `\uXXXX` 边界判断 `i + 4 < raw.length()` 应为 `i + 4 <= raw.length()`，恰好位于串尾的 Unicode 转义被静默吞掉一个字符 |
| N-12 | `MemoryRateLimiter.java:33-43,71` | `compute` 恒返回非 null，空 deque 常驻 map；`evictExpired()` 全仓库无调用方（grep 确认）。键为场景级有界，实际影响极小，建议接入生命周期定期清理或 compute 返回 null |
| N-13 | `NotifyxAutoConfiguration.java:355` | `notifyxNotifyKit` 内部直接调用 `notifyxSceneRegistry(properties)` 构造第二个 SceneRegistry，未复用同名 Bean；用户若注入 `SceneRegistry` Bean 会与管线持有的不是同一实例。建议改为注入 |
| N-14 | `Aggregator.java:132-142` | flush 移除 bucket 后，已持有该 bucket 引用的并发 `record` 仍会递增计数，该计数丢失（窗口边界最多 1 条/窗口）。影响极小，记录备查 |

**设计边界提示**（非缺陷）：

- `QuietHoursFilter.java:82-89` `nextEnd` 在 DST 切换时区可能偏移（中国无 DST，配置境外时区需注意）；
- `Fingerprints.java:95-99` 数值归一使 `1`（数字）与 `"1"`（字符串）指纹相同——已知取舍，极端场景下不同业务语义可能被去重到一起；
- `DurationParser` 接受负值（如 `-5s`），效果等同关闭静默，语义可接受但未文档化。

---

## 6. 既有 OPEN 问题复核（来自 .memory）

| Issue | 状态 | 本轮复核结论 |
| --- | --- | --- |
| ISSUE-039 `dedupe.default-window` 不可达 | OPEN | 确认存在：`NotifyPipeline.java:581-589` fallback 硬编码 5m，`dedupe.default-window` 无任何读取点。建议废弃该配置项，文档统一指向 `defaults.silent-window` |
| ISSUE-012 Appender 硬编码前缀 | OPEN | 确认存在：业务代码位于 `io.github.pkyit.notifyx.*` 包下会被静默丢弃。建议提供可配置排除前缀 |
| ISSUE-013 覆盖率目标 | OPEN | core ~78-81%，距 90% 目标有差距；本轮发现的聚合/补偿路径恰是覆盖薄弱区 |
| ISSUE-002 发布链路 | OPEN | 1.0.0 已发布，仅剩后续 patch 的 release profile 沙箱复验 |
| ISSUE-008 双份设计书 | OPEN | `docs/design.md` 与 `docs/NotifyX架构设计书.md` 仍并存 |
| A02/A03（STATE.md 待决策） | 待决策 | 复核确认仍存在：`NotifyPipeline.java:233-235` 任一渠道失败即整体释放去重槽位，部分成功场景可能重发 |

---

## 7. 修复路线建议

按「语义正确性 > 资源边界 > 可观测性」排序：

| 批次 | 内容 | 预计改动 | 验证要点 |
| --- | --- | --- | --- |
| 第 1 批（1.0.1 发布前） | N-2 补偿豁免 + 去重黑洞；N-1 聚合 sink 经执行器；N-3 邮件空收件人校验 | ~80 行 + 6 测试 | 补偿不产生重复记录；聚合慢渠道不阻塞其他窗口；空收件人一次即止 |
| 第 2 批 | N-6 响应体限量；N-5 聚合 level 取高；N-4 Message-ID 子类化 | ~60 行 + 4 测试 | 超大响应体不 OOM；T0/T1 摘要级别一致 |
| 第 3 批（可并入 1.0.2） | N-7~N-14 低风险项 + ISSUE-039 决策落地 | ~50 行 | 逐项单测 |
| 持续 | core 覆盖率向 90% 推进，优先补聚合/补偿/生命周期路径 | — | JaCoCo |

**依赖关系**：N-1 与 N-14 同属聚合路径，建议同批改；N-2 修复需先定「补偿豁免」的 API 形态（管线入口参数 vs FilterContext 属性），属小型契约变更，按仓库规范需同步 `docs/design.md` §9.9 并评估是否新增 D-NNN。

**流程要求**（按 AGENTS.md / .skills）：每批一个 `fix/<slug>` 分支、原子提交、全量 `mvn -B -ntp clean verify`、`--no-ff` 合并 master 并升版本号、同提交更新 `.memory/STATE.md` 与 iteration 记录。

---

## 附录 A：本轮精读文件清单

**core（14）**：NotifyPipeline、RetryPolicy、CircuitBreaker、EscalationPolicy、Aggregator、EventAggregator、DefaultNotifyKit、MqNotifyKit、6 个 Filter、Fingerprints、JsonCodec、MemoryDedupeStore、MemoryRateLimiter、InMemoryFailureStore、LocalFileFailureStore、SimpleTemplateEngine、NotifyEnvelopeCodec、DurationParser、LoggingAuditRecorder、InMemoryDeferredHandler

**spring（4）**：NotifyxAspect、SpelEvaluator、PriorityNotifyExecutor、NotifyxEventBridge

**autoconfigure（4）**：NotifyxAutoConfiguration、NotifyxLifecycle、FailureCompensationRunner、NotifyxProperties（默认值抽查）

**渠道与接入（3）**：EmailChannel、WebhookChannel、NotifyxAppender

**存储与 MQ（7）**：RedisDedupeStore、RedisRateLimiter、RedisAggregator、FallbackDedupeStore、RedisDegradation、JdbcFailureStore、JdbcAuditRecorder、RabbitMqNotifyBus、RabbitMqEnvelopeConsumer、KafkaNotifyBus、KafkaEnvelopeConsumer、consumer application.yml

## 附录 B：验证命令

```bash
mvn -B -ntp clean verify                        # 2026-11-03 实测 BUILD SUCCESS（17/17）
git rev-parse --short HEAD                      # 93656b8（master，工作区干净）
```

---

*报告生成：2026-11-03 · 审计基线：master@93656b8 · 新发现已登记 ISSUE-054 ~ ISSUE-060*
