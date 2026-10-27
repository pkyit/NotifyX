# iter-45 · 脱敏总开关与接收人脱敏修复

- **日期**：2026-10-27
- **分支**：`bugfix/iter-45-mask-enabled` → `master`
- **类型**：fix（安全/配置契约 + 回归测试）
- **触发**：19:00 夜间巡检；1.0.0 发布后继续处理 A07 的审计脱敏分支。

## 1. 问题

1. `notifyx.mask.enabled` 只绑定，`NotifyPipeline.audit()` 只看 `maskBeforeAudit`，
   关闭总开关仍会脱敏——配置契约失真。
2. 即使 `mask.before-audit=true`，receiver 走的是 `masker.maskText(receiver)`；
   `DefaultMasker.maskText` 只正则替换身份证号，**手机号/邮箱接收人原样进入审计**。
   对告警系统来说，这正好是最高频的接收人类型。

## 2. 修复

- `NotifyPipeline` 增加 `maskEnabled` 字段/Builder，审计条件改为
  `maskEnabled && maskBeforeAudit && receiver != null && masker != null`；
- `NotifyxAutoConfiguration` 注入 `properties.getMask().isEnabled()`；
- `Masker` 增加 default 方法 `maskReceiver(String)`：
  - 含 `@` → `maskEmail`；
  - 纯数字/带 `+`、空格、连字符的手机号 → `maskPhone`；
  - 其它 → `maskText` 兜底；
- Pipeline 审计改用 `masker.maskReceiver(receiver)`。

## 3. 验证

新增 `MaskEnabledTest` 3 个用例：

- `mask.enabled=false`：审计 receiver 保持 `13812345678`；
- `mask.enabled=true`：手机号变为 `138****5678`；
- `mask.enabled=true`：邮箱 `alice@example.com` 变为 `a***@example.com`。

`MaskEnabledTest` 3/3 通过。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，298 用例（285 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- `mask.enabled` 默认 true，默认行为从“receiver 基本不脱敏”变为“按类型脱敏”；
  这是安全修复，依赖审计里完整手机号/邮箱的看板需要调整。
- `Masker` 只新增 default 方法，不破坏自定义实现。
- 仍不改变 `mask.before-send`（渲染内容发送前脱敏）与 envelope 落盘脱敏；
  这两项仍属 A07 未完成部分。

## 5. 未纳入

- A02/A03、A04、A06、A07 的 before-send / envelope 落盘脱敏、A12 其余属性、A14、A15；
- 1.0.0 已发布，本轮改动是 **1.0.1 候选**；Central 额度 2026-11-01 前不可发新版本，
  未修改版本号。

## 6. 回滚

单提交回滚；无 schema/配置破坏性变更。
