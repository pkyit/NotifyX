# iter-46 · 审计文本脱敏（bizId / errorMsg / 手机号 / 邮箱）

- **日期**：2026-10-27
- **分支**：`bugfix/iter-46-audit-text-mask` → `master`
- **类型**：fix（安全/审计脱敏 + 回归测试）
- **触发**：20:00 夜间巡检；继续 A07 的审计脱敏分支。

## 1. 问题

`notifyx.mask.before-audit=true` 时，Pipeline 只对 receiver 调 `maskReceiver`；
`AuditRecord.bizId` 与 `errorMsg` 原样写入。`DefaultMasker.maskText` 也只处理身份证号，
手机号/邮箱仍会进入审计日志/JDBC 审计表。

## 2. 修复

- `DefaultMasker.maskText` 扩展为三类正则：
  - 身份证号（原有）；
  - 11 位手机号（`1[3-9]` 开头）；
  - 邮箱地址。
- `NotifyPipeline.audit` 在 `maskEnabled && maskBeforeAudit` 时：
  - receiver 继续走 `maskReceiver`；
  - bizId 走 `maskText`；
  - errorMsg（result.failureReason）走 `maskText`。
- 未开启脱敏时保持原值。

## 3. 验证

新增：

- `DefaultMaskerTest.shouldMaskTextPhoneAndEmail`：同一段文本同时脱敏手机号/邮箱；
- `MaskEnabledTest.shouldMaskBizId`：审计 bizId `13812345678` → `138****5678`；
- `MaskEnabledTest.shouldMaskFailureReason`：异常消息中的邮箱 → `a***@example.com`。

`MaskEnabledTest` 5/5、`DefaultMaskerTest` 9/9。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，301 用例（288 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 默认 `mask.before-audit=true`；审计中的 bizId/errorMsg 从原文变为脱敏文本，
  依赖审计 bizId 精确匹配的看板需要调整；eventId 仍可关联。
- `mask.enabled=false` 时行为不变。
- 无 public API 变更（仅扩展 `maskText` 行为）。

## 5. 未纳入

- `mask.before-send`（渲染内容发送前脱敏）仍未实现；
- 失败落盘 envelope 的 params 仍未脱敏；
- A02/A03/A04/A06/A12 其余属性/A14/A15；
- 1.0.0 已发布，本轮为 1.0.1 候选。

## 6. 回滚

单提交回滚；无 schema 变化。
