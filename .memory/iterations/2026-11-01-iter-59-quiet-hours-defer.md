# iter-59 · 免打扰 defer 补全：夜间事件延后到窗口结束再发

- **日期**：2026-11-01
- **分支**：`feat/quiet-hours-defer`
- **类型**：feat（设计书 §9.3 承诺补全）
- **触发**：只读核查发现「夜间免打扰」在默认装配下等同静默丢弃——
  `QuietHoursFilter` 的 DEFER 裁决依赖 `DeferredHandler`，但全仓库没有任何实现与装配，
  `NotifyPipeline.defer()` 退化为 `reject("deferred-unsupported")`，事件不进 FailureStore、不聚合、不重投。

## 1. 问题

| # | 问题 | 证据 |
| --- | --- | --- |
| 1 | `DeferredHandler` 只有 SPI 定义，无实现、无装配 | 修复前全仓库仅 `NotifyPipeline` 3 处引用（字段/Builder） |
| 2 | `defer=true`（默认）+ 免打扰命中 → 事件消失，仅审计 `SUPPRESSED` | `NotifyPipeline.defer()`：handler 为 null 时 `reject("deferred-unsupported")` |
| 3 | 设计书 §9.3 承诺「其余 defer 到工作时间」 | `docs/design.md` §9.3 限流手段表 |
| 4 | 丢弃不可区分：`notifyx.filter.rejected` 无 reason 标签 | `MicrometerNotifyMetrics.filterRejected` 只有 filter/scene |

## 2. 方案（T0 内存延后队列）

**core**

- 新增 `InMemoryDeferredHandler implements DeferredHandler, AutoCloseable`：
  - `DelayQueue` + 单守护线程 `notifyx-deferred`，到期经 `sink`（= `pipeline::handle`）重入管线；
  - 有界：容量 `Config.maxEntries`（默认 10000），溢出丢弃 + 计数 + warn；
  - 丢弃原因四态：`overflow / shutdown / no-sink / sink-error`，全部进指标；
  - `close()` 幂等；关闭时未到期事件计数丢弃（仍在静默时段，不能发出），重启丢失如实写进 Javadoc。
- `NotifyMetrics` 增加 4 个 default 方法（向后兼容）：
  `deferredScheduled / deferredDelivered / deferredDropped / deferredPending`。
- `NotifyPipeline` 零改动——DEFER 路径（`handle()` L156-159、`defer()` L475-483）原本就在。

**autoconfigure**

- `NotifyxProperties.QuietHours` 新增 `max-deferred`（默认 10000）。
- 新 Bean `notifyxDeferredHandler`：`@ConditionalOnProperty(quiet-hours.enabled=true)` +
  `@ConditionalOnMissingBean(DeferredHandler.class)`（用户可自定义持久化实现覆盖）+
  `destroyMethod="close"`。
- `notifyxNotifyKit`：注入 `ObjectProvider<DeferredHandler>` → `.deferredHandler(...)`，
  构建后对 `InMemoryDeferredHandler` 调 `sink(pipeline::handle)`（与聚合器同模式）。
- `NotifyxLifecycle` 新增 5 参构造：排空队列 → 聚合汇总 → **延后队列关闭（计数丢弃）** → 关渠道。
- `NotifyxDiagnostics` 免打扰行升级为三态提示：延后模式（展示实现类/容量/重启丢失）、
  `defer=false`（明确丢弃）、`defer=true` 但无 handler（⚠ 警告，正是本次修复前的静默陷阱）。
- `MicrometerNotifyMetrics` 实现 4 个新指标：`notifyx.deferred.scheduled/delivered/dropped`（counter）
  + `notifyx.deferred.pending`（gauge）。

**重入语义（刻意复用既有漏斗）**：窗口结束后事件完整重走过滤链——免打扰放行、
去重重新取槽（原槽位因未到过 400 环从未占用）、夜间堆积的同指纹事件被去重/聚合收拢，
清晨不会刷屏。升级计数（D-016）在入口记录，延后事件到期重入会再次计数，
但升级窗口（默认 10m）远小于延后时长（小时级），实际影响可忽略。

## 3. 验证

新增/扩展测试：

