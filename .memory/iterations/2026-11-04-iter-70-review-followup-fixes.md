# iter-70 · 复核跟进修复（熔断新卡死路径 / recordId 列宽 / 去重限频清理）

- **日期**：2026-11-04
- **类型**：fix + test（iter-69 修复的复核跟进）
- **触发**：外部复核（Claude 评审）确认 iter-69 死锁已修复，但发现两处新引入/遗留缺陷；本轮修复并补齐回归测试。
- **关联决策**：D-036（recordId 收件人摘要粒度，偏离 D-034 的"不做 receiver 粒度"结论）

## 1. 复核结论摘要

| 项 | 复核结果 |
| --- | --- |
| 4.1 去重死锁 | 已修复（16 线程压测 128 万次无死锁）；遗留容量打满后全表扫描性能悬崖 + 冗余分支 |
| 4.2 半开卡死 | 部分修复：抑制类状态已修，但 `FAIL_NON_RETRYABLE` 分支不释放探测标记 → 新卡死路径 |
| 4.3 熔断粒度 | 不可重试不计熔断已生效；但 `ChannelException` 不可重试路径仍 `recordFailure()`，口径不一致 |
| recordId | 脱敏收件人明文可能超过 JDBC `record_id varchar(64)`；严格模式插入失败 → 记录静默丢失；脱敏值还可能碰撞 |
| 测试 | 三个修复提交零测试改动，无回归保护 |

## 2. 本轮改动

| 文件 | 改动 |
| --- | --- |
| `NotifyPipeline.sendOne()` | `FAIL_NON_RETRYABLE` 分支补 `breaker.neutralProbeRelease()`；`ChannelException` catch 拆分：可重试才 `recordFailure()`，不可重试改 `neutralProbeRelease()`，与结果分支口径统一 |
| `NotifyPipeline.persistFailure()` | recordId 改为 `buildRecordId()`：`eventId:channel[:sha256(receiver)前16hex]`；总长超 64 时整体摘要压缩（`RECORD_ID_MAX_LENGTH=64` 对齐各方言 DDL） |
| `CircuitBreaker.neutralProbeRelease()` | Javadoc 补全三类调用场景 |
| `MemoryDedupeStore` | 删除两个分支都 `return false` 的冗余判断；容量打满时的全表清理按 `EVICT_INTERVAL_MILLIS=100ms` 限频（CAS 单线程清理，其余走拒绝路径），消除风暴下每次调用 ~1.6ms 的全表扫描悬崖 |

## 3. 新增回归测试（10 个）

| 测试 | 覆盖 |
| --- | --- |
| `CircuitBreakerTest` +2 | 中性释放后可再次探测且状态保持半开；关闭状态下空操作无害 |
| `CircuitBreakerHalfOpenRegressionTest`（新）+3 | 半开探测命中不可重试结果/异常后不卡死、后续成功可关闭熔断；关闭状态不可重试不计失败数 |
| `FailurePersistenceTest` +2 | 多收件人各自落盘互不覆盖；超长收件人 recordId ≤ 64 且不碰撞 |
| `MemoryDedupeStoreTest` +3 | 容量打满拒绝；过期清理后恢复接收；8 线程 × 2 万次并发压测不死锁（@Timeout(30)） |

## 4. 验证

- `mvn -B -ntp -pl notifyx-core test`：162 用例全绿（152 → 162）。
- `mvn -B -ntp clean verify`：17/17 模块 BUILD SUCCESS。

## 5. 遗留

见 ISSUE-061：4.4 重试占线程（需 `NotifyTaskExecutor` 延迟调度能力）、4.5 溢出/停机落盘（需 `PriorityTask` 携带 envelope + executor 注入 FailureStore）、4.6 指纹易变参数、去重限频窗口内的保守拒绝（已文档化为溢出策略）。
