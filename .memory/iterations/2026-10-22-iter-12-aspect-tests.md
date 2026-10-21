# iter-12 · L2 切面测试与 SpEL 安全默认验证（§8.3 四条铁律）

- **日期**：2026-10-22
- **分支**：`test/aspect-spel` → `master`
- **类型**：test + fix（ISSUE-013 覆盖率抬升）
- **关联**：ISSUE-013（部分关闭）

## 1. 背景

`@AlarmProtect` / `@NotifyOn` 是 L2 主力用法，但此前没有任何自动化测试：
「异常必须原样抛出」「成功不得误报」「SpEL 默认禁方法调用」这些铁律只写在注释里。
同时发现 `@NotifyOn.context` 属性在实现里从未被读取——文档承诺了但代码没做。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | L2 切面零测试 | `notifyx-spring/src/test` 只有 event 桥接测试 | 新建 `NotifyxAspectTest`（Spring AOP 真实代理，非 mock 切点） |
| 2 | **`@NotifyOn.context` 属性被忽略** | `NotifyxAspect.buildEvent` 没有 context 参数；注解却声明「求值结果摊平进 params」 | 生产代码补上：逐个求值放入 `params.ctx0/ctx1...`，并加测试 |
| 3 | 默认 SpEL 上下文的安全性无人验证 | `SimpleEvaluationContext` 禁方法调用，但无测试 | 默认上下文调用 `#r.toString()` → 不告警且不影响业务；显式 `allow-method-invocation=true` 时才生效 |
| 4 | spring 模块覆盖率只有 21.6%，拉低整体 | `jacoco.csv` | 切面测试后 61.8%，门禁从 20% 抬到 60% |

## 3. 改动点

**生产代码（小修）**：

- `NotifyxAspect.buildEvent` 增加 `contextExpressions` 参数；`aroundNotify` 传入
  `notifyOn.context()`，逐个 `evaluateBefore` 后写入 `params.ctx0...`（null 跳过）。
  `@AlarmProtect` 传空数组。

**测试 `NotifyxAspectTest`（6 个用例，Spring `@EnableAspectJAutoProxy` 真实代理）**：

| 用例 | 覆盖 |
| --- | --- |
| 异常告警 | 原异常类型/消息不变；事件 scene/level/bizId（SpEL `#orderNo`）/silent 30s/receivers/exception 参数 |
| 成功不误报 | `@AlarmProtect` 正常返回不发事件 |
| 条件为真 | `when = "#r != null and #r == 'bad1'"`；`context = "#orderNo"` → `ctx0`；返回值透传 |
| 条件为假 | 同方法返回 good，不发事件 |
| 方法抛异常 | `@NotifyOn` 发 WARN 事件并原样抛出 |
| SpEL 安全 | 默认禁方法调用：`#r.toString()` 不告警；`SpelEvaluator(true)` 下才告警 |

**POM**：spring 模块 `jacoco.minimum: 0.20 → 0.60`。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，12 模块，151 tests，0 failures
  core 80 / spring 12 / autoconfigure 21 / email 6 / logback 20 / webhook 12
→ JaCoCo：spring 61.8%（门禁 60%）；其它模块门禁全部通过
→ Spotless 0 / Checkstyle 0
```

## 5. 回滚

删除 `NotifyxAspectTest`、还原 spring 的 jacoco 门禁即可；`context` 属性的修复建议保留
（它修的是「注解声明了但没实现」的不一致）。

## 6. 下一步

- `PriorityNotifyExecutor` 仍无测试（drop-lowest/关闭排空），补后 spring 覆盖率可到 75%+。
- `NotifyEnvelope` + 自动补偿（ISSUE-015）仍是 P1 可靠性最后一块。
- core 覆盖率约 72%，目标 90%（ISSUE-013）。