| 测试 | 用例 | 覆盖 |
| --- | --- | --- |
| `InMemoryDeferredHandlerTest`（新，6） | 到期投递 / 到期顺序 / 溢出丢弃 / 无 sink / sink 异常 / close 幂等 | 队列全生命周期 |
| `NotifyPipelineTest`（+4） | defer 交给 handler / 无 handler 退化 / defer=false 丢弃 / CRITICAL 直通 | 管线 DEFER/REJECT 两条路径 + 审计 errorMsg |
| `QuietHoursDeferWiringTest`（新，4） | 开启+defer → 进队列不发 / defer=false → 丢弃 / CRITICAL 直通 / 关闭不装配 Bean | Spring 装配端到端 |
| `MicrometerNotifyMetricsTest`（+1） | 4 个新指标名/标签/负值归零 | §15.1 |
| `NotifyxDiagnosticsTest`（+3） | 延后模式展示 / 无 handler 警告 / defer=false 提示 | 诊断三态 |
| `NotifyxLifecycleTest`（+1） | stop 关闭延后投递器 | §9.10 |

踩坑：`DelayQueue.drainTo/poll` 只移除**已到期**元素，`close()` 最初漏掉未到期任务，
测试暴露后改为整体搬空 + `clear()`。

模块级验证：`notifyx-core` 143/143、`notifyx-spring-boot3-autoconfigure` 63/63（含新增 14）。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，343 用例（330 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- `quiet-hours.enabled` 默认 false：未开启免打扰的用户零感知；
- 开启免打扰的用户：行为从「静默丢弃」变为「窗口结束后补发」，这是向设计书 §9.3 的回归，
  属于修复而非破坏；明确要求丢弃的可设 `defer=false`（语义与修复前一致且审计留痕）；
- `NotifyMetrics` 新方法均为 default，自定义实现不受影响；
- `NotifyxLifecycle` / `NotifyxDiagnostics` 旧构造保留。

## 5. 文档与门禁

- `docs/design.md`：§9.3 免打扰行改为全局粒度 + defer 语义段落（含三点能力边界）；
  §14 配置示例加 `max-deferred`；§15.1 指标表加 `notifyx.deferred.*` 4 行。
- `README.md`：架构图后补「免打扰延后」说明；配置节选补 `defer` / `max-deferred`。
- `notifyx-sample` application.yml 同步 `max-deferred`。

## 5.5 自查修复（同日第二轮）

落地后对 iter-59 代码做 bug 自查，发现并修复 3 处、确认 4 处可接受：

| # | 问题 | 修复 |
| --- | --- | --- |
| 1 | **清晨重放串行排空**：sink 直连 `pipeline::handle`，单守护线程同步走完整管线（渠道重试+退避），千级堆积的队头阻塞可达分钟~小时级，且绕过 `NotifyTaskExecutor` 的有界队列/优先级/水位观测 | autoconfigure 的 sink 改为 `taskExecutor.execute(level, eventId, ...)`（与 `DefaultNotifyKit.sendAsync` 同路径），满载走既有拒绝/丢弃策略 |
| 2 | **defer/close 竞态**：`closed` 检查与 `offer` 之间 close 完成 drain+clear，事件滞留队列且无任何计量；并发 defer 还可小幅穿透容量上限 | `defer` 与 `close` 临界区共用一把锁：{closed 检查+入队} 串行于 {清点+清空}，每条事件恰好计量一次 |
| 3 | **handler 异常丢审计**：自定义 `DeferredHandler.defer` 抛 RuntimeException 时 audit 被跳过，且 `handle()` 仍返回 true（谎报已受理） | `defer` 包 try/catch → 退化为 `reject("deferred-error")`；`defer()` 改为返回受理结果，降级路径 `handle()` 返回 false（同步调用方可感知） |

新增测试：

- `InMemoryDeferredHandlerTest.shouldAccountEveryEventUnderCloseRace`：8 线程 × 25 条与 close 并发，
  断言 `dropped 总数 == 事件总数` 且队列清空（竞态不变量）；
- `NotifyPipelineTest.customHandlerFailureFallsBackToAudit`：broken handler → `accepted=false` +
  审计 `deferred-error`；无 handler 退化用例的预期同步改为 `accepted=false`；
