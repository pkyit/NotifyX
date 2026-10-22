# iter-22 · Redis 分布式去重/限流（T1 能力梯度）

- **日期**：2026-10-22
- **分支**：`feature/redis-store`（**过程失误**：实际提交直接落在 `master`，事后补建同名指针；
  未重写已推送历史，记录在 journal 的 Git 习惯一节）
- **类型**：feat（P2 第一块：Redis 存储）
- **关联**：D-019（新增）、journal/2026-10-22、deploy/k8s/redis.yaml

## 1. 背景

设计书 §9.4 要求 T1（有 Redis）下提供跨实例去重与限流，且 Redis 故障不能拖垮通知。
本轮新增 `notifyx-store-redis` 模块，并在真实 k8s Redis 6.2.23 上做了协议级验证。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 自动装配顺序决定谁生效 | `NotifyxAutoConfiguration` 的 `@ConditionalOnMissingBean` 若先执行，会创建内存实现，Redis 配置被忽略 | Redis 配置加 `beforeName = "...NotifyxAutoConfiguration"`，并用 `RedisBeansTakePrecedenceTest` 固定 |
| 2 | 真实 Redis 测试用 1 秒窗口偶发失败 | 第三、四次调用间隔约 3.2s（kubectl port-forward 延迟），1s 窗口已过期，脚本正确地再次放行 | 不是脚本 bug；用例改为 30s 窗口验证阈值，另用 2s 睡眠单独验证过期 |
| 3 | `size()` 用 `KEYS` 会阻塞 Redis | 设计未要求精确诊断值 | 返回 -1（未知），避免生产事故 |
| 4 | Redis 挂了不能让通知发不出 | 设计 §9.4 明确要求降级 | `FallbackDedupeStore`/`FallbackRateLimiter` 捕获异常回退内存，连续失败熔断 30s |
| 5 | 成员唯一性不能用 `math.random()` | 设计 §9.4 的修正说明 | Lua 用 `nowMs:count` 作为 ZSET member |

## 3. 改动点

- 新增模块 `notifyx-store-redis`（13 个模块）：
  - `RedisDedupeStore`：`SET NX EX` Lua；`release` 删除槽位；`size=-1`。
  - `RedisRateLimiter`：ZSET 滑动窗 Lua（清理/计数/写入/过期原子化）；`currentCount` 同口径。
  - `FallbackDedupeStore` / `FallbackRateLimiter`：失败降级 + 熔断（`RedisDegradation`）。
  - `RedisStoresAutoConfiguration`：`@ConditionalOnClass(StringRedisTemplate)`、
    `@ConditionalOnBean`、`enabled` 开关、排在管线自动装配之前。
  - `RedisStoreProperties`：`key-prefix`/`failure-threshold`/`open-duration`。
- BOM 与父 POM 增加模块；`deploy/k8s/redis.yaml`（Deployment + Service，镜像
  `registry.cn-hangzhou.aliyuncs.com/pkyit/redis:6.2.23`）。

**测试（9 个 + 5 个 live）**：

| 测试 | 覆盖 |
| --- | --- |
| `FallbackStoreTest`（3） | 连续失败熔断后不再打 Redis、成功不降级、限流降级 |
| `RedisStoresAutoConfigurationTest`（4） | 有模板才装配、无模板/关闭回退、配置绑定 |
| `RedisBeansTakePrecedenceTest`（2） | Redis 优先于内存；无 Redis 仍是内存 |
| `RedisStoresLiveTest`（5，默认跳过） | SET NX EX、release、窗口过期、滑动窗限流、降级包装真实 Redis |

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，13 模块，199 tests（5 个 Redis live + 1 个 email live 默认跳过）
→ store-redis 覆盖率 65.2%（门禁 60%）；其它模块门禁通过

k8s：
kubectl apply -f deploy/k8s/redis.yaml → redis-65d86fd5c4-j8trr Running（k8s-node2）
NOTIFYX_REDIS_HOST=127.0.0.1 NOTIFYX_REDIS_PORT=16379 \
  mvn -B -pl notifyx-store-redis test -Dtest=RedisStoresLiveTest
→ Tests run: 5, Failures: 0
```

## 5. 回滚

删除 `notifyx-store-redis` 模块、BOM/POM 条目与 `deploy/k8s/redis.yaml` 即可；
业务方不引该模块时行为完全不变（T0 仍零依赖启动）。

## 6. 下一步

- T1 跨实例聚合（Redis INCR/HSET + 抢锁发摘要）。
- JDBC store（审计/补偿）与 MQ（Kafka/RabbitMQ）总线。
- 生产化：Redis 密码/TLS、Sentinel/Cluster 连接配置。
