# iter-54 · `diagnostics.fail-fast` 严格模式生效

- **日期**：2026-10-28
- **分支**：`bugfix/iter-54-fail-fast` → `master`
- **类型**：fix（配置契约 + 启动校验）
- **触发**：19:00 夜间巡检；审计 A12 未生效配置。

## 1. 问题

`notifyx.diagnostics.fail-fast` 只绑定不生效：

- 设计书 §14.1 把它定义为“唯一允许启动失败的显式严格模式”；
- 实际无论 true/false，缺渠道/全部渠道禁用都只是 `ApplicationReady` 后打印诊断表，
  应用照常启动。

## 2. 修复

`NotifyxDiagnostics` 实现 `InitializingBean`，在 `afterPropertiesSet()` 中：

1. `fail-fast=false`（默认）直接返回，保持 1.0.0 行为；
2. `fail-fast=true` 时检查：
   - 没有任何 `NotifyChannel` → 抛 `IllegalStateException("... no NotifyChannel registered")`；
   - 所有渠道 `isEnabled(null)==false` → 抛 `IllegalStateException("... all NotifyChannel disabled")`。

刻意**不**校验 `templates.engine` / `dedupe.store` 等属性，避免把“自定义 bean 覆盖”误判为错误；
严格模式只覆盖最明确的“无处可发”配置。

## 3. 验证

新增 `NotifyxDiagnosticsTest`：

- `fail-fast=true` + 全部渠道禁用 → `IllegalStateException`，message 含 `all NotifyChannel disabled`；
- `fail-fast=false` + 全部渠道禁用 → 不抛异常。

`NotifyxDiagnosticsTest` 6/6。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，314 用例（301 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 默认 `fail-fast=false`，启动行为不变；
- 显式打开后，缺渠道/全禁用会阻止启动，符合设计书约定；
- `NotifyxDiagnostics` 新增 `afterPropertiesSet()`，不影响现有构造与 `render()`。

## 5. 未纳入

- ISSUE-039、A02/A03/A04、A06 完整方案、A14 仍需决策；
- `templates.engine`、`dedupe.store`、`rate-limit.store` 的语义仍未实现，未在严格模式中校验；
- 1.0.0 已发布，本轮为 1.0.1 候选。

## 6. 回滚

单提交回滚；默认关闭，无 schema 变更。
