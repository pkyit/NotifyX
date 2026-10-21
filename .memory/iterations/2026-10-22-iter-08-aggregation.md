# iter-08 · T0 实例内聚合（设计书 §9.5）

- **日期**：2026-10-22
- **分支**：`feat/aggregation` → `master`
- **类型**：feat（P1 可靠性）
- **关联**：ISSUE-007（部分关闭）、D-014（新增）

## 1. 背景

§9.5 要求把窗口内被去重的事件收拢成一条摘要，避免「100 个实例各发一条相同告警」。
完整方案有 T0/T1/T2 三档；本次只有 core + 内存实现，因此做 **T0 实例内聚合**，
并在 README/诊断表里明确「多实例下每个实例各发一条摘要」的边界（诚实声明是设计要求）。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 被去重事件在管线的 REJECT 分支直接丢弃，没有聚合入口 | `NotifyPipeline.handle()`：`REJECT → reject() → return` | REJECT 且 reason=`duplicate` 时调 `aggregator.record(...)`，再走原审计 |
| 2 | 聚合摘要本身可能被去重/限流吞掉 | 摘要与原始事件同 scene | 摘要带唯一 `dedupeKey=agg:<原指纹>:<firstAt>`，首次必过 |
| 3 | 没有窗口结束的触发点（只有异步执行器，不能延时） | `NotifyTaskExecutor.execute` 立即执行 | `Aggregator` 自带单线程 daemon 调度器，窗口到期 flush |
| 4 | 关闭时聚合桶会丢 | 生命周期只管执行器与渠道 | `NotifyxLifecycle` 在「排空执行器之后、关闭渠道之前」调用 `aggregator.close()`，同步汇总剩余桶 |
| 5 | 首次写测试时 3 条事件全部投递，聚合没触发 | 测试用不同 `bizId`，指纹不同（`sha256(scene‖bizId‖params)`） | 聚合键是指纹，不是 scene；测试改为同 `bizId`，并验证 count=2 |

## 3. 改动点

**core 新增 `io.github.pkyit.notifyx.core.pipeline.Aggregator`**：

- `Config(enabled, window, maxEntries)`，`record(event, level, dedupeKey, instanceId)`。
- 桶键 = dedupeKey；桶内维护 count、最高 Level、首/末样本（300 字符截断）、首/末时间、实例集合。
- 窗口到期由 daemon `ScheduledExecutorService` 触发；`maxEntries` 超限时先汇总最旧桶。
- 摘要事件：同 scene、最高 level、`dedupeKey=agg:<key>:<firstAt>`、
  params 带 `aggregateCount/aggregateScene/aggregateWindowSeconds/aggregateFirstAt/aggregateLastAt/aggregateInstances/aggregateFirstSample/aggregateLastSample`、
  tags `aggregate=true`；标题 `【SCENE】聚合告警：N 条 / Ws`。
- `close()`：先 `flushAll()` 再停调度器（关闭窗口不丢摘要）。

**core 管线**：

- `NotifyPipeline` 新增 `aggregator` 字段与 builder 项；去重 REJECT 时记录。
- 摘要通过注入的 sink 重新进入 `pipeline.handle`（走完整漏斗与渠道）。

**autoconfigure**：

- `NotifyxProperties.Aggregate`：`enabled=false`（默认关，避免行为突变）、`window=10s`、`max-entries=10000`。
- `notifyxAggregator` Bean；管线构建后 `aggregator.sink(pipeline::handle)`。
- `NotifyxLifecycle` 增加 `AutoCloseable` 资源参数，关闭顺序：执行器排空 → 聚合汇总 → 渠道关闭。
- 启动诊断表增加聚合状态与「多实例不合并」提示。

**测试（新增 5 个）**：

| 测试 | 覆盖 |
| --- | --- |
| `AggregatorTest` | 窗口收拢/级别取高/样本/实例数、关闭即时汇总、不同指纹分桶、禁用不产出 |
| `AggregationPipelineTest` | 同指纹 3 条 → 首条直达 + 摘要 count=2，摘要不被去重吞掉 |

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，12 模块，123 tests，0 failures
  core 66 / spring 6 / autoconfigure 19 / logback 20 / webhook 12
→ Spotless 0 / Checkstyle 0 / JaCoCo 达标
```

## 5. 回滚

删除 `Aggregator`、管线 `aggregator` 字段、autoconfigure Bean/配置、生命周期参数即可；
默认关闭，未显式开启时行为与之前完全一致。

## 6. 下一步

- 升级策略（§9.6）：需要「按场景升级级别/渠道」的机制，排在 iter-09。
- 失败落盘补偿（D-003）：未排空事件落盘要等文件实现。
- email 契约测试、覆盖率债（core 67.7% → 90%）。
- 跨实例聚合（T1 Redis）随 Redis store 一起做（P2）。
