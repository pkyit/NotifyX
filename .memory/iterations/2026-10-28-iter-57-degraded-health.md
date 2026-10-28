# iter-57 · Redis 降级状态进入健康检查与诊断表

- **日期**：2026-10-28
- **分支**：`bugfix/iter-57-degraded-health`
- **类型**：fix（可观测性 + 公共 SPI）
- **触发**：22:00 夜间巡检；审计 fallback 包装的降级状态。

## 1. 问题

`FallbackDedupeStore` / `FallbackRateLimiter` 已有 `isDegraded()`，但：

- `NotifyxHealthIndicator.isMemory()` 只看实现类名；
- `NotifyxDiagnostics.capabilityTier()` 只按类名判断内存/T1。

Redis 故障降级到内存后：

- 健康状态仍可能是 `UP`/仅按内存判断的 `DEGRADED`，不会说明“Redis 降级中”；
- 诊断表仍显示 `T1+（外部存储）`，与实际能力不符。

## 2. 修复

- 新增 core SPI `Degradable`（`boolean isDegraded()`，线程安全、不抛异常）；
- `FallbackDedupeStore` / `FallbackRateLimiter` 实现该接口（已有方法加 `@Override`）；
- `NotifyxHealthIndicator`：
  - `isDegraded(spi)` 检测降级包装；
  - 降级时 `DEGRADED`，detail `degradedReason=redis fallback active: using in-memory dedupe/rate limiter`；
- `NotifyxDiagnostics`：
  - SPI 行输出 `⚠ Redis 降级中，当前使用内存实现`；
  - `capabilityTier()` 在降级时降为 `T0（单机内存）`。

## 3. 验证

新增：

- `NotifyxHealthIndicatorTest.shouldReportDegradedWhenStoreFallsBack`：mock `DedupeStore + Degradable`，`isDegraded=true` → DEGRADED + reason；
- `NotifyxDiagnosticsTest.shouldShowDegradedRedisStore`：诊断表含降级提示且 `tier=T0`。

`NotifyxDiagnosticsTest` 10/10、`NotifyxHealthIndicatorTest` 9/9。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，321 用例（308 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 新增 `Degradable` public SPI，不破坏现有实现；
- 健康状态在“Redis 已降级”时从 UP/DEGRADED 变为明确的 DEGRADED；K8s readiness 仍为 UP；
- 诊断表增加提示行，不改变装配行为。

## 5. 未纳入

- 降级持续时间/次数指标；
- ISSUE-039、A02/A03/A04、A06 完整方案、A14；
- 1.0.0 已发布，本轮为 1.0.1 候选。

## 6. 回滚

单提交回滚；无 schema 变更。
