# iter-43 · `notifyx.retry.enabled` 生效修复

- **日期**：2026-10-24
- **分支**：`bugfix/iter-43-retry-enabled` → `master`
- **类型**：fix（配置契约 + 自动装配 + 回归测试）
- **触发**：01:00 夜间巡检；处理架构审计 A12 中「绑定但不生效」的 `notifyx.retry.enabled`。

## 1. 问题

`NotifyxProperties.Retry.enabled` 默认 true，但 `NotifyxAutoConfiguration` 构造
`RetryPolicy` 时只读取 `maxAttempts/baseDelay/maxDelay/jitter`，从不看 `enabled`。
用户设置：

```yaml
notifyx:
  retry:
    enabled: false
```

渠道失败仍按 `max-attempts` 重试，配置契约失真。

## 2. 修复

- `notifyx.retry.enabled=false` 时把全局最大尝试次数降为 **1**：
  `new RetryPolicy(enabled ? maxAttempts : 1, ...)`；
- 渠道 `maxRetries()` 与场景 `retry` 仍只能进一步降低，不会把 1 放大；
- `NotifyxProperties.Retry.enabled` 补 Javadoc；
- README 重试说明补一句“关闭时每个渠道只尝试一次”。

## 3. 验证

新增 `RetryConfigurationTest`：

- 默认配置：失败渠道被调用 3 次（默认 `max-attempts=3`）；
- `notifyx.retry.enabled=false`：只调用 1 次。

`RetryConfigurationTest` 2/2 通过。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，295 用例（282 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 默认 `enabled=true`，行为不变；
- 关闭后从“按上限重试”变为“只试一次”，符合配置语义；
- 无 public API 变更。

## 5. 未纳入

- A02/A03、A04、A06、A07、A12 其余属性、A14、A15；
- 信创 DDL、DLX/死信、consumer 多实例、Admin。

## 6. 回滚

单提交回滚；无 schema 变更。
