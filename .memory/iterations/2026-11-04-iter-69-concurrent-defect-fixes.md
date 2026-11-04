# iter-69 · 并发缺陷收口（去重死锁 / 熔断半开 / 失败记录覆盖）

- **日期**：2026-11-04
- **类型**：fix（外部代码评估报告 4.1/4.2/4.3 + 核实中发现的记录覆盖）
- **触发**：用户上传《NotifyX 代码评估报告》，逐项核实后修复。
- **版本**：随 1.0.4 发布（版本号升级提交 `9c3cc99`）。
- **注意**：本迭代的提交信息误标为 `(iter-09)`，正确编号为 iter-69；合并提交 `47de5b8`。

## 1. 修复内容

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| 4.1 `MemoryDedupeStore` 死锁（高） | `tryAcquire` 在 `ConcurrentHashMap.compute` 回调内调用 `evictExpired()`（对同一 map `removeIf`），违反 compute 契约，通知风暴下节点锁循环等待 | 清理移出 compute：快速路径 get → 容量检查+清理 → compute 只做原子占位 |
| 4.2 熔断器半开卡死（中） | 渠道返回抑制状态（SUPPRESSED/DEDUPED/RATE_LIMITED）时既不 recordSuccess 也不 recordFailure，`probeInFlight` 永不释放 | 新增 `CircuitBreaker.neutralProbeRelease()`，抑制分支调用 |
| 4.3 熔断粒度误伤（中） | `FAIL_NON_RETRYABLE`（收件人地址错等数据类错误）计入整个渠道的熔断失败 | 该分支不再 `recordFailure()` |
| 失败记录互相覆盖（核实中发现） | `persistFailure` 按收件人粒度调用，但 recordId=`eventId:channel` 不含收件人，同事件同渠道多收件人互相覆盖 | recordId 追加脱敏后的收件人（iter-70 进一步改为短摘要，见 D-036） |

## 2. 验证

`mvn -B -ntp clean verify` × 3（修复后 / 合并后 / 版本号升级后）均 17/17 模块全绿。

## 3. 遗留（转 iter-70 与 ISSUE-061）

- 修复提交未附测试，无回归保护 → 复核发现新引入卡死路径（iter-70 修复）。
- 4.4 重试 `Thread.sleep` 占工作线程、4.5 队列溢出/停机丢弃无落盘、4.6 指纹对易变参数敏感：未处理，登记 ISSUE-061。
