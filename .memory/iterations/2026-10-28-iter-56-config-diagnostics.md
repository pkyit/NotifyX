# iter-56 · 启动诊断表提示“配置与实现不一致”

- **日期**：2026-10-28
- **分支**：`bugfix/iter-56-config-diagnostics`
- **类型**：fix（可观测性/配置契约）
- **触发**：21:00 夜间巡检；A12 配置真实性审计。

## 1. 问题

诊断表会列出 SPI 实现类名，但不会指出“配置要求和实际实现不一致”：

- `dedupe.store=redis` 但 Redis 未装配/已降级 → 只显示 `MemoryDedupeStore`，用户可能以为 Redis 生效；
- `dedupe.store=jdbc` 但 JDBC 去重尚未实现 → 静默落到内存；
- `rate-limit.store=redis` 但实际内存；
- `templates.engine=freemarker` 但实际仍是 `SimpleTemplateEngine`。

这些是“配置写了、实际没生效”的典型静默陷阱。

## 2. 修复

`NotifyxDiagnostics.appendConfigMismatchWarnings` 增加启动警告：

| 配置 | 实际 | 输出 |
| --- | --- | --- |
| `dedupe.store=redis` | 内存实现 | `⚠ 配置 dedupe.store=redis，实际为内存实现` |
| `dedupe.store=jdbc` | 内存实现 | `⚠ jdbc 去重尚未实现，实际使用内存实现` |
| `dedupe.store=memory` | 非内存 | `⚠ 配置 dedupe.store=memory，实际为非内存实现` |
| `rate-limit.store=redis` | 内存实现 | `⚠ 配置 rate-limit.store=redis，实际为内存实现` |
| `rate-limit.store=memory` | 非内存 | `⚠ 配置 rate-limit.store=memory，实际为非内存实现` |
| `templates.engine!=simple` | `SimpleTemplateEngine` | `⚠ 配置 templates.engine=...，实际仍为 SimpleTemplateEngine` |

只加提示，不改变装配/发送行为。

## 3. 验证

新增 `NotifyxDiagnosticsTest`：

- `dedupe.store=jdbc` + 内存 → 含 `jdbc 去重尚未实现`；
- `dedupe.store=redis` + 内存 → 含 `dedupe.store=redis，实际为内存实现`；
- `templates.engine=freemarker` + SimpleTemplateEngine → 含 `templates.engine=freemarker`。

`NotifyxDiagnosticsTest` 9/9。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，319 用例（306 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 仅诊断表新增警告行，不改变启动/发送行为；
- 不引入高基数标签或配置值泄露（只打印 store/engine 名称）。

## 5. 未纳入

- `templates.engine` 的真实多引擎实现；
- `dedupe.store=jdbc` 的 JDBC 去重实现（信创 DDL，需决策）；
- ISSUE-039、A02/A03/A04、A06 完整方案、A14。

## 6. 回滚

单提交回滚；无 schema/行为变更。
