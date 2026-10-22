# iter-35 · 渠道返回失败状态时的管线语义修复

- **日期**：2026-10-22
- **分支**：`bugfix/iter-35-result-status` → `master`
- **类型**：fix（渠道 SPI 语义 + 熔断 + 重试）
- **触发**：夜间自动化第四轮；审计 `NotifyPipeline.sendOne()` 时发现。

## 1. 问题

渠道 SPI 允许通过 `SendResult` 返回值表达失败（`FAIL_RETRYABLE` / `FAIL_NON_RETRYABLE`），
但 `sendOne()` 原先只要返回值非 null 就执行：

```java
breaker.recordSuccess();
break;
```

后果：

- `FAIL_RETRYABLE` 不重试，只发一次；
- `FAIL_NON_RETRYABLE` 也被记为熔断成功；
- 连续失败结果永远不会打开熔断；
- 审计/落盘虽然事后按状态执行，但重试与熔断语义已经错了。

## 2. 修复

按 `SendResult.status()` 分支：

| 状态 | 行为 |
| --- | --- |
| `SUCCESS` | `breaker.recordSuccess()`，结束 |
| `FAIL_RETRYABLE` | `breaker.recordFailure()`；未达 `maxAttempts` 退避后重试，否则以该结果结束 |
| `FAIL_NON_RETRYABLE` | `breaker.recordFailure()`，只发一次 |
| `SUPPRESSED` / `DEDUPED` / `RATE_LIMITED` / `CIRCUIT_OPEN` | 渠道主动抑制，不算成功也不记失败；结束 |

`null` 返回、`ChannelException`、意外 `RuntimeException` 的既有处理不变。

## 3. 验证

新增 `NotifyPipelineResultStatusTest` 4 个用例：

1. `FAIL_RETRYABLE` → 第 2 次成功，调用 2 次，审计 `SUCCESS`；
2. `FAIL_NON_RETRYABLE` → 只调用 1 次，审计 `FAIL_NON_RETRYABLE`，失败落盘 1 条；
3. 连续 `FAIL_RETRYABLE` 达到阈值 → 熔断打开，下一次事件得到 `CIRCUIT_OPEN`；
4. 渠道主动 `SUPPRESSED` → 不算失败、不落盘、不打开熔断。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，274 用例（261 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 正常抛出 `ChannelException` 的现有渠道行为不变；
- 只有返回失败状态的渠道会获得正确的重试/熔断语义，属于修复；
- 主动抑制状态不再被误记为熔断成功，熔断计数更准确；
- 无 public API 变更。

## 5. 未纳入

- A02/A03（失败记录粒度、dedupe release 部分成功重复发送）；
- A04（无渠道返回成功）；
- A07（审计/落盘全链路脱敏）；
- DLX/死信、consumer 多实例、信创 DDL、Admin。

## 6. 回滚

单提交回滚；无 schema/配置变化。
