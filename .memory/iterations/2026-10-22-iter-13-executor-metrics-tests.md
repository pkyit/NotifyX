# iter-13 · 优先级队列语义与 Micrometer 指标测试

- **日期**：2026-10-22
- **分支**：`test/executor-metrics` → `master`
- **类型**：test（ADR-008 / §9.11 / §15.1）
- **关联**：ISSUE-013（大幅推进）

## 1. 背景

`PriorityNotifyExecutor` 是 ADR-008 的核心实现（drop-lowest、绝不 CallerRuns），
`MicrometerNotifyMetrics` 是 §15.1 的指标出口——两者此前都没有任何测试。
队列语义一旦写错，表现是「通知风暴时静默丢最该发的那条」，极难发现。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 优先级执行器零测试 | `notifyx-spring/src/test` 只有 aspect/event 测试 | 新增 5 个用例，用 latch 构造「worker 阻塞 + 队列满」的确定性场景 |
| 2 | 队列满的两种行为没有断言 | 代码区分「替换最低」与「丢弃新任务」 | 分别验证 `replaced-by-higher-priority` 与 `queue-full` |
| 3 | 关闭后提交任务的行为未验证 | execute 捕获 `RejectedExecutionException` | 断言不抛异常且计入 `executor-rejected` |
| 4 | 指标名/标签基数无人守 | §15.1 明确禁止 receiver/bizId/eventId 标签 | 用 `SimpleMeterRegistry` 断言指标名统一 `notifyx.` 前缀、标签不含高基数字段 |
| 5 | 独立 `-pl` 构建时本地仓库 core jar 过旧导致编译失败 | `mvn -pl autoconfigure verify` 报 Aggregator/EscalationPolicy 找不到 | 用 `-am` 或先 install；已记录到操作注意事项 |

## 3. 改动点

**新增 `PriorityNotifyExecutorTest`（5 个用例）**：

| 用例 | 覆盖 |
| --- | --- |
| 优先级顺序 | worker 阻塞后入队 INFO/WARN/CRITICAL，释放后按 CRITICAL→WARN→INFO 执行 |
| 高优先级替换 | capacity=2 时 CRITICAL 挤掉最旧的 WARN，`replaced-by-higher-priority` 计数 |
| 同级丢弃新任务 | capacity=1 且同级时不替换，`queue-full` 计数 |
| 关闭后拒绝 | destroy 后 execute 不抛，`executor-rejected` 计数 |
| 水位可读 | `capacity()` / `queueSize()` |

**新增 `MicrometerNotifyMetricsTest`（4 个用例）**：

- `notifyx.event.received{scene,level,source}` 计数；
- `notifyx.send.total{status=success/unknown}` 与 `notifyx.send.duration`（零耗时不计）；
- `notifyx.queue.discarded{reason,level}`、`notifyx.filter.rejected{filter,scene}`；
- 所有指标名 `notifyx.` 前缀，标签不含 `receiver/bizId/eventId`。

**POM 门禁**：spring `0.60 → 0.90`（实测 92.2%）；autoconfigure `0.40 → 0.60`（实测 62.1%）。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，12 模块，160 tests，0 failures
  core 80 / spring 17 / autoconfigure 25 / email 6 / logback 20 / webhook 12
→ JaCoCo：spring 92.2%（门禁 90%）、autoconfigure 62.1%（门禁 60%）、core 约 72%（门禁 65%）
→ Spotless 0 / Checkstyle 0
```

## 5. 回滚

删除两个测试类、还原 POM 门禁即可；无生产代码改动。

## 6. 下一步

- core 覆盖率 72% → 90%（`NotifyPipeline` 重试/熔断分支、`NotifyEvent` 边界）。
- `NotifyEnvelope` + 自动补偿仍是最大的功能缺口（ISSUE-015）。
