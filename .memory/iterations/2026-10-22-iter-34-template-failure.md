# iter-34 · 模板渲染异常统一失败路径

- **日期**：2026-10-22
- **分支**：`bugfix/iter-34-template-failure` → `master`
- **类型**：fix（管线失败语义 + 回归测试）
- **触发**：夜间自动化第三轮；收口 iter-32 遗留的 A05 模板渲染分支。

## 1. 问题

`NotifyPipeline.sendOne()` 在进入发送重试循环之前先渲染标题/正文：

```java
String renderedTitle = templateEngine.render(...);
String renderedContent = templateEngine.render(...);
```

渲染异常（`TemplateRenderException` 或模板引擎抛出的任意 `RuntimeException`）不在 `sendOne` 的
`try/catch` 内，会直接落到 `handle()` 外层 catch。后果：

- 该渠道没有审计记录（无法知道“模板坏了”）；
- 没有失败落盘，补偿器看不到；
- `onError` 回调不触发；
- 因为发生在 `breaker.allowRequest()` 之前，也不进入熔断统计——这一条是刻意保留的：
  模板错误不是渠道网络故障，不应把渠道熔断掉，否则修好模板后仍会被 `CIRCUIT_OPEN` 挡住。

## 2. 修复

- 把标题/正文渲染包进 `try/catch (RuntimeException)`；
- 失败时构造 `SendResult.nonRetryable("template render failed: ...")`，走统一的
  `failBeforeSend(...)`：审计 → 指标 → 失败落盘 → `onError`；
- 把 `maxAttempts` 计算提前到渲染之前（无副作用），保证失败记录里的尝试次数与正式发送一致；
- 失败记录仍使用原始事件与 dedupeKey，补偿器可以按原报文重投；渠道不会被调用。

## 3. 验证

新增用例 `NotifyPipelineFailureContainmentTest.shouldPersistTemplateRenderFailure`：

- `handle()` 返回 `false`；
- 渠道调用次数为 0；
- 审计状态为 `FAIL_NON_RETRYABLE`；
- `FailureStore` 有 1 条记录；
- `onError` 回调 1 次。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，270 用例（257 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 模板正常时行为不变；
- 模板异常从“只打一条 WARN、无审计/无落盘”变为“审计 + 落盘 + onError”，属于修复；
- 模板异常不记熔断失败，避免配置错误把渠道熔断。
- 无 public API 变更。

## 5. 未纳入

- A02/A03（失败记录粒度、dedupe release 部分成功重复发送）；
- A04（无渠道返回成功）；
- A07（审计/落盘全链路脱敏）；
- DLX/死信、consumer 多实例、信创 DDL、Admin。

## 6. 回滚

单提交回滚；无 schema/配置变化。
