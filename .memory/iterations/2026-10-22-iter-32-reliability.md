# iter-32 · 管线与存储可靠性缺陷修复

- **日期**：2026-10-22
- **分支**：`bugfix/iter-32-reliability` → `master`
- **类型**：fix（缺陷修复 + 回归测试）
- **触发**：夜间自动化第一轮；只修已交付范围的缺陷，不扩 P2+ 范围。

## 1. 范围与原则

本轮从 `docs/architecture-review-2026-10-22.md` 与代码走查中挑出 5 个**有证据、可回归、低争议**的问题；
A02/A03（失败记录粒度、dedupe release 部分成功重复发送）、A04（无渠道当成功）涉及契约变更，先记录不实施，
待用户确认后再做。

## 2. 问题与修复

| # | 问题 | 证据 | 修复 |
| --- | --- | --- | --- |
| 1 | 渠道抛非 `ChannelException` 或返回 `null` 时绕过审计、熔断与失败落盘；半开探针可能永久卡住 | `docs/architecture-review-2026-10-22.md` A05；`NotifyPipelineFailureContainmentTest`；修复前持续抛 `IllegalStateException` 会被当成成功 | `NotifyPipeline.sendOne()` 捕获 `RuntimeException` 并按可重试失败处理；`null` 结果转失败；中断时退出重试；新增 `channel produced no result` 兜底 |
| 2 | README 承诺“自定义 `EventFilter` Bean 即被发现”，实际未接入 | `README.md` §扩展：插一个 Filter；`CustomFilterWiringTest`；修复前 `filters=4`，自定义回调为空 | `notifyxNotifyKit()` 注入 `ObjectProvider<EventFilter>`，合并后按 `EventFilter.order()` 升序排序 |
| 3 | `JdbcAuditRetention` 删除失败（权限/锁/只读）时，select 仍返回整批 id，循环永不退出 | `JdbcAuditRetentionTest.shouldStopWhenDeleteFails`（H2 触发器拒绝删除）；修复前 2s 超时内持续 select 同一批 | 单批删除 0 行时 warn 并结束本轮，返回已删除总数 |
| 4 | `DefaultMasker.maskParams()` 只脱敏顶层字符串，嵌套 Map/List 原样返回，与“递归匹配”契约不符 | `DefaultMaskerTest.shouldMaskNestedParams`；修复前嵌套 `phone/email` 不脱敏 | 递归 Map/Iterable，深度上限 10，返回新结构且不修改入参 |
| 5 | `MemoryRateLimiter.currentCount()` 不按窗口清理，窗口滑过后仍返回陈旧计数 | `MemoryRateLimiterTest.currentCountShouldPruneExpired`；修复前窗口过期后仍为 1 | `computeIfPresent` 内清理过期时间戳，空 deque 移除 key |

## 3. 环境问题（非代码缺陷）

在 DSH 的 `pwsh` 沙箱中直接执行 `mvn -B -ntp clean verify`，`notifyx-store-redis` 的 Mockito 用例失败：

```text
Could not initialize inline Byte Buddy mock maker.
It appears as if your JDK does not supply a working agent attachment mechanism.
```

根因是 Byte Buddy 默认起外部 JVM 做 self-attach，沙箱禁止管道/进程附加；设置
`JDK_JAVA_OPTIONS=-Djdk.attach.allowAttachSelf=true` 后通过。已记录到 `.memory/env.md` §12。

## 4. 验证

```text
$env:JDK_JAVA_OPTIONS='-Djdk.attach.allowAttachSelf=true'
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块
→ 264 个测试用例（251 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 violation / Checkstyle 0 violation / JaCoCo 全模块达标
```

定向验证：

- `notifyx-core`：109 tests（新增 5 个：3 个管线故障隔离 + 1 个嵌套脱敏 + 1 个限流计数）
- `notifyx-store-jdbc`：`JdbcAuditRetentionTest` 3/3（新增删除失败用例）
- `notifyx-spring-boot3-autoconfigure`：`CustomFilterWiringTest` 1/1

## 5. 行为变化与兼容性

- 渠道非受控异常不再“静默成功”，会进入重试、审计、熔断与失败落盘；这是修复，不是兼容性破坏。
- 自定义 `EventFilter` Bean 现在生效；原有内置 Filter 顺序不变（100–600）。
- 保留期删除失败提前结束，避免一次调用无限占用连接/线程。
- 嵌套脱敏返回新 Map/List，入参不变。
- `currentCount` 只统计窗口内计数，指标语义更准确。
- 无 public 方法签名删除；`notifyxNotifyKit()` 是自动配置 Bean 方法，新增参数不影响业务方。

## 6. 未纳入本轮 / 待确认

- **A02/A03**：`FailedRecord.recordId` 仍是 eventId，多渠道路由失败会互相覆盖；`dedupe.release` 在部分成功时
  会释放槽位，导致成功渠道重复发送。需要“目标级失败记录 + 定向重投”，涉及 `FailedRecord` 结构与 DDL 变更。
- **A04**：无可用渠道仍返回成功，现有测试 `NotifyPipelineRetryTest.shouldSkipWhenNoChannel` 明确固化了该行为；
  改成 `NO_ROUTE` 需要先改契约与补偿器。
- **A05 残留**：模板渲染异常仍发生在 `sendOne()` 的 try 之外，未进入统一失败路径；下一轮处理。
- **A07**：审计只脱敏 receiver，`mask.before-send` 未生效，envelope 落盘未脱敏；范围较大。
- **A09**：Webhook 嵌套 JSON 被 `String.valueOf` 拍平、用户 headers 可覆盖保留头、URI/HMAC 异常未归类。
- 信创 DDL、DLX/死信、consumer 多实例、Admin 均未动。

## 7. 回滚

单提交回滚即可：无数据库 schema 变更，无配置项新增/删除；回滚后行为回到 iter-31。
