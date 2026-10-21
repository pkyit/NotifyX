# iter-16 · 失败自动补偿（设计书 §9.9 状态机收尾）

- **日期**：2026-10-22
- **分支**：`feat/compensation` → `master`
- **类型**：feat（P1 可靠性闭环）
- **关联**：ISSUE-015（关闭）、D-018（新增）

## 1. 背景

iter-10 把失败记录落了盘、iter-15 把报文做成了可还原的 `NotifyEnvelope`，
但「谁来重投、怎么判断成功、失败几次放弃」还没有实现。本轮补齐状态机执行侧。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 管线 `handle` 返回 void，补偿无法判断投递成败 | `NotifyPipeline.handle` 只 catch 后记日志 | 改为返回 boolean（REJECT/异常 false、DEFER true、dispatch 无失败 true）；历史调用方忽略返回值不受影响 |
| 2 | `FailureStore` 无法把记录退回 PENDING 并设置下次重试时间 | 只有 `updateStatus`，不会改 nextRetryAt | SPI 增加 `requeue(...)` 默认方法；InMemory / LocalFile 分别实现（保持版本 +1） |
| 3 | 自定义 `NotifyKit` 没有同步语义 | `NotifyKit` 6 方法全是 void（设计 6.7 刻意克制） | `DefaultNotifyKit` 增加 `deliverSync(event)`；补偿器优先用真实结果，自定义实现退化为「提交成功」并在 D-018 声明 |
| 4 | 多实例可能重复补偿 | §9.9 方案 A | 采用乐观锁 CAS：`updateStatus(id, version, SENDING, ...)` 失败即跳过 |
| 5 | 关闭时调度线程泄漏 | 自建 daemon 调度器 | `AutoCloseable` + `@Bean(destroyMethod="close")`；`enabled=false` 不启动调度但允许手动 `runOnce()` |
| 6 | 无法解码的报文会无限重试 | 坏 JSON 或未来 schemaVersion | 直接转 `DEAD`（人工介入），不消耗重试次数 |

## 3. 改动点

**core**：

- `NotifyPipeline.handle` 返回 `boolean`（补偿/测试可判断成败；异步执行器忽略）。
- `DefaultNotifyKit.deliverSync(NotifyEvent)`：同步投递并返回结果。
- `FailureStore.requeue(recordId, expectedVersion, attempts, nextRetryAt)`：默认退化到
  `updateStatus(PENDING)`；`InMemoryFailureStore` / `LocalFileFailureStore` 实现真正退避。

**autoconfigure**：

- 新增 `FailureCompensationRunner`：
  - 定时（默认 60s）`loadPending(batchSize)`；手写 `runOnce()` 便于测试/补跑；
  - CAS → `SENDING` → 解码 → `deliverSync` → `SENT`；
  - 失败且未达上限 → `requeue`（`retryBackoff` 默认 5m）；达到 `maxAttempts` → `DEAD`；
  - 解码失败 → 直接 `DEAD`；任何异常只 warn/error，不让调度线程退出。
- `NotifyxProperties.Fallback.Compensation`：`enabled=false`、`interval=60s`、`batch-size=50`、
  `max-attempts=5`、`retry-backoff=5m`。
- Bean 仅在 `notifyx.fallback.compensation.enabled=true` 时装配，`destroyMethod=close`。

**测试（7 个新增）**：

| 测试 | 覆盖 |
| --- | --- |
| 补偿成功 | 记录变 SENT、事件被重投、pending 清空 |
| 失败退避 | PENDING + attempts/version 递增，第三次转 DEAD |
| 报文损坏 | 直接 DEAD、不投递、只写一条 error |
| CAS 失败 | 被抢锁时跳过、不投递 |
| 调度开关 | enabled=false 不运行、close 幂等 |
| 真实管线集成 | `DefaultNotifyKit` + 真实 pipeline + capture 渠道 → 渠道收到、状态 SENT |
| 自动装配 | 默认无补偿器、开启后装配单例 |

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，12 模块，186 tests，0 failures
  core 99 / spring 17 / autoconfigure 32 / email 6 / logback 20 / webhook 12
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 5. 回滚

删除 `FailureCompensationRunner`、`Compensation` 配置与 Bean 即可；`handle` 返回值与
`requeue` 为向后兼容扩展（默认实现/忽略返回值），可保留。

## 6. 下一步

- 补偿的「单实例扫描」优化（ShedLock/MQ 单消费者）属 P2/P3，随 MQ 模式一起。
- core 覆盖率 79.8% → 90%；T1–T24 剩余用例；真实 Central 发布验证（需凭据）。
