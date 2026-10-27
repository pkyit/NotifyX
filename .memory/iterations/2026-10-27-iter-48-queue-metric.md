# iter-48 · 队列水位指标 `notifyx.queue.size`

- **日期**：2026-10-27
- **分支**：`bugfix/iter-48-queue-metric` → `master`
- **类型**：feat/fix（可观测性 A15 + 回归测试）
- **触发**：22:00 夜间巡检；补设计书 §15.1 要求的队列水位指标。

## 1. 问题

`PriorityNotifyExecutor` 有 `queueSize()`，但 `NotifyMetrics` 没有队列水位出口，
设计书 §15.1 的 `notifyx.queue.size` gauge 实际不存在。运维无法判断“持续 > 80% capacity”
的丢弃前兆，只能等 `queue.discarded` 增长。

## 2. 修复

- `NotifyMetrics` 增加 default 方法 `queueSize(int size)`，不破坏自定义实现；
- `MicrometerNotifyMetrics` 注册 `Gauge`（默认名 `notifyx.queue.size`，跟随前缀），
  内部 `AtomicInteger` 由 `queueSize()` 更新，负值归零；
- `PriorityNotifyExecutor.execute` 在入队/丢弃/拒绝后上报 `queue.size()`；
- `destroy` 结束时也上报一次，关闭后归零。

## 3. 验证

新增：

- `MicrometerNotifyMetricsTest.shouldRecordQueueSize`：7 → gauge=7，-1 → 0；
- `PriorityNotifyExecutorTest.shouldReportQueueSize`：任务入队后 metrics 收到水位。

`MicrometerNotifyMetricsTest` 7/7、`PriorityNotifyExecutorTest` 6/6。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，304 用例（291 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- `NotifyMetrics` 只新增 default 方法，现有实现无需修改；
- 默认指标名仍为 `notifyx.queue.size`，跟随 `metrics.prefix`；
- 每次入队多一次 `queue.size()` 调用（O(1)），可忽略。

## 5. 未纳入

- 渠道熔断状态 gauge（A15 的另一半）；
- A02/A03/A04/A06、`mask.before-send`、envelope 落盘脱敏、A14；
- ISSUE-039 仍需决策。

## 6. 回滚

单提交回滚；无 schema/配置变化。
