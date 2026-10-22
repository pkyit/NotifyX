# NotifyX P0/P1 SDK 架构与代码质量审计（2026-10-22）
> 审计对象：`master@b4fb473`，交付范围收敛为设计书 P0/P1 的 SDK 部分。  
> 审计方式：逐模块阅读主实现与关键测试，结合 Maven 质量门禁、设计书契约和框架运行语义做架构级审查。  
> 结论先行：**工程完成度和质量门禁表现良好，但当前不建议直接对外宣称“生产可用”**。主要风险不在风格或覆盖率，而在**可靠性语义、配置契约、失败补偿边界和安全脱敏**四类问题；其中若进入公开发布，Email 认证与失败补偿属于高优先级阻断项。
---
## 1. 总体评价
| 维度 | 评价 | 说明 |
| --- | --- | --- |
| 架构分层 | ★★★★☆ | 模块边界总体清晰，core 零 Spring、渠道独立、starter 空聚合的方向正确 |
| 可靠性语义 | ★★☆☆☆ | “成功/失败/受理/延迟/部分成功”被压缩成 boolean，导致多处误判 |
| 安全边界 | ★★☆☆☆ | 脱敏接口存在，但关键调用链没有真正用起来；敏感数据可进入审计 / 补偿 / Webhook |
| 配置契约 | ★★☆☆☆ | 大量属性绑定后不生效，容易形成“配置看起来可调、运行时无效”的信任问题 |
| 测试与门禁 | ★★★★☆ | 186 个测试、Spotless / Checkstyle / JaCoCo 全绿，基础质量好 |
| 生产可发布性 | ★★★☆☆ | 架架骨架可发布为 SNAPSHOT；正式 1.0 前应先修复高优先级问题 |
**总体评分：76 / 100（良好偏风险）**。  
这不是“代码写得不认真”，而是框架在从“功能完成”走向“可信基础设施”时，需要把隐式语义显式化。通知框架的最低要求不是把消息发出去，而是**在没发出去、只发出一部分、重复发送、被限流丢弃、关闭中断时，系统自己知道发生了什么，并能解释给运维**。
---
## 2. 关键发现汇总
| 优先级 | 问题 | 影响 | 主要证据 |
| --- | --- | --- | --- |
| P0 | Email 配置了 username/password，但 SMTP Authenticator 为 null | 常规 SMTP 认证场景必失败 | `EmailChannel.java:45-48, 89-105` |
| P0 | FailedRecord 用 eventId 当 recordId，多渠道 / 多接收人失败互相覆盖 | 补偿记录丢失，无法恢复 | `FailedRecord.java:10`, `LocalFileFailureStore.java:103-107` |
| P0 | 部分渠道失败后全局释放 dedupeKey | 已成功渠道会被重复发送 | `NotifyPipeline.java:224-225` |
| P0 | 无可用渠道被当成投递成功 | 配置错误被隐藏，补偿器会标记 SENT | `NotifyPipeline.java:208-211` |
| P0 | 意外 RuntimeException / null SendResult 绕过审计、落盘和熔断 | 故障不可观测，熔断可能卡死 | `NotifyPipeline.java:258-292` |
| P0 | `mask.before-audit` 只脱敏 receiver，params / bizId / errorMsg 未脱敏 | 敏感数据可进入审计与补偿存储 | `NotifyPipeline.java:339-355` |
| P1 | 队列丢弃没有落盘，且缺 scene / dedupeKey | 违背“丢弃必须可见、可选补偿”的架构承诺 | `PriorityNotifyExecutor.java:72-92` |
| P1 | Webhook payload 对嵌套 Map/List 生成非法 JSON | 契约破坏，接收方解析失败 | `WebhookChannel.java:123-147` |
| P1 | 用户自定义 headers 可覆盖 NotifyX 保留头 | 可伪造 / 破坏签名与幂等语义 | `WebhookChannel.java:68-83` |
| P1 | `@NotifyOn` 的 bizId / context 用 `evaluateBefore`，拿不到 `#r/#e` | 注解核心表达式语义错误 | `NotifyxAspect.java:117-147` |
| P1 | Quiet hours `defer=true` 实际会被拒绝 | 免打扰延后能力不可用 | `NotifyPipeline.java:367-374` |
| P1 | 自定义 `EventFilter` Bean 未被自动装配 | 设计承诺的扩展点不可插入 | `NotifyxAutoConfiguration.java:321-367, 485-516` |
| P1 | 多个配置项绑定后不生效 | 配置契约失真，用户难以排查 | `NotifyxProperties.java` 与实际调用点 |
| P2 | 升级窗口算法可能永不复位 | 连续高频事件无法按预期升级 | `EscalationPolicy.java:81-101` |
| P2 | 缺 queue.size 与 circuit state 指标 | 无法监控队列积压和渠道熔断 | `NotifyMetrics.java`, `MicrometerNotifyMetrics.java` |
| P2 | 内存去重容量满时 fail-closed | 新告警被误判为 duplicate 而静默消失 | `MemoryDedupeStore.java:44-61` |
---
## 3. 架构级问题详析
### A01. 投递结果模型过度简化，是多个问题的共同根源
**位置**：`NotifyPipeline.handle()`、`dispatch()`、`FailureCompensationRunner.deliver()`。
当前 `handle()` 返回 boolean：
- `true`：成功，或 DEFER，或“没有任何渠道也被视为成功”；
- `false`：拒绝、异常、部分渠道失败。
这会把四种完全不同的系统状态混在一起。对通知框架而言，正确模型应该是显式的 `DeliveryOutcome`：
```text
DELIVERED          至少一个目标成功，且没有失败目标
PARTIAL_DELIVERED  部分成功、部分失败
REJECTED           被策略明确拒绝（含原因）
DEFERRED           已受理，等待后续时间
NO_ROUTE           无渠道 / 无接收人 / 配置不可用
FAILED             全部失败或管线内部错误
```
**推荐修复方向**：
1. `NotifyPipeline.handle()` 返回结构化结果，不返回 boolean。
2. `DefaultNotifyKit.deliverSync()` 暴露该结果；`NotifyKit` 增加 `deliverSync(event)` 或独立 `NotifyDeliveryService`。
3. 补偿器只根据 `PARTIAL_DELIVERED / FAILED / NO_ROUTE` 决定状态。
4. 每个结果必须写审计与指标，不允许“无渠道即成功且无审计”。
这个重构会同时解决：无渠道误判、defer 误判、部分成功误判、自定义 NotifyKit 补偿语义依赖具体实现的问题。
---
### A02. 失败补偿的记录粒度与投递粒度不一致
**位置**：
- `FailedRecord.recordId` 注释为 eventId；
- `LocalFileFailureStore` 以 `<recordId>.json` 为文件名；
- `InMemoryFailureStore.updateStatus/requeue` 只找第一个相同 recordId。
一次事件可能 fan-out 到：
- 多个渠道；
- 每个渠道多个接收人。
当前 `persistFailure()` 为每个失败目标调用一次，但 `recordId` 都是 eventId。结果是：
1. local-file 中后写覆盖先写；
2. in-memory 中虽然能保存多条，但状态更新只命中第一条；
3. 补偿任务无法知道失败的是哪个 channel + receiver；
4. 一个目标失败、另一个目标成功时，无法只补发失败目标。
**推荐模型**：
```text
recordId = eventId + ":" + channel + ":" + receiverOrBatch
idempotencyKey = 原事件幂等键
eventRef = eventId
```
补偿重投时应携带 `targetChannel` / `targetReceiver`，至少支持“只补发失败目标”。如果 1.0 先实现“整事件重投”，也必须明确这是 at-least-once，可能重复成功渠道，并通过幂等头让接收方处理。
---
### A03. 失败释放 dedupeKey 的策略会造成重复发送
**位置**：`NotifyPipeline.dispatch()`：
```java
if (failed && dedupe != null && dedupe.store() != null) {
    dedupe.store().release(dedupeKey);
}
```
设计意图是“失败后释放槽位，避免后续事件被去重挡住”。但在多渠道场景下：
- email 成功；
- webhook 失败；
- dedupeKey 被释放；
- 下一个相同事件进入后，email 又会发送一次。
这会把“失败恢复”变成“成功渠道重复轰炸”。对告警系统而言，这不仅是重复消息，还可能带来短信 / IM 的真实成本。
**推荐修复**：
1. dedupe lease 按 `event + channel + receiver` 维度持有；
2. 只有失败目标释放对应 lease；
3. 成功目标保持静默窗口；
4. 若暂时只能事件级 lease，则部分成功时不要释放，改为将失败目标写入 FailureStore，由补偿器定向重投。
---
### A04. 无渠道、无接收人被处理成成功
**位置**：`NotifyPipeline.dispatch()`：
```java
if (channels.isEmpty()) {
    return true;
}
```
`handle()` 又返回 `!failed`，所以无渠道场景返回 true。后果：
1. 用户拼错渠道名，系统看起来“投递成功”；
2. 健康检查可能是 UP，审计没有 no-route 记录；
3. 补偿器把该记录标记为 SENT。
这是框架最危险的错误类型：**把配置错误伪装成业务成功**。
**推荐**：
- 启动时 fail-fast 校验 scene.channels 是否有可用渠道；
- 运行时输出 `NO_ROUTE`；
- 审计状态新增 `NO_ROUTE`；
- 补偿器将 `NO_ROUTE` 标记为 DEAD 或 PENDING，而不是 SENT。
同理，`receivers.isEmpty()` 时直接以 null receiver 调用渠道，Email 会抛 NPE。应显式判断“该渠道是否需要 receiver”，不需要 receiver 的渠道可以继续，需要 receiver 的渠道应记录失败。
---
### A05. 意外异常绕过熔断、审计与失败落盘
**位置**：`NotifyPipeline.sendOne()` 只捕获 `ChannelException`；`handle()` 只在外层捕获 RuntimeException。
以下情况会直接落入外层 catch：
- 渠道抛 NullPointerException / IllegalStateException；
- 模板渲染抛 `TemplateRenderException`；
- 渠道返回 null；
- URI 解析 / HMAC 初始化抛非 ChannelException。
后果：
- 本次发送没有审计；
- 没有失败落盘；
- 熔断器不记录失败；
- 半开状态的 `probeInFlight` 可能永远为 true，后续请求全部被拒绝；
- `onError` 不一定被调用。
**推荐**：
```java
try {
    result = channel.send(context);
    if (result == null) {
        result = SendResult.retryable("channel returned null", ...);
    }
    breaker.recordSuccess();
} catch (ChannelException ex) {
    ...
} catch (RuntimeException ex) {
    breaker.recordFailure();
    result = SendResult.retryable(...);
}
```
模板渲染也应进入统一失败路径。中断时必须退出重试循环，而不是继续空转。
---
### A06. 队列丢弃只计数，不进入失败链路
**位置**：`PriorityNotifyExecutor.execute()`。
当前队列满时：
- 记 `queue.discarded`；
- 打 WARN；
- 直接 return。
但设计书明确要求丢弃任务至少包含 scene 与 dedupeKey，并可选落盘。当前执行器只有 level 和 taskKey（eventId），拿不到事件上下文，也不可能落盘。关闭超时后 `shutdownNow()` 返回的任务也只是统计数量，最终静默丢失。
**推荐**：
1. 队列元素从 `Runnable` 升级为 `NotifyTask`，携带 `NotifyEvent`、scene、dedupeKey、level。
2. 执行器接收 `DropHandler`，把 drop 事件交给管线 / FailureStore / 指标。
3. 关闭超时时遍历剩余任务，逐一调用 drop handler。
4. drop 指标必须带低基数 reason / level，日志带 scene / dedupeKey / eventId。
否则“有界队列”只是保护了业务线程，却没有守住告警可见性。
---
### A07. 脱敏契约与实现严重不一致
**设计契约**：
- `mask.before-audit=true` 表示落库前脱敏；
- `mask.before-send=false` 默认关闭；
- `maskParams` 应按 key 递归脱敏。
**实际实现**：
1. `NotifyPipeline.audit()` 只对 receiver 调 `maskText()`；
2. `AuditRecord.bizId`、`errorMsg` 未脱敏；
3. `DefaultMasker.maskParams()` 没有递归处理嵌套 Map / List；
4. `mask.before-send` 属性没有任何调用点；
5. Webhook 会把完整 params 发给外部系统，这符合 before-send 默认关闭，但必须在配置契约中明确。
6. 失败落盘的 envelope 也保存完整 params，任何能读文件 / 存储的人都能看到敏感数据。
**推荐**：
- 审计前构造 `AuditSnapshot`，对 bizId、receiver、errorMsg、params、rendered title/content 明确应用 mask 策略；
- `maskParams` 递归处理嵌套结构；
- envelope 持久化前增加独立 `mask-before-persist` 策略，至少支持按 key 脱敏；
- 渠道发送前不默认脱敏，但开启 `before-send` 时应只对内容脱敏，接收人必须保持可用；
- 所有 mask 决策写入 ADR，避免“安全开关”变成无效配置。
---
### A08. Email 渠道的认证实现不可用
`EmailChannel` 构造 Session 时：
```java
Session.getInstance(mailProperties(properties), null)
```
第二个参数是 Authenticator，当前为 null。虽然配置了 username 时设置了 `mail.smtp.auth=true`，但 password 从未使用。`Transport.send(message)` 无法拿到密码，常规 SMTP 认证必然失败。
**推荐**：
```java
Session session = Session.getInstance(props, new Authenticator() {
    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(properties.getUsername(), properties.getPassword());
    }
});
```
同时：
- 用户名或密码为空时启动 fail-fast；
- 不建议把 password 长期保存在可变 Properties / String 中，至少限制日志输出；
- 增加真实 / mock Transport 认证测试，现有 6 个 Email 测试没有覆盖这个缺陷。
---
### A09. Webhook 契约存在三类问题
1. **嵌套结构不是合法 JSON**  
   `jsonMap()` 对所有 value 执行 `String.valueOf()`。如果 params 中有 `{a=1}` 或 List，输出会变成字符串，而不是 JSON object / array。NotifyEvent 明确支持嵌套 Map / Collection，因此这是可复现的契约破坏。
