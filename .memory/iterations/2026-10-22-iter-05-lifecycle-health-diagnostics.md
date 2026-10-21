# iter-05 · P1 可观测性与生命周期（§9.10 / §14.4 / §15.2）

- **日期**：2026-10-22
- **分支**：`feat/lifecycle-health-diagnostics` → `master`
- **类型**：feat（P1 第一批）
- **关联**：ISSUE-005（关闭）、ISSUE-006（关闭）、D-012（新增）

## 1. 背景

P0 收尾后按设计书进入 P1：§9.10 优雅关闭、§15.2 Health Indicator、§14.4 启动诊断表。
这三项共同回答运维最关心的三个问题：现在能力是什么、出问题在哪、重启会不会丢通知。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 关闭时只靠 `PriorityNotifyExecutor.destroy`（Bean 销毁阶段），数据源可能已经先关 | 设计书 §9.10 | 新增 `NotifyxLifecycle`（SmartLifecycle），在容器关闭早期排空队列并关闭渠道 |
| 2 | 关闭阶段与 Web 容器的先后关系设计书表述含糊 | §9.10 写「比 Web 容器先停」，但先停 Web 才能避免新请求涌入 | phase 取 `DEFAULT_PHASE - 2048`，晚于 Web 优雅停机、早于 Bean 销毁；关闭期间新事件被拒绝并 warn（D-012） |
| 3 | 没有健康端点，运维无法区分「业务挂了」与「通知降级」 | 无 `HealthIndicator` | 新增 `NotifyxActuatorAutoConfiguration` + `NotifyxHealthIndicator`，DEGRADED 不映射为 503 |
| 4 | 健康检查若默认探活会制造垃圾消息与费用 | §15.2 | `probe-enabled=false` 默认关闭；开启后才调用 `channel.health()` |
| 5 | 启动后不知道「去重是不是降级成内存」 | §14.4 | `NotifyxDiagnostics` 在 `ApplicationReadyEvent` 打印能力表，只打类名/数量，不打配置值 |
| 6 | autoconfigure 直接引 actuator 会破坏「零依赖可启动」 | 承诺 P1 | `spring-boot-actuator` 设为 optional + `@ConditionalOnClass(name=...)` 字符串条件 |

## 3. 改动点

**autoconfigure（3 个新类 + 2 处接线）**：

- `NotifyxLifecycle implements SmartLifecycle`：`stop()` 先 `executor.destroy()`（内部 shutdown →
  awaitTermination → shutdownNow），再对每个渠道 `close()`；幂等、异常只记 warn。
- `NotifyxHealthIndicator implements HealthIndicator`：
  - `notifyx.enabled=false` → OUT_OF_SERVICE；全局静默 → DOWN；无渠道 → DOWN；
  - 内存去重/限流 → DEGRADED（详情带 `degradedReason`，不影响 K8s readiness）；
  - `probe-enabled=true` 时才探活；全部渠道 DOWN → DOWN，部分 DOWN → DEGRADED。
- `NotifyxDiagnostics implements ApplicationListener<ApplicationReadyEvent>`：
  打印版本/mode/tier/渠道/场景模板/SPI 实现/降级提示/actuator 提示；`banner=false` 可关；
  渲染逻辑独立成 `render()` 便于单测；任何异常只 warn，不影响启动。
- `NotifyxActuatorAutoConfiguration`：actuator 不在 classpath 或
  `notifyx.actuator.health.enabled=false` 时完全不装配。
- `NotifyxProperties` 新增 `actuator.health.enabled/probe-enabled`；
  `AutoConfiguration.imports` 增加 actuator 配置（并调整顺序：core → metrics → actuator）。
- autoconfigure POM 增加 `spring-boot-actuator`（optional），顺手修复 `<dependency>` 粘连的格式问题。

**sample**：引入 starter-actuator、`management.endpoint.health.show-details=always`、
`server.shutdown=graceful`，用于 E2E 验证。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，11 模块，106 tests，0 failures
  core 61 / spring 6 / autoconfigure 19 / logback 20
→ Spotless 0 / Checkstyle 0 / JaCoCo 达标

E2E（sample + actuator）：
GET /actuator/health
→ "notifyx":{"status":"DEGRADED","details":{"mode":"local","channels":["log"],
   "dedupe":"MemoryDedupeStore","rateLimiter":"MemoryRateLimiter",
   "probeEnabled":false,"degradedReason":"in-memory dedupe/rate limiter: multi-instance may duplicate"}}
启动日志出现能力表：NotifyX dev · mode=local · tier=T0（单机内存），
渠道 1 启用/1 注册，去重/限流/落盘均为内存实现并带 ⚠ 提示。
```

## 5. 回滚

删除 `NotifyxLifecycle` / `NotifyxHealthIndicator` / `NotifyxDiagnostics` /
`NotifyxActuatorAutoConfiguration` 及 autoconfigure 中的 Bean 与 `actuator` 配置、
imports 文件对应行即可；不影响发送链路。

## 6. 下一步

- P0 收尾：ADR-001 alias artifact。
- P1 继续：聚合（§9.5）、升级（§9.6）、失败落盘补偿（文件/JDBC）。
- ISSUE-013：spring 模块覆盖率（AOP/SpEL/执行器）仍需补测。
- 关闭时的「未排空事件落盘」要等失败落盘文件实现（D-003），当前只 warn + 指标。
