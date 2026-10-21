# iter-15 · NotifyEnvelope 契约与零依赖 JSON 编解码（§6.5）

- **日期**：2026-10-22
- **分支**：`feat/notify-envelope` → `master`
- **类型**：feat（失败补偿的前置契约）
- **关联**：ISSUE-015（推进，未关闭）

## 1. 背景

失败落盘此前只存 `eventId`，无法据记录重建事件，自动补偿无从谈起。
§6.5 定义了 MQ/落盘共用的 `NotifyEnvelope`；core 又必须零依赖（不能引 Jackson）。
本次先把「报文模型 + 编解码 + 落盘时写真实报文」落地，补偿执行器留到下一轮。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | `FailedRecord.envelopeJson` 里只有 eventId | `persistFailure` 第二参数为 `event.eventId()` | 改为写入 `NotifyEnvelope` JSON；序列化失败退化为 eventId 并 debug 日志（不阻断落盘） |
| 2 | core 不能引 Jackson | ADR-003 / 依赖纪律 | 新增 `JsonCodec`（约 300 行）：对象/数组/字符串/数字/布尔/null，转义含 `\uXXXX`，NaN/Infinity 输出 null |
| 3 | 报文版本兼容无人守 | §6.4/§6.5 要求 `schemaVersion` | `NotifyEnvelopeCodec` 遇到高于当前版本的报文直接拒绝，避免旧代码误读新语义 |
| 4 | 事件参数是任意 Map，直接 `toString` 会丢结构 | 参数含嵌套 Map/List | 编解码递归处理嵌套；测试覆盖中文/引号/换行/嵌套 |
| 5 | 测试里渠道默认 `maxRetries()=3` 干扰 attempts 断言 | 落盘用例期望 attempts=1 实得 3 | 测试渠道 `maxRetries()=0`，使用 `RetryPolicy` |

## 3. 改动点

**core 新增**：

- `NotifyEnvelope`（record）：`schemaVersion/eventId/idempotencyKey/dedupeKey/scene/level/title/params/receivers/bizId/tags/traceId/spanId/instanceId/silentWindowSeconds/attempts/occurredAt/createdAt`；
  - `of(event, dedupeKey, traceId, spanId, instanceId, attempts, createdAt)` 组装；
  - `toEvent()` 还原为事件（`async=false`，补偿时同步执行；trace/span 写回 tags）。
- `JsonCodec`：零依赖 JSON 读写；对象键保留写入顺序；整数 `Long`、小数/指数 `Double`。
- `NotifyEnvelopeCodec`：报文 ↔ JSON；版本守卫与类型转换。

**管线**：`persistFailure(..., dedupeKey)` 用 `NotifyEnvelope` + codec 组装 envelope；
codec 抛异常时退化为 eventId，落盘本身不失败。

**测试（9 个）**：

| 测试 | 覆盖 |
| --- | --- |
| `JsonCodecTest`（4） | 全类型往返、转义/Unicode、非法 JSON、NaN/Infinity |
| `NotifyEnvelopeCodecTest`（4） | 全字段往返（含嵌套/中文）、`toEvent` 还原、未来版本拒绝、非法 JSON |
| `FailurePersistenceTest`（1） | 投递失败落盘的 envelope 可解码出原事件/参数/attempts |

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，12 模块，179 tests，0 failures
  core 99 / spring 17 / autoconfigure 25 / email 6 / logback 20 / webhook 12
→ JaCoCo：core 79.8%（门禁 76%）；其它模块门禁全部通过
→ Spotless 0 / Checkstyle 0
```

## 5. 回滚

删除 `NotifyEnvelope`/`JsonCodec`/`NotifyEnvelopeCodec` 与管线落盘改动即可；
`FailedRecord` 契约未变（envelopeJson 仍是 String）。

## 6. 下一步

- **ISSUE-015 收尾**：`FailureCompensationRunner`（调度、CAS SENDING、重投、SENT/DEAD、退避）。
  需要管线提供「同步投递结果」；或先以 `NotifyKit.send(event.async=false)` + 状态机最佳努力实现。
- core 79.8% → 90%。
