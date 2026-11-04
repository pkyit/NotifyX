# iter-68 · 架构审查 A02/A03/A04 落地（A07 确认已修复）

- **日期**：2026-11-04
- **类型**：fix（3 项架构审查遗留缺陷）
- **触发**：架构审查 `docs/architecture-review-2026-10-22.md` 的 A02/A03/A04/A07 自 10 月起处于待决策状态，本轮由用户拍板方向后落地。
- **关联决策**：D-034（A02+A03 合并）、D-035（A04）

## 0. A07 先行确认：已全部修复

架构审查 A07 要求"审计/发送/落盘三条脱敏链路"全部生效。代码证据（[NotifyPipeline.java](file:///c:/Users/admin/Downloads/NotifyX/notifyx-core/src/main/java/io/github/pkyit/notifyx/core/pipeline/NotifyPipeline.java)）：

| 分支 | 位置 | 开关 | 默认值 | 状态 |
| --- | --- | --- | --- | --- |
| before-audit | `audit()` 方法 L443-452 | `mask.before-audit` | `true` | ✅ |
| before-send | `sendOne()` L262-264 | `mask.before-send` | `false` | ✅ |
| before-persist | `persistFailure()` L417-418 | `mask.before-persist` | `false` | ✅ |

三条脱敏链路全部有效、开关可配。**A07 关闭**，本轮不再做任何改动。

## 1. A02+A03 合并修复（D-034）

### 根因

- A02：`FailedRecord.recordId` 只用 eventId → 多渠道 fan-out 后互相覆盖。
- A03：`dispatch()` 里 `if (failed) dedupe.release(dedupeKey)`（事件级）→ email 成功、webhook 失败时成功渠道的静默窗口也被释放，同指纹事件重入会重复发送成功渠道。

### 改动

| 文件 | 改动 |
| --- | --- |
| `NotifyPipeline.persistFailure()` | recordId 从 `event.eventId()` 改为 `event.eventId() + ":" + channel` |
| `MqNotifyKit.persist()` | recordId 从 `event.eventId()` 改为 `event.eventId() + ":" + bus.name()` |
| `FailedRecord` Javadoc | recordId 注释更新为"eventId + ":" + channel" |
| `dispatch()` | 引入 `anySuccess` 追踪；release 条件改为 `failed && !anySuccess`（全部失败才 release） |

### 为什么不做完整粒度（channel + receiver）

- 设计书 §9.9 补偿语义是"整事件重投"而非"定向到 receiver 重投"；
- 同一渠道多 receiver 失败会产生 N 条记录，补偿复杂度陡增；
- channel 粒度已足以解决多渠道互相覆盖的核心痛点。

### 为什么不改 dedupeKey 为 channel 级

- 需要改 `resolveDedupeKey` 签名、`DedupeFilter` 和 store key 空间；
- SPI 级变更，改动面大且不符合"事件级去重"的设计书 §9.5 契约；
- 事件级 lease + 部分成功不 release 是语义最清晰、改动最小的解。

## 2. A04 无渠道视为失败（D-035）

### 根因

`channels.isEmpty()` 分支直接 `return true`（dispatch 的 failed=false）→ handle 返回 `true` →
业务方看到成功，但实际什么都没发出去。最危险的"配置错误伪装成业务成功"。
现有测试 `shouldSkipWhenNoChannel` 固化了该反模式。

### 改动

| 文件 | 改动 |
| --- | --- |
| `dispatch()` L217-220 | `channels.isEmpty()` 分支从 `return true` 改为 `return true`（failed=true），同时 `audit(..., "NO_ROUTE")`；**注意**：dispatch 返回值是 `failed` 语义，return true 表示"投递失败"，handle 外层返回 `!failed=false`。 |
| `NotifyPipelineRetryTest.shouldSkipWhenNoChannel` | 改名为 `shouldReportNoRouteWhenNoChannel`，断言从"无调用、无审计"改为"handle 返回 false、audit 状态 FAIL_NON_RETRYABLE、errorMsg 含 NO_ROUTE" |

## 3. 验证

`mvn -B -ntp clean verify` → BUILD SUCCESS，17/17 模块，379 用例全绿。

### 关键行为对比

| 场景 | 修复前 | 修复后 |
| --- | --- | --- |
| email 成功 + webhook 失败，再进同指纹事件 | email 被重复发送（dedupeKey 被释放） | email 被静默窗口挡住；webhook 失败记录由补偿器 at-least-once 重投 |
| 多渠道 fan-out 全部失败 | 只有最后一条失败记录存活（recordId=eventId 覆盖） | 每个失败渠道独立一条记录 |
| 无可用渠道 | `handle()` 返回 true，无审计 | `handle()` 返回 false，audit 写入 `NO_ROUTE` |

## 4. 遗留

全部架构审查决策项已处理完毕。剩余工作：按项目 Git 规范提交 + 版本号升级（待用户授权）。
