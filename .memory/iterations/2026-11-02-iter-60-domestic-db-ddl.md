# iter-60 · 信创 DDL（DM/Kingbase/openGauss）+ nx_idempotency 保留期

- **日期**：2026-11-02
- **分支**：`feat/domestic-db-ddl`
- **类型**：feat（设计书 §12.4 信创适配落地 + §12.3 幂等表治理）
- **触发**：STATE.md 未实现清单第 3 项「信创 DDL（DM/Kingbase/openGauss）与
  `nx_idempotency` 保留期」；同时也是 §12.4「各库独立 DDL」承诺的缺失部分。

## 1. 问题

| # | 问题 | 证据 |
| --- | --- | --- |
| 1 | 只提供 mysql/postgresql/h2 三套 DDL，无信创库脚本 | `notifyx-store-jdbc/src/main/resources/sql/` 仅 3 个目录 |
| 2 | `nx_audit.level` 是金仓保留字，且加双引号方案在 MySQL 下不可行 | §12.4 方言雷区对照表 + D-029 背景 |
| 3 | `nx_idempotency` 无过期清理，D-024 明确「暂未清理，需运维/后续任务」 | `notifyx-consumer` 只有幂等写入，无 retention |
| 4 | consumer 幂等 DDL 同样缺信创库脚本 | `notifyx-consumer/src/main/resources/sql/` 仅 mysql/postgresql |

## 2. 方案

**列名修正（破坏性，D-029）**

- `nx_audit.level` → `nx_audit.event_level`：`JdbcAuditRecorder` INSERT 与
  mysql/postgresql/h2 三套 schema.sql 同步修改。加双引号备选方案在 MySQL 下
  （双引号=字符串字面量）不可行，改名是唯一跨库一致的方案。

**信创 DDL（6 个新文件）**

- `notifyx-store-jdbc/src/main/resources/sql/{dm,kingbase,opengauss}/schema.sql`
  - DM：`longtext`→`clob`、`datetime`→`timestamp(0)`、索引独立 `create index`；
  - Kingbase（PG 兼容）：`longtext`→`text`、列名已规避 `level` 保留字；
  - openGauss（PG 兼容）：`longtext`→`text`。
- `notifyx-consumer/src/main/resources/sql/{dm,kingbase,opengauss}/idempotency.sql`（同上方言要点）。

**nx_idempotency 保留期**

- 新增 `JdbcIdempotencyRetention`（`notifyx-consumer`）：
  - 与 `JdbcIdempotencyStore` 一致用 `DriverManager` 直连，不引入 Spring DataSource 自动配置；
  - `purgeBefore(long)` / `purgeOlderThan(int)`；
  - 循环「查一批 key → 按 key IN 删除」，默认 1000/批，标准 `limit`/`in`（跨库可移植）；
  - 调度由部署方负责（`@Scheduled` / CronJob），SDK 不内置后台线程、不默认自动删除。

## 3. 验证

| 测试 | 用例 | 覆盖 |
| --- | --- | --- |
| `JdbcIdempotencyRetentionTest`（新，2） | 按截止时间分批删除 / 按天清理 | 幂等表清理核心路径 |
| `JdbcAuditRecorderTest` / `JdbcAuditRetentionTest` / `JdbcFailureStoreTest` | 既有全通过 | 列名修正后的回归 |

模块级验证：

```text
mvn -B -ntp -pl notifyx-store-jdbc -am test   → BUILD SUCCESS（11 执行 + 2 live 跳过）
mvn -B -ntp -pl notifyx-consumer -am test     → BUILD SUCCESS（含 JdbcIdempotencyRetentionTest）
```

## 4. 兼容性

- **破坏性**：`nx_audit.level` → `event_level`。基于 1.0.0 旧 schema 建表的用户升级 1.0.1
  需 `alter table nx_audit rename column level to event_level`（或用新 DDL 重建）。
- 信创 DDL 为**新增**文件，不改 MySQL/PostgreSQL 既有脚本（除列名修正外结构不变）；
- `JdbcIdempotencyRetention` 为新增 API，不改变既有 `IdempotencyStore` 行为。

## 5. 文档与门禁

- `docs/design.md`：§12.2 表结构 `level` → `event_level`；§12.4 补「落实状态」段落。
- `README.md`：JDBC 建表脚本路径补信创库 + 1.0.1 迁移提示；MQ Consumer 补幂等 DDL 路径与保留期 API。
- `.memory/decisions.md`：新增 D-029（破坏性变更 + 迁移提示）。
- `.memory/STATE.md`：勾选该项 + 新增第 13 节。

## 6. 未纳入

- 信创库真实 CI 冒烟（§12.4 第 6 条）：当前无 DM/Kingbase/openGauss 环境，DDL 按方言清单静态编写，
  真实库验证需用户环境，属后续迭代；
- RabbitMQ DLX/多级 TTL、consumer 多实例分区与死信重试、T1–T24 剩余高危用例：独立迭代。

## 7. 回滚

单提交回滚；列名如需回退需同时回滚 `JdbcAuditRecorder` INSERT 与全部 DDL。