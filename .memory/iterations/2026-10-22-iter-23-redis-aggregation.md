# iter-23 · T1 跨实例聚合（Redis）

- **日期**：2026-10-22
- **分支**：`feature/redis-aggregation` → `master`
- **类型**：feat（设计书 §9.5 T1）
- **关联**：D-020（新增）、journal/2026-10-22、iter-22

## 1. 背景

T0 聚合只在单实例生效：100 个实例同时报错会发 100 条摘要。§9.5 要求 T1 用 Redis
`INCR`/`HSET` 累计，窗口结束时用 `SET NX` 抢锁，只让一个实例发摘要。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 管线只认具体 `Aggregator` 类型 | `NotifyPipeline.aggregator` 为 `Aggregator`，Redis 实现无法注入 | 抽出 `EventAggregator` 接口（record/sink/flushAll/close），T0 `Aggregator` 实现它 |
| 2 | T0/T1 谁生效取决于自动装配顺序 | T0 bean `@ConditionalOnMissingBean` 若先创建，Redis 版本被忽略 | Redis 配置继续 `beforeName`；T0 bean 返回类型改 `EventAggregator`，测试固定 Redis 优先 |
| 3 | 多实例同时 flush 会重复发摘要 | 每个实例本地都调度了窗口结束任务 | `SET NX` 摘要锁（TTL = 2×窗口 + 5s），抢不到直接返回 |
| 4 | 实例在窗口结束前崩溃会丢摘要 | 每实例只调度本地见过的事件，不做 SCAN | 诚实记录为边界；数据 TTL 自动清理，不补发（避免引入扫描/协调复杂度） |
| 5 | Redis 聚合失败不能影响通知 | 聚合在去重过滤链内调用 | `record/flush` 全部 catch RuntimeException，仅 warn |
| 6 | 计数/样本/实例集合需要原子写 | 多实例并发 HINCRBY/SADD/EXPIRE | 单个 Lua 脚本完成记录；flush 端用 Hash 读取 + Set size |

## 3. 改动点

**core**：

- 新增 `EventAggregator` 接口；`Aggregator` 实现它；`NotifyPipeline` 字段/Builder 改为接口类型；
  `NotifyxAutoConfiguration` 的 `notifyxAggregator`/`notifyxLifecycle` 参数改为 `EventAggregator`。

**store-redis**：

- `RedisAggregator`：
  - `record`：Lua `HINCRBY count` + 首次写 scene/firstAt/firstSample、每次写 lastAt/lastSample、
    `HSETNX level`、`SADD instances`、双 key `PEXPIRE`；每个 key 本地只调度一次 flush；
  - `flush`：`SET NX` 锁 → 读 Hash/实例数 → 构造摘要（`aggregateCount/Instances/FirstSample/LastSample`）
    → 删除 Hash/实例集合 → `sink.accept`；锁保留到 TTL，避免并发重复；
  - 本地待调度键上限 `maxEntries`，超限先 flush 最旧；Redis 异常只 warn。
- `RedisAggregationProperties`（复用 `notifyx.aggregate.*`：enabled/window/max-entries）。
- 自动装配新增 `redisAggregator` Bean：`@ConditionalOnMissingBean(EventAggregator.class)` +
  `@ConditionalOnProperty(notifyx.aggregate.enabled=true)`。

**测试（+11）**：

| 测试 | 覆盖 |
| --- | --- |
| `RedisPrimitivesTest`（4） | 去重/限流脚本结果、null、permits<=0、键前缀 |
| `RedisAggregatorTest`（5） | 抢锁成功摘要字段、抢锁失败不投递、记录异常吞掉、disabled、空 Hash |
| `RedisBeansTakePrecedenceTest`（+2） | 有 Redis 且开启聚合 → RedisAggregator；无 Redis → T0 |
| `RedisAggregationLiveTest`（2，默认跳过） | 单实例 3 条/2 实例；两实例并发只发 1 条 |

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，13 模块，210 tests（7 个 live 默认跳过）
→ store-redis 覆盖率 79%（门禁 60%）；Spotless/Checkstyle 全绿

真实 Redis（k8s，port-forward 16379）：
RedisAggregationLiveTest → Tests run: 2, Failures: 0
  - 单实例窗口内 3 条、2 个实例 → 1 条摘要，aggregateCount=3
  - 两个实例并发记录 → 全局只有 1 条摘要，aggregateCount=2
```

## 5. 回滚

删除 `EventAggregator` 抽象与 `RedisAggregator`/属性/Bean，管线字段改回 `Aggregator` 即可；
不引 store-redis 时行为不变（T0）。

## 6. 下一步

- JDBC store（审计/补偿）与 MQ（Kafka/RabbitMQ）总线，镜像已具备。
- 生产化 Redis（密码/TLS/Sentinel）与聚合的崩溃补发策略评估。
