# iter-53 · 失败落盘 envelope 脱敏（`mask.before-persist`）

- **日期**：2026-10-28
- **分支**：`bugfix/iter-53-persist-mask` → `master`
- **类型**：feat/fix（A07 落盘脱敏 + 回归测试）
- **触发**：03:00 夜间巡检；收口 A07 的失败报文落盘分支。

## 1. 问题

失败补偿的 `NotifyEnvelope` 直接来自 `NotifyEvent`，`params` 原样序列化：

- `mask.before-audit` / `mask.before-send` 只影响审计与渠道，不影响 FailureStore；
- LocalFile/JDBC 失败表里能看到手机号、邮箱、业务敏感参数；
- 设计书 A07 明确把“envelope 落盘脱敏”列为独立策略。

## 2. 修复

- `NotifyEnvelope` 增加 `withParams(Map)`，返回替换 params 的新报文；
- `NotifyxProperties.Mask` 增加 `before-persist`（默认 **false**，保持补偿报文可重放）；
- `NotifyPipeline` 增加 `maskBeforePersist`：
  - 开启且 `mask.enabled=true` 时，在 `persistFailure` 里对 envelope.params 调 `maskParams`；
  - 原始 event 不变；
- 自动装配传入 `properties.getMask().isBeforePersist()`。

**权衡**：开启后失败补偿拿到的是脱敏参数，重投内容也会脱敏；这是显式 opt-in，
默认 false 保持 1.0.0 行为。

## 3. 验证

新增 `FailurePersistenceTest.shouldMaskEnvelopeParamsBeforePersist`：

- `phone=13812345678` → envelope.params `138****5678`；
- 原始 event.params 仍是 `13812345678`。

`FailurePersistenceTest` 3/3。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，312 用例（299 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 默认 `before-persist=false`，现有失败报文与补偿行为不变；
- 开启后 LocalFile/JDBC 中的 params 脱敏，补偿重投使用脱敏值；
- `NotifyEnvelope.withParams` 是新增 public 方法，不破坏 record 构造。

## 5. 未纳入

- A02/A03（失败记录粒度与定向重投）、A04（NO_ROUTE）、A06 完整方案、A14；
- ISSUE-039 仍需决策；
- 1.0.0 已发布，本轮为 1.0.1 候选。

## 6. 回滚

单提交回滚；默认关闭，无 schema 变更。
