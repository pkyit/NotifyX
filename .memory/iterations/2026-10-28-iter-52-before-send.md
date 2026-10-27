# iter-52 · `mask.before-send` 生效

- **日期**：2026-10-28
- **分支**：`bugfix/iter-52-before-send` → `master`
- **类型**：fix（A07 发送前脱敏 + 回归测试）
- **触发**：02:00 夜间巡检；收口 A07 的 `mask.before-send` 分支。

## 1. 问题

`NotifyxProperties.Mask.beforeSend` 只绑定不生效：

- Pipeline 从不读取它；
- 渠道拿到的 `renderedTitle/renderedContent` 是原文；
- Webhook 直接使用 `ctx.event().params()`，没有“脱敏副本”入口。

所以配置 `notifyx.mask.before-send=true` 时，发送内容仍然包含手机号/邮箱/业务参数。

## 2. 修复

- `NotifyContext` 增加 `params()`：默认等于 `event.params()`，可由 Builder 覆盖为脱敏副本；
  `withAttempt()` 保留该副本；
- `NotifyPipeline` 增加 `maskBeforeSend`：
  - 开启且 `mask.enabled=true` 时，对 `renderedTitle/renderedContent` 调 `maskText`；
  - 对 `event.params()` 调 `maskParams` 作为 `ctx.params()`；
  - 原始 `event` 不变，审计/追踪仍可关联；
- `NotifyxAutoConfiguration` 传入 `properties.getMask().isBeforeSend()`；
- `WebhookChannel` payload 改用 `ctx.params()`。

默认 `before-send=false`，1.0.0 行为不变。

## 3. 验证

新增：

- `MaskEnabledTest.shouldMaskBeforeSend`：标题/正文/`ctx.params()` 均脱敏，`event.params()` 原文；
- `MaskEnabledTest.shouldNotMaskBeforeSendByDefault`：默认关闭时渠道看到原文；
- `WebhookChannelTest.shouldUseContextParams`：body 使用 `ctx.params()` 的脱敏副本。

`MaskEnabledTest` 7/7、`WebhookChannelTest` 18/18。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，311 用例（298 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 默认 `before-send=false`，现有渠道行为不变；
- 自定义渠道仍可直接读 `ctx.event().params()`（原文）；开启 before-send 时应改用
  `ctx.params()` 才能拿到脱敏副本；
- `NotifyContext.params()` 是新增 public 方法，不破坏实现。

## 5. 未纳入

- 失败落盘 envelope 的 params 脱敏（需要 `mask-before-persist` 策略与补偿兼容性决策）；
- A02/A03/A04/A06 完整方案、A14；
- ISSUE-039 仍需决策。

## 6. 回滚

单提交回滚；无 schema/配置破坏性变更。
