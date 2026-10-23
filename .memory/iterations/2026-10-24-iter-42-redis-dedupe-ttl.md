# iter-42 · Redis 去重 TTL 毫秒精度修复

- **日期**：2026-10-24
- **分支**：`bugfix/iter-42-redis-dedupe-ttl` → `master`
- **类型**：fix（T1 去重窗口精度 + 回归测试）
- **触发**：00:00 夜间巡检；对比 `MemoryDedupeStore` 与 `RedisDedupeStore` 的窗口语义。

## 1. 问题

`RedisDedupeStore.tryAcquire()` 用 `SET NX EX`，参数是 `window.toSeconds()`：

```java
long seconds = Math.max(1L, window == null ? 1L : window.toSeconds());
```

- 亚秒窗口（如 `Duration.ofMillis(100)`）被截断为 0，再被 `Math.max(1)` 抬成 **1 秒**；
- 去重窗口比配置更长，故障恢复/短窗口测试会被多压制最多约 1 秒；
- 与 `MemoryDedupeStore` 的毫秒语义不一致，切换存储会改变行为。

## 2. 修复

- 脚本改为 `SET key 1 NX PX <millis>`；
- `tryAcquire` 使用 `window.toMillis()`，最小 1ms；
- Javadoc 从 `SET NX EX` 改为 `SET NX PX`。

## 3. 验证

新增 `RedisPrimitivesTest.dedupeShouldUseMillisecondTtl`：

- `Duration.ofMillis(100)` 传给脚本的参数必须是 `"100"`，不能是 `"1"`；
- 捕获 `template.execute` 的 `Object[]` 断言。

`RedisPrimitivesTest` 5/5 通过。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，293 用例（280 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 分钟级窗口的 TTL 与原来等价（例如 1m = 60000ms）；
- 亚秒窗口现在按毫秒生效，不再被放大到 1 秒；属于修复；
- Redis 版本要求不变（`SET PX` 自 2.6.12 起支持）。

## 5. 未纳入

- A02/A03、A04、A06、A07、A12 其余属性、A14、A15；
- 信创 DDL、DLX/死信、consumer 多实例、Admin。

## 6. 回滚

单提交回滚；无 schema/配置变化。
