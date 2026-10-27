# iter-51 · 全部渠道禁用时健康状态修正

- **日期**：2026-10-28
- **分支**：`bugfix/iter-51-health-disabled` → `master`
- **类型**：fix（健康语义 A04 可见性 + 回归测试）
- **触发**：01:00 夜间巡检；审计 `NotifyxHealthIndicator` 的“无可用渠道”分支。

## 1. 问题

`NotifyxHealthIndicator` 只判断 `channels.isEmpty()`：

```java
if (channels.isEmpty()) {
    return Health.down().withDetail("reason", "no NotifyChannel registered").build();
}
```

如果注册了渠道但全部 `isEnabled(ctx) == false`：

- 管线不会投递任何通知；
- 健康状态却可能是 UP（无非内存 store）或 DEGRADED（内存 store），
  运维看到的是“正常/降级”，不是“无处可发”。

## 2. 修复

- 在 `channels.isEmpty()` 之后检查所有渠道的 `isEnabled(null)`（异常时按启用处理，保守）；
- 全部禁用 → `DOWN`，detail `reason=all NotifyChannel disabled` + `disabledChannels`；
- 部分禁用 → `DEGRADED`，detail `disabledChannels`；
- 保留原有 probe/内存降级逻辑。

这是 A04 的“可见性”修复：Pipeline 的 no-route 语义仍待用户决策。

## 3. 验证

新增 `NotifyxHealthIndicatorTest`：

- 全部渠道禁用 → `DOWN`，reason 与 disabledChannels 正确；
- 一个启用 + 一个禁用 → `DEGRADED`，列出 disabledChannels。

`NotifyxHealthIndicatorTest` 8/8 通过。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，308 用例（295 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 之前“全部渠道禁用”显示 UP/DEGRADED，现在显示 DOWN；K8s readiness 会更早暴露配置错误；
- 部分禁用从 UP 变为 DEGRADED（含内存 store 时原本已是 DEGRADED）；
- 无 public API 变更。

## 5. 未纳入

- Pipeline 的 `NO_ROUTE` 结构化结果（A04，需决策）；
- A02/A03/A06 完整方案、`mask.before-send`、envelope 落盘脱敏、A14；
- ISSUE-039 仍需决策。

## 6. 回滚

单提交回滚；无 schema/配置变化。
