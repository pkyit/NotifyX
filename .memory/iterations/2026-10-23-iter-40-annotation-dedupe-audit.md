# iter-40 · AOP 数组参数去重与审计 attempts 修复

- **日期**：2026-10-23
- **分支**：`bugfix/iter-40-annotation-dedupe-audit`（工作区改动，未提交）
- **类型**：fix（AOP 参数归一化 + 审计/落盘 attempts + 全局重试上限）
- **触发**：外部独立 demo（用户注册 + 下单，Maven 坐标引用 SDK）冒烟测试
  `TEST-REPORT.md`：10 个用例 9 通过，发现 F-01/F-02/F-03。

## 1. 问题

### F-01 `@AlarmProtect` 的 `args` 数组破坏去重

同一订单、同一异常、同一参数在 30s 静默窗口内连续触发两次，发出两条
`ORDER_CREATE_FAIL`。两条 webhook 的 `params.args` 是
`[Ljava.lang.Object;@6e05e3e8` / `[Ljava.lang.Object;@4c9c43b3`。

根因链：

1. `NotifyxAspect.buildEvent()`：`params.put("args", joinPoint.getArgs())`；
2. `NotifyEvent.normalizeValue()`：数组命中兜底 `String.valueOf(value)`，
   得到 identity hash；
3. `Fingerprints` 把该值算进 `md5(sorted(params))`，dedupeKey 每次不同。

L4 路径（参数是 Map/String）不受影响，所以问题只在注解接入暴露。

### F-02 全局 `retry.max-attempts` 不生效

demo 配置 `max-attempts: 2`、`base-delay: 200ms`，webhook 失败仍尝试 3 次、
耗时 3280ms。`RetryPolicy.attemptsFor()` 在渠道 `maxRetries() > 0` 时直接返回渠道值，
而内置渠道默认 3，全局配置永远用不上。

### F-03 审计/落盘 attempts 不是真实值

实际 3 次尝试，审计为 `attempts=1`（`NotifyPipeline.audit` 硬编码）；
`persistFailure()` 又把 `maxAttempts` 写进 envelope/失败记录，非重试失败
（认证错误 1 次即放弃）会被记成配置上限。

## 2. 修复

### F-01

- `NotifyEvent.normalizeValue()` 识别 `value.getClass().isArray()`，
  用 `java.lang.reflect.Array` 逐元素递归归一化为不可变 List；
- `Fingerprints.canonicalValue()` 对数组使用与 Collection 相同的 `[a,b]` 格式，
  防止绕过 `NotifyEvent` 直接调用指纹工具时再次退化；
- 数组与等值 List 现在产生同一指纹，payload 中 `args` 变为可读的
  `CreateOrderCommand[...]`。

### F-02

- `RetryPolicy.attemptsFor()`：
  `channel.maxRetries() <= 0 ? maxAttempts : min(maxAttempts, channel.maxRetries())`；
- 场景 `retry > 0` 继续在管线里取更小值；
- `docs/design.md` §9.8、`README.md`、`NotifyxProperties.Retry` 补充语义：
  全局值是上限，渠道只能降低；`base-delay` 仍由渠道 `retryBackoff()` 决定。

### F-03

- `sendOne()` 维护 `attemptsUsed`（仅在实际调用渠道时递增）；
- `audit()` 增加 `attempts` 参数，审计与 `persistFailure()` 都用真实值；
- 熔断打开、模板预发送失败、Filter 拒绝没有调用渠道，记 0；
- 删除 `audit()` 里 `result == null ? 0 : 1` 的硬编码。

## 3. 验证

新增/加强 7 个测试：

| 模块 | 测试 |
| --- | --- |
| core | `NotifyEventTest.shouldNormalizeArrayParams` |
| core | `FingerprintsTest.shouldCanonicalizeArrays` |
| core | `NotifyPipelineTest.arrayParamsDoNotBreakDedupe` |
| core | `NotifyPipelineRetryTest.shouldAuditActualAttemptsAfterRetriesExhausted` |
| core | `FailurePersistenceTest.shouldPersistActualAttempts` |
| core | `RetryPolicyTest.shouldCapAttemptsByGlobalMaximum` |
| spring | `NotifyAspectTest.shouldProduceStableFingerprintForRepeatedArgs` |

全量门禁：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块
→ 274 测试执行 + 13 live 跳过（共 287 用例），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

外部 demo 复测（重新 `mvn install` 后）：

```text
TOTAL=10  PASS=10  FAIL=0
TC-05 重复失败：webhooks 3 -> 3，DEDUPED
TC-08 渠道 503：attempts=2 costMs=1841，事件落盘
```

证据：`C:\Users\admin\Downloads\notifyx-demo\evidence-fixed\` 与
`TEST-REPORT.md` §10。

## 4. 兼容性

- 无 public 方法签名变化；`NotifyEvent.params()` 的数组值从 String 变为 List，
  这是缺陷修复，模板会看到可读的列表字符串。
- `AuditRecord.attempts` / `NotifyEnvelope.attempts` 语义修正为真实渠道尝试次数；
  依赖旧值恒 1 的看板需调整。
- 全局 `max-attempts` 现在会封顶内置渠道；默认 3 与原有行为一致。
  需要更多重试时把全局值调大。
- `base-delay` 行为不变（渠道 `retryBackoff()` 优先，§9.8）。

## 5. 未纳入

- F-01 的更深层问题：`@AlarmProtect` 的业务入参若是带 identity `toString()` 的 POJO，
  仍可能不稳定；本轮只修数组归一化，未强制参数实现稳定 toString。
- F-02 的 `base-delay`：按设计书保持渠道优先，没有改成全局覆盖。
- 外部 demo 未覆盖邮件/Redis/MQ/JDBC/L0 场景。

## 6. 回滚

单分支内三处独立改动，可按文件回滚：

- `NotifyEvent` + `Fingerprints`（F-01）；
- `RetryPolicy` + 文档（F-02）；
- `NotifyPipeline`（F-03）。

无 schema/配置破坏性变更；回滚后 demo 的 TC-05 会重新失败，可据此识别。
