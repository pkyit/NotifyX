# iter-47 · `notifyx.env` / `notifyx.tenant` 进入模板参数

- **日期**：2026-10-27
- **分支**：`bugfix/iter-47-env-tenant` → `master`
- **类型**：fix（配置契约 + 模板渲染）
- **触发**：21:00 夜间巡检；处理 A12 的 `env`/`tenant` 属性。

## 1. 问题

`NotifyxProperties.env` / `tenant` 有默认值并绑定，但 Pipeline 渲染参数只有
`scene/level/title/bizId/eventId/occurredAt/instanceId/traceId/params/content`。
设计书模板示例 `[${env}] ${scene}` 在运行时被渲染为空串，`tenant` 同样不可用。

## 2. 修复

- `NotifyPipeline` 增加 `env` / `tenant` 字段与 Builder 方法，默认 `prod` / `default`；
- `renderParams` 注入 `env`、`tenant`（`putIfAbsent`，业务参数同名时可覆盖）；
- `NotifyxAutoConfiguration` 传入 `properties.getEnv()` / `getTenant()`。

## 3. 验证

新增 `NotifyPipelineTest.shouldRenderEnvAndTenant`：

- 模板 `[${env}] ${tenant}` / `${tenant}`；
- Builder 设置 `env=staging`、`tenant=acme`；
- 渠道收到 `[staging] acme` 与 `acme`。

`NotifyPipelineTest` 6/6 通过。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，302 用例（289 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 默认 `env=prod`、`tenant=default`，之前模板里的 `${env}` 渲染为空串，现在渲染为 `prod`；
  依赖空串的模板需要调整。
- 业务 params 中已有同名 `env`/`tenant` 时优先使用业务值（`putIfAbsent`）。
- 无 public API 删除；Builder 新增方法。

## 5. 未纳入

- `dedupe.default-window` 语义冲突（ISSUE-039，需决策）；
- `mask.before-send`、失败落盘 envelope 脱敏；
- A02/A03/A04/A06/A14/A15。

## 6. 回滚

单提交回滚；无 schema/配置破坏性变更。
