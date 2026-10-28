# iter-55 · `dedupe.store` / `rate-limit.store` 选择生效

- **日期**：2026-10-28
- **分支**：`bugfix/iter-55-store-selection` → `master`
- **类型**：fix（配置契约 + 自动装配条件）
- **触发**：20:00 夜间巡检；审计 A12 未生效配置。

## 1. 问题

设计书把 `notifyx.dedupe.store` / `notifyx.rate-limit.store` 定义为存储选择：

- `memory`：强制内存（多实例会重复）；
- `redis`：强制 Redis（不可用降级内存）；
- `jdbc`：数据库去重（信创场景）；
- `auto`：自动探测。

实际 `RedisStoresAutoConfiguration` 只看 `StringRedisTemplate` 是否存在，完全不读这两个属性：

- 配 `dedupe.store=memory` 且 classpath 有 Redis 时，仍装配 `FallbackDedupeStore`；
- 配 `jdbc` 时同样被 Redis 覆盖（JDBC 目前也未实现去重存储）。

## 2. 修复

给 Redis 去重/限流 Bean 增加条件：

```java
@ConditionalOnExpression("'${notifyx.dedupe.store:auto}' == 'auto' or '${notifyx.dedupe.store:auto}' == 'redis'")
@ConditionalOnExpression("'${notifyx.rate-limit.store:auto}' == 'auto' or '${notifyx.rate-limit.store:auto}' == 'redis'")
```

- `auto` / `redis`：保持原行为，Redis 可用时接管；
- `memory`：Redis 自动装配跳过，由 `NotifyxAutoConfiguration` 的 `MemoryDedupeStore`/`MemoryRateLimiter` 兜底；
- `jdbc`：同样跳过 Redis；JDBC 去重本身仍未实现，会落到内存（已在文档/记忆中标注为未完成）。

## 3. 验证

新增 `RedisStoresAutoConfigurationTest`：

- `dedupe.store=memory` + `rate-limit.store=memory` → 不装配 Redis 实现；
- 显式 `redis` → 仍装配 `FallbackDedupeStore` / `FallbackRateLimiter`。

`RedisStoresAutoConfigurationTest` 6/6。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，316 用例（303 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 默认 `auto`，现有 Redis 自动装配行为不变；
- 显式 `memory` 现在真正生效（之前被 Redis 覆盖）；
- `jdbc` 仍无实现，配该值不会启用 Redis，但会静默使用内存；后续需单独实现或 fail-fast。

## 5. 未纳入

- JDBC 去重存储实现（信创 DDL，需决策）；
- ISSUE-039、A02/A03/A04、A06 完整方案、A14；
- 1.0.0 已发布，本轮为 1.0.1 候选。

## 6. 回滚

单提交回滚；无 schema 变更。
