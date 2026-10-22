# iter-27 · Consumer 持久化幂等（Redis / JDBC）

- **日期**：2026-10-22
- **分支**：`feature/consumer-idempotency` → `master`
- **类型**：feat（P3：consumer 可靠性）
- **关联**：D-024（新增）、§11.4、iter-26

## 1. 背景

iter-26 的 consumer 只有内存幂等，重启即失效、无法多实例。设计 §11.4 要求三档：
Redis `SET NX` / `nx_idempotency` 表 / 内存。本轮补齐 Redis 与 JDBC 两档。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 内存幂等重启失效 | iter-26 文档已声明 | 新增 `RedisIdempotencyStore`（`SET NX EX`）与 `JdbcIdempotencyStore`（唯一键） |
| 2 | Redis 故障不能导致所有通知被丢 | 幂等是旁路 | Redis 异常 → 降级内存并 warn（可能重复） |
| 3 | JDBC 非重复类异常的处理 | 唯一键冲突 SQLState=23xxx | 23 类视为重复；其它异常 fail-open（放行）并告警 |
| 4 | 三档实现需要可切换 | 设计 `store` 配置 | `notifyx.consumer.idempotency-store=memory\|redis\|jdbc` 条件装配 |
| 5 | 生产 DDL 缺失 | 设计 §12.2 有 `nx_idempotency` | consumer 增加 mysql/postgresql DDL；H2 测试内建表 |
| 6 | 消费者模块引入了 Redis/JDBC 依赖 | 需要 DataSource/StringRedisTemplate | 加 `spring-boot-starter-data-redis`、`spring-boot-starter-jdbc`；未配置时默认内存不受影响 |

## 3. 改动点

- `RedisIdempotencyStore`：`nx:idem:<key>`，`setIfAbsent` + TTL；异常降级 `MemoryIdempotencyStore`；
- `JdbcIdempotencyStore`：`insert into nx_idempotency`，唯一键冲突返回 false，其它 SQL 异常 fail-open；
- `ConsumerConfiguration`：按 `notifyx.consumer.idempotency-store` 条件装配三档；
- `ConsumerProperties` 增加 `idempotencyStore`；
- DDL：`notifyx-consumer/src/main/resources/sql/{mysql,postgresql}/idempotency.sql`；
- 测试（+5）：Redis 单测 2、JDBC H2 2、真实 Redis live 1。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，16 模块，236 tests（12 个 live 默认跳过）
→ Spotless / Checkstyle / JaCoCo 全绿

真实 Redis（k8s，port-forward 16379）：
RedisIdempotencyLiveTest → Tests run: 1, Failures: 0
  同一 key 首次 true、重复 false（SET NX）
```

## 5. 回滚

删除两个 Store 与条件 Bean，恢复只装配内存实现即可；默认 `memory` 行为不变。

## 6. 下一步

- 多实例 consumer（Kafka 分区消费 + 持久化幂等已具备基础）、死信/重试。
- RabbitMQ bus。
- `nx_idempotency` 的过期清理（retention）。
