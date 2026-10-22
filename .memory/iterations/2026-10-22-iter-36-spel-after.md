# iter-36 · @NotifyOn / @AlarmProtect 的 SpEL 后置求值修复

- **日期**：2026-10-22
- **分支**：`bugfix/iter-36-spel-after` → `master`
- **类型**：fix（AOP 表达式语义 + 回归测试）
- **触发**：夜间自动化第五轮；处理架构审计 P1「`@NotifyOn` 的 bizId/context 用 `evaluateBefore`，
  拿不到 `#r/#e`”。

## 1. 问题

`NotifyxAspect.buildEvent()` 对所有 `bizId` / `context` 表达式都调用
`spelEvaluator.evaluateBefore(...)`。但这两个注解的求值时机在方法执行之后：

- `@NotifyOn` 已经拿到返回值 / 异常；
- `@AlarmProtect` 在 catch 块里已经拿到异常。

`evaluateBefore` 的变量表没有 `#r` / `#e`，所以：

```java
@NotifyOn(scene = "X", bizId = "#r.id", context = "#r.id")
```

会被 SpEL 降级为 null：`bizId` 为空、`ctx0` 缺失，而且只打一条 WARN，不报错。
`@AlarmProtect` 的 `bizId = "#e.message"` 同样失效。

## 2. 修复

- `buildEvent()` 增加 `Object result` 参数；
- `aroundNotify` 传入 `result`，`aroundAlarm` 传 `null`（只有异常）；
- `context` 与 `bizId` 统一改用 `spelEvaluator.evaluateAfter(expression, joinPoint, result, error)`，
  使 `#r` / `#e` 可用；`#p0` / `#a0` / 具名参数仍然可用。

## 3. 验证

新增 2 个用例：

- `@NotifyOn(bizId="#r.id", context="#r.id")` → `bizId=R-1`、`ctx0=R-1`；
- `@AlarmProtect(bizId="#e.message")` → `bizId=E-1`。

`NotifyxAspectTest` 8/8 通过（原 6 + 新增 2）。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，276 用例（263 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 原有 `#p0` / `#a0` / 具名参数表达式不受影响；
- 之前静默失效的 `#r` / `#e` 表达式现在生效，属于修复；
- SpEL 默认仍禁止方法调用，安全默认不变；
- 无 public API 变更。

## 5. 未纳入

- A02/A03（失败记录粒度、dedupe release 部分成功重复发送）；
- A04（无渠道返回成功）；
- A06（队列丢弃不落盘）；
- A07（审计/落盘全链路脱敏）；
- A12（绑定但不生效的配置项）；
- A13/A14/A15（升级窗口、去重 fail-closed、观测性缺口）。

## 6. 回滚

单提交回滚；无 schema/配置变化。
