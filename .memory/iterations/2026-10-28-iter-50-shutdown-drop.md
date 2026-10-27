# iter-50 · 关闭超时丢弃任务可见性

- **日期**：2026-10-28
- **分支**：`bugfix/iter-50-shutdown-drop` → `master`
- **类型**：fix（A06 可观测性 + 回归测试）
- **触发**：00:00 夜间巡检；处理关闭窗口静默丢任务。

## 1. 问题

`PriorityNotifyExecutor.destroy()` 在 `shutdownAwait` 超时后调用 `shutdownNow()`，
原来只打印一条 `N tasks dropped from queue`：

```java
int remaining = pool.shutdownNow().size();
log.warn("notifyx shutdown timeout, {} tasks dropped from queue", remaining);
```

单个任务的 level / taskKey 全部丢失，`queue.discarded` 指标也不增加；
运维无法知道关闭窗口具体丢了哪些事件。

## 2. 修复

- 保留 `shutdownNow()` 返回的 `List<Runnable>`；
- 对每个 `PriorityTask`：
  - `metrics.queueDiscarded("shutdown", level)`；
  - 前 20 条打印 `level + taskKey`，避免一次刷几千行日志；
- 最后仍打印总数与 `queue.size()` 归零。

A06 的完整方案（任务携带 event/scene/dedupeKey 并写入 FailureStore）仍需要改执行器任务模型，
本轮只做“可见性”，不改变丢弃语义。

## 3. 验证

新增 `PriorityNotifyExecutorTest.shouldReportShutdownDroppedTasks`：

- 1 个 worker 被阻塞，队列中留 1 个任务；
- `shutdownAwait=1ms` 强制超时；
- 断言 `metrics.discardedReasons` 包含 `shutdown`。

`PriorityNotifyExecutorTest` 7/7 通过。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，306 用例（293 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 关闭超时仍会丢弃未完成任务（语义不变），但每个任务有指标、前 20 条有日志；
- 无 public API 变更。

## 5. 未纳入

- 队列满 drop 的 FailureStore 落盘（需要任务携带事件上下文）；
- A02/A03/A04、`mask.before-send`、envelope 落盘脱敏、A14；
- ISSUE-039 仍需决策。

## 6. 回滚

单提交回滚；无 schema/配置变化。
