# iter-49 · 渠道熔断状态指标 `notifyx.circuit.state`

- **日期**：2026-10-27
- **分支**：`bugfix/iter-49-circuit-metric` → `master`
- **类型**：feat/fix（可观测性 A15 + 回归测试）
- **触发**：23:00 夜间巡检；补设计书 §15.1 的渠道熔断状态指标。

## 1. 问题

`CircuitBreaker` 有 `state()`，但 `NotifyMetrics` 没有出口；设计书 §15.1 要求能看到
渠道熔断状态，运维只能从 `CIRCUIT_OPEN` 审计间接推断。A15 的“队列水位 + 熔断状态”
只补了前者。

## 2. 修复

- `NotifyMetrics` 增加 default `circuitState(String channel, String state)`；
- `MicrometerNotifyMetrics` 按 channel 惰性注册 `Gauge`（`notifyx.circuit.state`），
  数值映射 CLOSED=0、HALF_OPEN=1、OPEN=2；未知状态按 0；
- `NotifyPipeline.sendOne` 在构造 breaker、`allowRequest()` 后、发送循环结束后上报当前状态；
- 指标带 `channel` 低基数标签，不引入 receiver/bizId。

## 3. 验证

新增 `MicrometerNotifyMetricsTest.shouldRecordCircuitState`：

- `OPEN` → 2；
- `HALF_OPEN` → 1；
- `CLOSED` → 0。

`MicrometerNotifyMetricsTest` 8/8 通过。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，305 用例（292 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- `NotifyMetrics` 只新增 default 方法；
- 默认指标名 `notifyx.circuit.state`，跟随 `metrics.prefix`；
- 每个渠道一条 gauge，channel 标签基数受渠道数量约束。

## 5. 未纳入

- `mask.before-send`、失败落盘 envelope 脱敏；
- A02/A03/A04/A06/A14；
- ISSUE-039 仍需决策；
- 1.0.0 已发布，本轮为 1.0.1 候选。

## 6. 回滚

单提交回滚；无 schema/配置变化。
