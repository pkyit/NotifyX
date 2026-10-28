# iter-58 · `fail-fast` 扩展校验存储/引擎配置

- **日期**：2026-10-28
- **分支**：`bugfix/iter-58-failfast-config`
- **类型**：fix（严格模式配置校验）
- **触发**：23:00 夜间巡检；把 iter-56 的诊断提示接入显式严格模式。

## 1. 问题

`notifyx.diagnostics.fail-fast=true` 在 iter-54 只校验“有没有可用渠道”；
但配置与实现不一致仍然只打印 WARN：

- `dedupe.store=redis` 但实际内存；
- `dedupe.store=jdbc` 但 JDBC 去重未实现；
- `rate-limit.store=redis` 但实际内存；
- `templates.engine=freemarker` 但实际 `SimpleTemplateEngine`。

严格模式用户显式要求“配置不生效就别启动”，这些情况应该失败。

## 2. 修复

`NotifyxDiagnostics.afterPropertiesSet()` 在原有渠道检查后追加：

| 配置 | 实际 | 错误 |
| --- | --- | --- |
| `dedupe.store=redis` | 内存 | `dedupe.store=redis but actual dedupe store is in-memory` |
| `dedupe.store=jdbc` | 内存 | `dedupe.store=jdbc is not implemented yet` |
| `dedupe.store=memory` | 非内存 | `dedupe.store=memory but actual dedupe store is not in-memory` |
| `rate-limit.store=redis` | 内存 | `rate-limit.store=redis but actual rate limiter is in-memory` |
| `rate-limit.store=memory` | 非内存 | `rate-limit.store=memory but actual rate limiter is not in-memory` |
| `templates.engine!=simple` | SimpleTemplateEngine | `templates.engine=... but actual engine is SimpleTemplateEngine` |

仅 `fail-fast=true` 时抛 `IllegalStateException`；默认 false 仍然只打印诊断 WARN。

## 3. 验证

新增 `NotifyxDiagnosticsTest`：

- `fail-fast=true` + `dedupe.store=jdbc` → 抛异常，message 含 `not implemented`；
- `fail-fast=true` + `templates.engine=freemarker` → 抛异常；
- `fail-fast=true` + `dedupe.store=redis` 实际内存 → 抛异常。

`NotifyxDiagnosticsTest` 13/13。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，324 用例（311 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 默认 `fail-fast=false`，行为不变；
- 显式严格模式下，配置不生效会阻止启动，符合设计书 §14.1 的“唯一例外”；
- 自定义 `TemplateEngine` Bean 不会被误判（只有实际是 `SimpleTemplateEngine` 才报错）。

## 5. 未纳入

- JDBC 去重实现（信创 DDL）；
- ISSUE-039、A02/A03/A04、A06 完整方案、A14；
- 1.0.0 已发布，本轮为 1.0.1 候选。

## 6. 回滚

单提交回滚；无 schema/默认行为变更。
