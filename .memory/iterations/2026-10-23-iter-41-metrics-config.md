# iter-41 · 指标配置生效修复（enabled / prefix）

- **日期**：2026-10-23
- **分支**：`bugfix/iter-41-metrics-config` → `master`
- **类型**：fix（配置契约 + 指标自动装配 + 回归测试）
- **触发**：23:00 夜间巡检；处理架构审计 A12 中「绑定但不生效」的 `notifyx.metrics.*`。

## 1. 问题

`NotifyxProperties.Metrics` 定义了 `enabled` 与 `prefix`，但运行时完全没有使用点：

- `MicrometerNotifyMetrics` 的所有指标名硬编码 `notifyx.` 前缀；
- `NotifyxMetricsAutoConfiguration` 只要 classpath 有 Micrometer + `MeterRegistry` 就装配，
  `notifyx.metrics.enabled=false` 不生效；
- 用户改 `notifyx.metrics.prefix=acme` 后指标名仍是 `notifyx.*`，属于“配置看起来可调、实际无效”。

## 2. 修复

- `MicrometerNotifyMetrics` 增加 `(MeterRegistry, String prefix)` 构造；默认构造继续使用
  `notifyx`，保持向后兼容；
- 前缀规范化：空/空白回退 `notifyx`，末尾 `.` 自动去掉；
- 所有指标名改为 `prefix + "." + suffix`；
- `NotifyxMetricsAutoConfiguration` 增加
  `@ConditionalOnProperty(prefix="notifyx.metrics", name="enabled", havingValue="true", matchIfMissing=true)`；
  关闭时核心自动装配的 `NotifyMetrics.noop()` 兜底；
- 自动配置显式 `@EnableConfigurationProperties(NotifyxProperties.class)`，保证在核心自动配置之前
  也能绑定 `prefix`。

## 3. 验证

新增：

- `MicrometerNotifyMetricsTest.shouldRespectCustomPrefix` → `acme.notifyx.event.received`；
- `MicrometerNotifyMetricsTest.shouldFallbackToDefaultPrefix` → 空白前缀回退 `notifyx`；
- `MetricsAutoConfigurationTest` 3 个用例：默认前缀、`prefix=acme`、`enabled=false` 回退 no-op。

`MetricsAutoConfigurationTest` 3/3，`MicrometerNotifyMetricsTest` 6/6。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，292 用例（279 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 默认指标名仍是 `notifyx.*`，现有看板不受影响；
- `notifyx.metrics.enabled=false` 现在真正关闭 Micrometer，回退 no-op；
- `prefix` 末尾点自动去掉，避免出现 `acme..event.received`；
- 无 public 方法签名删除；`MicrometerNotifyMetrics` 原有构造保留。

## 5. 未纳入

- A02/A03、A04、A06、A07、A14、A15；
- 其余 A12 属性（`dedupe.store`、`rate-limit.store`、`templates.engine` 等）仍待逐项处理；
- 信创 DDL、DLX/死信、consumer 多实例、Admin。

## 6. 回滚

单提交回滚；无 schema/配置破坏性变更。
