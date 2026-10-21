# iter-14 · 管线重试/熔断分支与事件边界测试（ISSUE-013）

- **日期**：2026-10-22
- **分支**：`test/pipeline-retry` → `master`
- **类型**：test（core 覆盖率 72% → 78%）
- **关联**：ISSUE-013

## 1. 背景

core 是「去重静默失效」「重试策略错」这类隐蔽 bug 的高发区。此前 `NotifyPipeline` 的重试、
熔断、无渠道分支与 `NotifyEvent` 的边界校验没有直接测试，覆盖率停在约 72%。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 重试与熔断交互没有测试，容易写错阈值关系 | 首版用例 `failureThreshold=2` + 3 次重试 → 第 3 次被熔断拦下，`calls=2` 而非 3 | 重试用例把熔断阈值调到 10 隔离；熔断用例单独验证阈值/半开 |
| 2 | 渠道默认 `maxRetries()=3` 会覆盖 `RetryPolicy.maxAttempts` | 首版熔断用例每次事件尝试 3 次，第 3 次熔断打开，审计全成 CIRCUIT_OPEN | 测试渠道 `maxRetries()` 返回 0（表示使用策略值），用例语义才可控 |
| 3 | `NotifyEvent` 的 64KB 上限、null/Instant 归一、深嵌套退化无测试 | 只有场景码/拷贝/接收人 4 个用例 | 补 5 个边界用例 |

## 3. 改动点

**新增 `NotifyPipelineRetryTest`（5 个用例）**：

| 用例 | 覆盖 |
| --- | --- |
| 可重试最终成功 | timeout×2 → 第 3 次成功；audit=SUCCESS |
| 不可重试 | `ChannelAuthException` 只尝试 1 次；audit=FAIL_NON_RETRYABLE |
| 场景 retry 覆盖 | scene.retry(1) 时只尝试 1 次；audit=FAIL_RETRYABLE |
| 熔断→半开 | 阈值 2、open 100ms：两次失败、第三次 CIRCUIT_OPEN（渠道不再被调用）；150ms 后半开成功关闭 |
| 无渠道 | 静默跳过，不抛异常、不产生审计 |

**`NotifyEventTest` 新增 5 个用例**：

- params 超过 64KB → `IllegalArgumentException("params exceed")`；
- null 值归一 `__NULL__`、`Instant` 归一 epoch millis；
- 嵌套超过 3 层退化为字符串；
- tags 防御性拷贝 + `tag()` 读取；
- 自定义 eventId/occurredAt/silentWindow/async/title 原样保留、toString 可读。

**POM**：core `jacoco.minimum: 0.65 → 0.76`（实测 78%）。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，12 模块，170 tests，0 failures
  core 90 / spring 17 / autoconfigure 25 / email 6 / logback 20 / webhook 12
→ JaCoCo：core 78%（门禁 76%）；其它模块门禁全部通过
→ Spotless 0 / Checkstyle 0
```

## 5. 回滚

删除 `NotifyPipelineRetryTest`、还原 `NotifyEventTest` 与 core 门禁即可；无生产代码改动。

## 6. 下一步

- core 78% → 90%：剩余主要在 `NotifyPipeline` 的 defer/failureStore/onSuccess 回调、
  `NotifyContext` builder、`InMemoryFailureStore` 边界。
- `NotifyEnvelope` + 自动补偿（ISSUE-015）仍是 P1 最后的功能缺口。
