# iter-39 · Redis 聚合锁释放修复

- **日期**：2026-10-23
- **分支**：`bugfix/iter-39-redis-lock` → `master`
- **类型**：fix（T1 跨实例聚合并发语义 + 回归测试）
- **触发**：21:00 夜间巡检；审计 `RedisAggregator.flush` 的锁生命周期。

## 1. 问题

`RedisAggregator.flush()` 用 `SET NX` 抢摘要锁，但成功后**从不删除锁**：

```java
Boolean locked = template.opsForValue().setIfAbsent(lockKey(key), "1", lockTtl());
...
sink.accept(summary);
// lock 未释放，只能等 TTL
```

`lockTtl = window * 2 + 5s`。如果同一个 `dedupeKey` 在下一个窗口再次出现：

- t0 窗口 1：抢锁 → 发摘要 → 锁保留；
- t0+window 窗口 2：flush 抢锁失败（旧锁还没过期）→ 摘要被跳过；
- 直到 t0+2window+5s 锁过期，后续窗口才可能恢复。

结果是**每隔一个窗口丢一次摘要**，而且多实例下无法判断是“别的实例正在发”还是“旧锁没释放”。

## 2. 修复

- 锁值改为每次 flush 唯一的 UUID token；
- 抢锁成功后，在 `finally` 中用 Lua `compare-and-delete` 释放：
  ```lua
  if redis.call('GET', KEYS[1]) == ARGV[1] then
      return redis.call('DEL', KEYS[1])
  end
  return 0
  ```
- 释放失败只 debug，不覆盖摘要主流程；锁 TTL 仍是兜底。
- token 校验避免误删其他实例在 TTL 过期后新抢到的锁。

## 3. 验证

新增 `RedisAggregatorTest.shouldReleaseLockAfterFlush`：

- 抢锁成功、摘要投递后，捕获 `template.execute` 的脚本，断言存在包含 `DEL` 的释放脚本；
- `RedisAggregatorTest` 6/6 通过（原 5 + 新增 1）。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，280 用例（267 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 摘要内容、抢锁语义、窗口计数不变；
- 只修正锁生命周期，减少“旧锁挡住下一窗口”的丢摘要；
- 无 public API 变更。

## 5. 未纳入

- A02/A03、A04、A06、A07、A12、A14、A15；
- Redis 聚合的“所有实例崩溃则摘要丢失”边界（架构已声明，需持久化/补偿方案）；
- 信创 DDL、DLX/死信、consumer 多实例、Admin。

## 6. 回滚

单提交回滚；无 schema/配置变化。