- `QuietHoursDeferWiringTest.shouldRedeliverDueEventThroughTaskExecutor`：端到端——手动 defer
  短延时事件 → sink → 优先级执行器 → 管线 → 渠道收到（验证修复 #1 的真实链路）。

确认不修（低危/信息级，记录备查）：升级计数双重计（within 默认 10m 无实际影响）、
时钟回拨次日重延循环（NTP 阶跃罕见）、deliver 内指标抛异常的账目失真、
pending gauge 关闭瞬间短暂失真、max-deferred 按条数不按字节、MQ consumer 侧未接线（诊断表会 ⚠）。

全量复验：`mvn -B -ntp clean verify` → BUILD SUCCESS，346 用例（333 执行 + 13 live 跳过），
Spotless/Checkstyle/JaCoCo 全绿。

## 5.8 demo 端到端实证（notifyx-demo，2026-11-02 00:29–00:38）

用 `C:\Users\admin\Downloads\notifyx-demo`（Spring Boot 3.5.16 + starter + log/webhook 渠道）
做真实进程验证：本地 `clean install` 覆盖 .m2 的 1.0.0 → demo `clean package`（必须 clean，
否则 repackage 会复用旧构件）→ 以命令行参数开启动态免打扰窗口（`now-2min ~ now+2min`）。
验证脚本 `run-quiet-hours-verify.ps1`，证据 `evidence-quiet-hours/`：

| # | 场景 | 结果 |
| --- | --- | --- |
| QH-01 | 启动装配：banner + `deferredHandler=InMemoryDeferredHandler` | PASS |
| QH-02 | 窗口内下单+resend：HTTP 201、**0 条 webhook**、审计 `SUPPRESSED`、`deferred.scheduled=2→3` | PASS |
| QH-03 | 窗口结束：**恰好 1 条** ORDER_CREATED webhook（重投时刻=窗口 to），resend 被 `DEDUPED` 收拢，`delivered=3`、`pending=0` | PASS |
| QH-04 | 优雅关闭：端口关闭、lifecycle finished、零延后丢弃 | PASS |

**附带发现并修复一个既有 bug**：`/actuator/metrics` 下任何 `notifyx.*` 指标都不存在——
`NotifyxMetricsAutoConfiguration` 声明了 `before = NotifyxAutoConfiguration` 但未声明 after
Boot `MetricsAutoConfiguration`/`SimpleMetricsExportAutoConfiguration`（真实环境 MeterRegistry
的来源），`@ConditionalOnBean(MeterRegistry)` 先于 registry 评估而恒不成立，整套指标自 1.0.0
起在真实应用中静默缺失；iter-41 的测试用 `withBean(MeterRegistry)`（用户 bean 先于 auto-config
评估，条件恒真）掩盖了该问题。修复：`@AutoConfigureAfter(name=...)` 字符串引用（不引 actuator
编译依赖）+ 同 FQCN test fixture 回归测试（故意按「NotifyX 在前」声明顺序断言仍能装配）。
修复后 demo 实测 `notifyx.deferred.scheduled/delivered/pending` 全部可见。

工程注意：demo 的业务规则要求先 `POST /api/users/register` 再下单（否则 422 USER_NOT_FOUND）；
demo 全局异常处理器会把 actuator 404 包装成 500（排障时看 `exception` 字段而非 HTTP 码）。

全量复验（含该修复）：`mvn -B -ntp clean install` → BUILD SUCCESS，17 模块全绿，
347 用例（334 执行 + 13 live 跳过），Spotless/Checkstyle/JaCoCo 达标。

## 6. 未纳入

- 延后队列持久化（重启不丢）：属 T1+（Redis/JDBC）能力，与 ISSUE-039、A02/A03 同批待决策；
- 按场景的免打扰窗口（§9.3 表中「全局/scene」的 scene 粒度）：需求未出现，避免过度设计；
- `notifyx.send.total{status=suppressed}` 与 `notifyx.filter.rejected` 的指标口径统一：
  涉及既有指标语义，留待单独迭代。

## 7. 回滚

单提交回滚；`quiet-hours.enabled=false` 时行为与回滚后完全一致。
