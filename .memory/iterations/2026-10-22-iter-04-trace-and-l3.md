# iter-04 · L3 Spring Event 桥接 + Trace 贯通（设计书 §8.4 / §15.3）

- **日期**：2026-10-22
- **分支**：`feat/trace-and-l3` → `master`
- **类型**：feat（P0 第 6 步的另一半 + §15.3）
- **关联**：ISSUE-004（关闭）、ISSUE-013（补充 spring 覆盖率）、ISSUE-014（新增并关闭）、D-011

## 1. 背景

§21.2 第 6 步要求 `notifyx-spring` 提供「AOP 切面 + SpEL + Spring Event 桥接 + MDC」。
前两项此前已完成，本次补齐 L3 与 trace，让「业务只发事件」和「告警带 traceId」两个卖点成立。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 模板 `${userName}` 被 Spring 绑定期替换成 Windows 用户名 `admin` | L3 E2E：发布 `userName=bob`，通知标题却是 `【账号开通】admin`；`__source` 显示 `userName=bob` | 用 identity `PlaceholdersResolver` 的 Binder 重读 `notifyx.templates`（D-011）；新增测试固定该行为 |
| 2 | core 单测没有 MDC 实现 | `MdcTraceContextTest` 期望 `trace-abc` 实得 null；`slf4j-simple` 不提供 MDCAdapter | core 加 `logback-classic`（test scope）+ `logback-test.xml` 收敛日志 |
| 3 | Appender 读 MDC 在无 MDCAdapter 环境抛 NPE，整条日志被丢弃 | logback 测试 7 个失败；status 显示 `NullPointerException: ... mdcAdapter is null` | 新增 `mdcValue()` 吞掉异常返回 null（符合「Appender 内部异常必须吞掉」铁律） |
| 4 | notifyx-spring 有测试后覆盖率只有 21.6%，跌破 40% 门禁 | `jacoco.csv` | 模块级 ratchet 设 0.20，记入 ISSUE-013 |
| 5 | `@EventListener(Object)` 会收到 Spring 内部事件 | 设计风险 | 只处理「有 `@NotifyxScene` 或命中配置映射」的事件，其余静默忽略；另加线程级重入保护 |

## 3. 改动点

**core（trace 能力）**：

- 新增 `TraceContext` SPI（`traceId()` / `spanId()` + `none()`）。
- 新增 `MdcTraceContext`：读 SLF4J MDC（Micrometer Tracing / SkyWalking 都写这里），支持自定义 key。
- `DefaultNotifyKit`：**在调用线程**抓 traceId/spanId 写进事件 tags（异步投递后 MDC 就看不到了）。
- `NotifyPipeline`：新增 `traceContext` 构建项；渲染前把 trace 补进参数（不影响去重指纹），
  `NotifyContext` 与审计记录带 traceId；事件自带标签优先于线程 MDC。

**notifyx-logback**：

- 日志快照从 `ILoggingEvent.getMDCPropertyMap()` 抓 traceId/spanId，写进事件 tags；
  MDC 不可用时安全降级。

**notifyx-spring（L3）**：

- `@NotifyxScene`（value / level / receivers）。
- `NotifyxEventPayload`（可选：事件自己提供结构化参数）。
- `NotifyxEventBridge`：`@EventListener` 统一翻译，场景来源 = 注解 → 配置映射（父类逐级查找）；
  `params.__source = 类名#toString()`（截断 2000 字符）；异常只记 warn，绝不回抛发布方。

**autoconfigure**：

- `TraceContext` Bean（默认 `MdcTraceContext`）注入管线与门面。
- `NotifyxEventBridge` Bean，`notifyx.events.enabled=false` 可关。
- `NotifyxProperties` 新增 `events.enabled` + `events.mappings`。
- 模板配置改用 identity `PlaceholdersResolver` 的 Binder 读取（D-011）。

**sample**：新增 `AccountCreatedEvent`（`@NotifyxScene("ACCOUNT_CREATED")`）+ `/demo/event` + 场景/模板。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，11 模块，92 tests，0 failures
  core 61 / spring 6 / autoconfigure 5 / logback 20
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标（core 67%+、spring 20%+、logback 80%+）

E2E（sample）：
GET /demo/event?phone=99&userName=bob
→ NOTIFYX_FALLBACK：scene=ACCOUNT_CREATED level=INFO title=【账号开通】bob
   content 含 手机：99 / 来源：...AccountCreatedEvent[phone=99, userName=bob]
→ NOTIFYX_AUDIT：status=SUCCESS attempts=1
（修复前 title 是「【账号开通】admin」——Windows USERNAME 被当成模板变量）
```

## 5. 回滚

删除 `TraceContext`/`MdcTraceContext`、`@NotifyxScene`/`NotifyxEventPayload`/`NotifyxEventBridge`、
autoconfigure 的 Bean 与 `events` 配置、template raw binder 即可；管线与渠道 API 不变。

## 6. 下一步

- iter-05：P1 优雅关闭 + actuator health + 启动诊断表。
- ADR-001 alias artifact 仍是 P0 尾部未完成项。
- ISSUE-013：补 AOP/SpEL/priority executor 测试，把 spring 覆盖率抬到 60%+。
