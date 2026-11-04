# iter-66 · ISSUE-060 剩余低风险缺陷收口

- **日期**：2026-11-03
- **类型**：fix / docs（处理 `docs/code-quality-review-2026-11-03.md` 遗留的 N-8/N-9/N-10/N-12/N-14）
- **触发**：iter-65 收口 ISSUE-060 的 N-7/N-13/N-11 后，剩余 5 项待重构或文档化，本轮逐项落地并关闭 ISSUE-060。

## 1. 问题

| # | 问题 | 证据 |
| --- | --- | --- |
| N-14 | 窗口结束 `buckets.remove` 后，已持有旧桶引用的并发 `record` 仍递增计数，该计数随桶丢弃 | `Aggregator.record` 的 `computeIfAbsent` + 桶锁与 `flush` 的 `remove` 非互斥 |
| N-12 | `tryAcquire` 清理后返回空 deque，空对象可能常驻；`evictExpired()` 无调用方 | `MemoryRateLimiter.tryAcquire` / `evictExpired` |
| N-10 | RabbitMQ 退避重试依赖 `notifyx.async.enabled=false`，无启动校验，异步派发时 DLX 拓扑静默失效 | `RabbitMqEnvelopeConsumer` 失败分支只在同步抛异常时触发 |
| N-8 | 队列软上限的「读长度 → 找最低 → 移除」非原子，满队扫描 O(n) | `PriorityNotifyExecutor.execute` |
| N-9 | Kafka `publish` 同步 `.get(deliveryTimeout)` 阻塞调用线程，命名未明示 | `KafkaNotifyBus.publish` |

## 2. 方案

- **N-14**：`record` 改为 `buckets.compute(key, ...)`，把「取桶 + 计数递增」放进 `ConcurrentHashMap`
  的原子操作，与 `flush` 的 `remove` 对同 key 串行化。窗口结束后的并发事件落入新桶，
  不再丢失；类 Javadoc 同步说明。
- **N-12**：`compute` 在清理后为空时返回 `null`，空 deque 不常驻 map；`evictExpired` Javadoc
  明确「不内置调度、key 数量由场景/用户维度界定、由运维面按需调用」。
- **N-10**：`RabbitMqEnvelopeConsumer` 构造注入 `NotifyxProperties`，`retry-enabled=true` 且
  `notifyx.async.enabled=true` 时输出 WARN，说明失败消息会被 ACK、DLX 重试静默失效；
  README 与 design §11.4 补前置条件。
- **N-8**：保持实现不变（软上限非硬约束、4096 容量下扫描可接受），在类 Javadoc 写明并发语义
  与「上调到 10 万级前需改为增量维护最低优先级索引」的约束。
- **N-9**：`KafkaNotifyBus` 类 Javadoc、design §11.2、README MQ 模式三条位置明示
  「同步等待 broker 确认、最长 `delivery-timeout`（默认 10s）、producer-only 的确定性取舍」。

## 3. 验证

| 测试 | 覆盖 |
| --- | --- |
| `AggregatorTest.shouldNotLoseCountWhenFlushRacesWithRecord` | 4 线程 × 200 条并发 `record`/`flushAll`，全部摘要计数之和守恒 |
| `RabbitMqEnvelopeConsumerTest.shouldConsumeWhenAsyncDispatchWithRetry` | 异步 + 重试组合仅告警、消费行为不变 |

模块级：`mvn -B -ntp -pl notifyx-core,notifyx-spring,notifyx-consumer,notifyx-bus-kafka -am test`
指定用例 BUILD SUCCESS（core 11、spring 8、consumer 6）。

全量：`mvn -B -ntp clean verify` → **BUILD SUCCESS**，17/17 模块，
379 用例（366 执行 + 13 live 跳过），0 failure / 0 error，Spotless/Checkstyle/JaCoCo 全绿。

## 4. 兼容性

- 全部为内部实现与文档调整，无公共 API / 配置语义变更；
- N-14 修复使窗口边界计数守恒（原先最多丢 1 条/窗口），摘要条数可能略增，符合设计语义；
- N-10 仅新增启动 WARN，不阻断启动。

## 5. 文档

- `README.md`：MQ 模式同步发布语义、consumer 重试前置条件；
- `docs/design.md` §11.2 / §11.4；
- `.memory/issues.md` ISSUE-060 CLOSED；`.memory/STATE.md` 新增 §18。

## 6. 未纳入

- ISSUE-039（`dedupe.default-window` 与 `defaults.silent-window` 语义冲突）仍需用户拍板；
- A02/A03/A04/A07 等失败记录粒度与 NO_ROUTE 语义仍待决策。
