# iter-30 · 审计保留期清理（§12.3）

- **日期**：2026-10-22
- **分支**：`feature/audit-retention` → `master`
- **类型**：feat（JDBC 容量治理）
- **关联**：D-027（新增）、§12.3、iter-24

## 1. 背景

`nx_audit` 是写入量最大的表（§12.3 估算 40 万行/天）。iter-24 只做了写入，
没有保留期清理；设计要求「分批 1000 行删除，避免大事务锁表」。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 无清理会导致表无限增长 | 设计 §12.3 容量治理表 | 新增 `JdbcAuditRetention.purgeBefore/purgeOlderThan` |
| 2 | 一次 `DELETE` 全量会锁表 | 设计明确要求分批 1000 | 「查一批 id → 按 id 删除」，每批默认 1000 |
| 3 | SDK 不应偷偷起后台线程 | 库的使用者可能已有调度器 | 只提供 API，调度交给 `@Scheduled`/CronJob（D-027） |
| 4 | 各库 `DELETE ... LIMIT` 语法不一致 | MySQL 支持、PG 不支持 | 先 `SELECT id ... LIMIT`，再按 id `IN` 删除（三库通用） |

## 3. 改动点

- `notifyx-store-jdbc` 新增 `JdbcAuditRetention`：
  - `purgeBefore(long cutoffMillis)`：循环分批删除，返回删除行数；
  - `purgeOlderThan(int days)`：按天保留（<1 按 1）；
  - 默认批大小 1000，可构造指定。
- 测试 +2：分批删除旧记录/保留新记录；按天清理。
- README JDBC 段补充「保留期清理需自行调度」。

## 4. 验证

```text
mvn -B -pl notifyx-store-jdbc -am test → 10 tests 通过
mvn -B clean verify → BUILD SUCCESS，17 模块，244 tests（13 live 跳过）
Spotless / Checkstyle / JaCoCo 全绿
```

## 5. 回滚

删除 `JdbcAuditRetention` 与测试即可；不影响写入路径。

## 6. 下一步

- `nx_idempotency` 的保留期清理（consumer）。
- RabbitMQ DLX/重试；consumer 多实例分区消费。
- 信创 DDL 适配与 k8s 生产化。
