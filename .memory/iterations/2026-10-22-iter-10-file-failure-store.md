# iter-10 · 本地文件失败落盘（设计书 §9.9）

- **日期**：2026-10-22
- **分支**：`feat/file-failure-store` → `master`
- **类型**：feat（P1 可靠性收尾）
- **关联**：D-003（部分落地）、D-017（新增）、ISSUE-015（新增）

## 1. 背景

D-003 一直把失败落盘标为「只有有界内存」：进程重启后失败记录全丢，无法作为事后追溯依据。
§9.9 要求 `~/.notifyx/pending/yyyyMMddHH/<eventId>.json` 的结构、乐观锁状态机与磁盘上限。
本次实现 local-file 版本；JDBC 版属 P2（信创库适配一起做）。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | core 零依赖原则与 JSON 序列化冲突 | core 仅允许 SLF4J，不能引 Jackson | 手写扁平 JSON 读写（引号/换行/制表符转义 + 反转义），约 100 行；损坏文件跳过而不是让扫描失败 |
| 2 | recordId 可能含路径分隔符，直接拼文件名有目录穿越风险 | `eventId` 通常安全但 API 允许任意字符串 | 文件名白名单过滤 `[^A-Za-z0-9._-]` |
| 3 | 通知持续失败可能写满磁盘 | §9.9 磁盘保护 | 默认 10,000 文件 / 100MB；超限先删最旧 DEAD，再删最旧文件，累计 `overflowCount()` |
| 4 | 首版容量清理在遍历 `files` 时 `remove`，有 `ConcurrentModificationException` 风险；且保存后没有累加字节数 | 代码评审发现 | 改为遍历副本 + 删除时扣减 `approxBytes`；保存后 `approxBytes += 文件大小` |
| 5 | 写一半崩溃会留下半截 JSON | 直接写目标文件 | 先写 `.tmp` 再 `ATOMIC_MOVE`（失败回退普通 move） |
| 6 | 自动重投缺少「完整事件报文」 | `persistFailure` 的 envelope 目前只是 eventId | 记录为 ISSUE-015；需要先设计 `NotifyEnvelope` 序列化（P2/P3 与 MQ 一起） |

## 3. 改动点

**core 新增 `LocalFileFailureStore implements FailureStore`**：

- 目录 `<root>/yyyyMMddHH/<recordId>.json`；构造时扫描建索引。
- `save`：原子写、索引、字节计数、容量维护；IO 异常只 warn（落盘失败不能反向影响主流程）。
- `loadPending(limit)`：扫描 → PENDING 且 `nextRetryAt <= now` → 按时间升序 → limit；
  `limit<=0` 返回空。
- `updateStatus(recordId, expectedVersion, status, attempts)`：读文件比对版本，匹配才写新文件并 `version+1`。
- `pendingCount()` / `recordCount()` / `overflowCount()`。
- 文件名安全化、损坏文件跳过、时间分片。

**autoconfigure**：

- `NotifyxProperties.Fallback`：`store=memory|file`、`dir`、`max-files`、`max-bytes`（DataSize）。
- `notifyxFailureStore(properties)`：默认内存；`store=file` 时创建 `LocalFileFailureStore`（默认目录 `~/.notifyx/pending`）。
- 诊断表已有 SPI 表格，落盘实现会显示为 `LocalFileFailureStore`。

**测试（8 个）**：

| 测试 | 覆盖 |
| --- | --- |
| `LocalFileFailureStoreTest`（6） | 重启后仍可读（含引号/换行 reason）、乐观锁、容量淘汰、损坏文件跳过、到期过滤与排序、未知记录 |
| `FailureStoreSelectionTest`（2） | 默认内存；`store=file` 使用文件实现并落在指定目录 |

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，12 模块，139 tests，0 failures
  core 80 / spring 6 / autoconfigure 21 / logback 20 / webhook 12
→ Spotless 0 / Checkstyle 0 / JaCoCo 达标
```

## 5. 回滚

删除 `LocalFileFailureStore`、`Fallback` 配置与 bean 分支即可；默认仍走内存实现，行为不变。

## 6. 下一步

- 自动补偿（ISSUE-015）：先设计 `NotifyEnvelope` 序列化，再实现补偿任务（CAS → 重投 → SENT/DEAD）。
- email 渠道契约测试（ISSUE-010）。
- JDBC store 与 Redis store 属 P2（随信创适配）。