2. **保留头可被覆盖**  
   用户 headers 在签名头之后设置，可以覆盖 `X-Notifyx-Signature`、`X-Notifyx-Idempotency-Key`、`Content-Type`。应在最后重新设置框架保留头，或拒绝用户配置保留头。
3. **异常分类不完整**  
   `URI.create()` 抛 IllegalArgumentException，`sign()` 抛 IllegalStateException，都会绕过 ChannelException 体系。应统一转为 `ChannelProtocolException` / `ChannelAuthException`，让管线走审计、熔断和落盘。
---
### A10. Quiet hours defer 能力实际不可用
`QuietHoursFilter` 可以返回 `deferUntil`，但自动装配没有提供 `DeferredHandler`。`NotifyPipeline.defer()` 遇到 null handler 时直接 reject。
因此配置：
```yaml
notifyx.quiet-hours.defer: true
```
实际效果是“夜间丢弃”，不是“夜间延后”。这与配置名和设计语义冲突。
**推荐**：
- 1.0 如果不做延迟队列，应把配置改成 `drop` / `drop-with-summary`；
- 若保留 `defer`，需要内置有界延迟队列 / 调度器；
- 补偿器必须能区分 `DEFERRED`，不能当成已发送。
---
### A11. 用户 EventFilter 扩展点没有被自动装配
设计书承诺用户可以插 900+ 自定义 Filter。当前 `buildFilters()` 只添加内置 Filter，`notifyxNotifyKit()` 方法参数也没有 `ObjectProvider<EventFilter>`。
用户定义 `EventFilter` Bean 后，除非自己重写整个 NotifyKit，否则不会生效。这违背了 SPI 的核心价值。
**推荐**：
```java
public NotifyKit notifyxNotifyKit(..., ObjectProvider<EventFilter> customFilters) {
    List<EventFilter> filters = buildFilters(...);
    customFilters.orderedStream().forEach(filters::add);
    filters.sort(Comparator.comparingInt(EventFilter::order));
    ...
}
```
同时用测试覆盖内置 Filter 与自定义 Filter 的顺序，明确 900+ 保留区间。
---
### A12. 配置契约存在“绑定但不生效”的属性
这些属性在 `NotifyxProperties` 中存在，但运行时没有语义：
| 配置 | 当前问题 |
| --- | --- |
| `notifyx.dedupe.store` | 未使用，始终内置内存实现 |
| `notifyx.dedupe.default-window` | 仅诊断表打印，实际窗口来自 scene / event |
| `notifyx.rate-limit.store` | 未使用 |
| `notifyx.templates.engine` | 未参与模板引擎选择 |
| `notifyx.metrics.enabled` | 未使用 |
| `notifyx.metrics.prefix` | Micrometer 名称硬编码为 `notifyx.` |
| `notifyx.mask.enabled` | 未使用 |
| `notifyx.mask.before-send` | 未使用 |
| `notifyx.retry.enabled` | 未使用，只读取 attempts / delay |
| `notifyx.env` / `notifyx.tenant` | 未渲染进模板或审计 |
框架的配置元数据是公开 API。用户设置了属性却没有效果，是最消耗信任的问题之一。
**推荐**：
1. 对每个属性建立“绑定 → 使用点 → 测试”的追踪表。
2. 1.0 未实现的属性直接删除或标注 `@Deprecated` / 未实现，不要出现在配置提示中。
3. `store`、`engine`、`metrics.enabled`、`retry.enabled`、`mask.enabled` 必须真实生效。
4. 用 `@Validated` + JSR-303 或启动校验器统一 fail-fast。
---
### A13. 升级策略窗口算法存在逻辑漏洞
`EscalationPolicy.record()` 只在“上次事件距当前超过 within”时 reset。它还要求：
```java
now - state.firstAt <= within
```
示例：`within=10m`，事件间隔 9 分钟，阈值为 3：
- t0 / t9 / t18 到来；
- t18 时 `now - firstAt = 18m > 10m`，永远无法升级；
- t27 时与上次间隔 9m，仍不触发 reset；
- 状态永远无法升级。
正确做法是在每次评估时检查 `now - firstAt > within`，将窗口滚动到当前事件：
```java
if (state.firstAt == 0 || now - state.firstAt > rule.within().toMillis()) {
    state.reset(now);
}
```
再判断连续计数。否则“窗口内连续 N 次”会退化成不可达状态。
---
### A14. 去重存储容量满时 fail-closed，可能吞掉新告警
`MemoryDedupeStore` 达到容量上限且没有过期项时，`tryAcquire` 返回 false。`DedupeFilter` 将其解释为 duplicate，因此新事件会被当成重复而静默丢弃。
这对告警系统是错误倾向：
- 重复发送是可接受的噪声问题；
- 新告警消失是不可接受的可靠性问题。
**推荐**：
- 存储满时 fail-open，让事件通过；
- 记录 `dedupe-store-overflow` 指标与 WARN；
- 可选 LRU 淘汰最旧 key，但必须计数；
- 健康状态显示降级，而不是静默吞事件。
---
### A15. 观测性缺口：缺队列水位与熔断状态
设计书要求：
- `notifyx.queue.size`
- `notifyx.channel.circuit{channel,state}`
当前 `NotifyMetrics` 只有：
- event received
- send result
- queue discarded
- filter rejected
缺少最关键的运行时状态指标。通知系统最常见的生产问题是“队列积压”和“渠道熔断”，没有这两个 gauge，Prometheus 无法闭环监控 NotifyX 自身健康。
**推荐**：
1. 在 Micrometer 中注册 queue gauge / capacity gauge。
2. 为每个 channel 注册 circuit state gauge（closed=0, half_open=1, open=2）。
3. 补偿任务增加 pending / sending / dead / success 指标。
4. `LoggingAuditRecorder` 可输出 JSON，便于采集。
---
### A16. 健康状态的架构语义需要重新定义
当前测试要求内存去重 / 限流默认产生 `DEGRADED`。从架构上看，这个设计值得挑战：
1. P0/P1 明确承诺零依赖可运行，内存 store 是合法的 T0 能力，不是故障。
2. 如果默认安装就 DEGRADED，所有零依赖用户开局即“降级”，健康语义被稀释。
3. `DEGRADED` 是 Spring Boot 自定义状态，若没有配置 `management.endpoint.health.status.order`， readiness / health 聚合行为并不总是等价于 UP。
4. 健康检查应回答“当前能否投递”，而不是“是否具备多实例能力”。
**推荐语义**：
- 有可用渠道、管线和执行器正常 → `UP`；
- 在 details 中输出 `tier=T0`、`dedupe=MemoryDedupeStore`、`rateLimiter=MemoryRateLimiter`、`multiInstanceLimitation=true`；
- 只有外部 store 配置为 Redis / JDBC 但不可用、或部分渠道探活 DOWN，才 `DEGRADED`；
- 全部渠道 DOWN → `DOWN`；
- `notifyx.enabled=false` → `OUT_OF_SERVICE`。
这需要新增一条 ADR / D-NNN，并同步修改设计书与测试。当前工作区中该测试实际通过（targeted run 6/6），用户贴出的失败可能来自其他分支或旧构建；但无论测试是否复现，**默认 DEGRADED 的设计本身需要复审**。
---
### A17. 生命周期与资源初始化有优化空间
1. `Aggregator` 构造时总是创建调度线程，即使 `enabled=false`。P0/P1 强调零依赖轻量运行，禁用能力不应常驻线程。
2. `FailureCompensationRunner` 同样在 disabled 时创建 scheduler。
3. 两个组件都应该懒初始化，或 enabled=false 时完全不装配。
4. `Aggregator.close()` 只 flush 一轮快照，若关闭期间仍有新桶写入，可能丢失。
5. 补偿 runner 与 NotifyxLifecycle 的关闭顺序没有统一编排，可能存在渠道已关闭但补偿线程仍在投递的窗口。
推荐将后台资源建模成统一的 `NotifyxBackgroundService`，由 lifecycle 按顺序 start / stop。
---
### A18. 其他值得记录的问题
#### Webhook / JSON / 指纹
- `Fingerprints.canonicalParams()` 使用未转义的 `=`、`&`、`{}`，不同参数结构可能碰撞；建议 JSON canonical form 或长度前缀编码。
- `Ulid` 注释承诺“同一毫秒原子自增保证单调”，实现实际是纯随机，行为与注释不一致。
- `WebhookChannel` 的 `allowedHosts` 只比较 host，不限制 scheme；如作为安全边界，应明确只允许 HTTPS 或支持策略配置。
- Webhook 响应体直接进入异常信息，可能携带敏感内容，应截断并脱敏。
#### Spring Event / Logback
- `NotifyxEventBridge.sourceOf()` 调用任意业务对象 `toString()`，可能暴露敏感字段或执行昂贵逻辑；默认应只输出类名，结构化 payload 显式开启。
- `NotifyxAppender` 的防递归依赖 logger 前缀，但 `LogChannel.logger-name` 可配置为任意值；若配置成业务 logger，可能重新打开递归风险。建议强制 `NOTIFYX_` 前缀或校验保留前缀。
- `NotifyxDiagnostics.isEnabled(channel)` 调用 `channel.isEnabled(null)`，违反 NotifyContext 契约；实现依赖 ctx 时会 NPE，并被误判为 enabled。
#### 优先级队列
- `ThreadPoolExecutor + PriorityBlockingQueue` 导致 maxPoolSize 基本无效，代码注释已承认“实际等于常驻数”，但配置仍叫 maxPoolSize，容易误导。
- drop-lowest 的“扫描 + remove + execute”不是原子操作，并发高峰下可能超过软上限或重复替换。
- 更合理的实现是自定义有界优先队列 + 语义明确的 offer/drop 策略，或使用信号量控制容量。
---
## 4. 修复路线建议
### 第一阶段：发布前必须完成（P0）
1. 修复 Email Authenticator，并补认证测试。
2. 重构 `DeliveryOutcome`，解决 no-route / defer / partial-success 误判。
3. 修复 FailureRecord 主键与多目标覆盖问题。
4. 修复 dedupe release 与部分成功的重复发送问题。
5. 让渠道意外异常 / null result 进入统一失败路径。
6. 落实 audit 前脱敏，至少覆盖 receiver、bizId、params、errorMsg。
### 第二阶段：1.0 稳定性（P1）
1. 队列 drop 事件携带完整上下文，并接入指标 / 日志 / 可选 FailureStore。
2. 修复 Webhook JSON 嵌套结构与保留头覆盖。
3. 修复 `@NotifyOn` 表达式求值上下文。
4. 自动装配自定义 `EventFilter` 并排序。
5. 清理或实现所有无效配置项，补配置契约测试。
6. 实现或移除 quiet-hours defer 语义。
7. 增加配置 fail-fast 校验。
### 第三阶段：可观测与长期可维护（P2）
1. 增加 queue size / circuit state / compensation state 指标。
2. 重新定义 health 语义，T0 正常状态应为 UP + detail。
3. 修复 escalation 滑动窗口。
4. 优化后台线程懒初始化与关闭顺序。
5. 修复 fingerprint canonicalization 歧义。
6. 收敛 Logback / Event bridge 的敏感 toString 风险。
---
## 5. 对当前测试失败的判断
当前 `master@b4fb473` 上执行：
```bash
mvn -pl notifyx-spring-boot3-autoconfigure -am ^
  -Dtest=NotifyxHealthIndicatorTest ^
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```
结果为 **6 / 6 通过**，因此用户贴出的 `expected: DEGRADED but was: UP` 在当前工作区不可复现，可能来自：
- 未合并 / 其他分支代码；
- 本地 stale class；
- 不同模块构建顺序；
- 或对健康逻辑做过修改后未同步测试。
但从架构评审角度，**不应简单把代码改回 DEGRADED 来让测试变绿**。默认内存实现是否应该降级，是产品语义决策，需要先定 ADR，再改实现和测试。
---
## 6. 结论
NotifyX 的骨架是健康的：模块边界、不可变事件、责任链、质量门禁和测试基础都明显优于一般初创项目。  
但它还没有完全完成从“功能框架”到“可运维基础设施”的跨越。
当前最大的问题不是缺少 Redis、MQ 或 Admin，而是本地模式的可靠性语义不够严谨：
- **部分成功不是成功；**
- **无路由不是成功；**
- **队列丢弃不能无声；**
- **补偿不能覆盖失败记录；**
- **安全开关不能只是配置项；**
- **健康状态不能把默认能力当故障。**
建议将本审计中的 P0 项作为 iter-18 的第一目标，完成后再考虑对外发布 GA 或宣称生产可用。