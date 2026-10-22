# iter-24 · JDBC 审计与失败补偿存储

- **日期**：2026-10-22
- **分支**：`feature/jdbc-store` → `master`
- **类型**：feat（P2：JDBC store）
- **关联**：D-021（新增）、iter-22/23、journal/2026-10-22

## 1. 背景

T0 只有控制台审计与内存/文件失败落盘；设计书 §12.2 要求 `nx_audit` 与
`nx_failure_record`（JDBC 版 FailureStore），供有数据库、无 Redis 的信创场景使用。
本轮实现 `notifyx-store-jdbc`，用真实 MySQL 8.0 与 PostgreSQL 16 验证。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 设计模块名为 `store-mybatisplus`，但 MyBatis-Plus 会引入版本耦合 | §5.4 模块表；信创适配要求驱动作 optional | 改用**纯 JDBC**（标准 SQL + DataSource），记为 D-021；先把契约/乐观锁跑通，MP 版可作为后续增强 |
| 2 | 各库 DDL 语法差异（text/datetime/索引内联） | 设计 §12.4 方言雷区 | 按库提供 `sql/mysql`、`sql/postgresql`、`sql/h2`；测试按方言加载 |
| 3 | 审计主键 `id bigint` 无自增，多库自增/序列语法不同 | 设计明确禁止复用 MySQL 脚本 | 应用侧生成随机 63 位正数（UUID 高位），不依赖数据库序列 |
| 4 | 审计写失败不能影响通知 | 审计是观测旁路 | `JdbcAuditRecorder` 捕获 SQLException 只 warn |
| 5 | 多实例扫失败表会重复补偿 | §9.9 要求乐观锁 | `updateStatus/requeue` 使用 `where record_id=? and version=?`，影响行数为 0 即跳过 |
| 6 | DDL 文件首行注释导致测试跳过建表 | 真实 MySQL 首次跑：`Table 'notifyx_test.nx_audit' doesn't exist` | 测试初始化按行剔除 `--` 注释后再按 `;` 执行 |

## 3. 改动点

- 新增模块 `notifyx-store-jdbc`（14 个模块）：
  - `JdbcAuditRecorder`：预编译 INSERT `nx_audit`，时间转 `Timestamp`，异常只 warn；
  - `JdbcFailureStore`：save / loadPending（`status='PENDING' and next_retry_at<=?`）/
    updateStatus / requeue / pendingCount，全部标准 SQL；
  - `JdbcStoresAutoConfiguration`：有 `DataSource` 时，`notifyx.audit.store=jdbc` 装配审计、
    `notifyx.fallback.store=jdbc` 装配失败存储；排在 `NotifyxAutoConfiguration` 之前，
    让默认的 Logging/InMemory 实现让位；
  - DDL：`sql/mysql`、`sql/postgresql`、`sql/h2`。
- BOM/父 POM 接入模块。
- 测试：H2 单测 8 个 + 真实库 live 2 个（MySQL/PostgreSQL 各跑一遍，默认跳过）。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，14 模块，218 tests（9 个 live 默认跳过）
→ store-jdbc 覆盖率 85.4%（门禁 60%）；Spotless / Checkstyle 全绿

真实数据库（10.10.10.102）：
MySQL 8.0.46   → JdbcStoresLiveTest 2/2 通过
PostgreSQL 16.15 → JdbcStoresLiveTest 2/2 通过
（审计写入可查询；失败记录 save → CAS → requeue → pendingCount）
```

## 5. 回滚

删除模块、BOM/POM 条目与 SQL 目录即可；不引该模块时默认审计/失败存储行为不变。

## 6. 下一步

- MQ 总线（Kafka/RabbitMQ）与 consumer（幂等表 `nx_idempotency` 已设计）。
- 信创 DDL（DM/Kingbase/openGauss）与 CI 冒烟。
- 审计容量治理（retention/sampling/batch）尚未实现。
