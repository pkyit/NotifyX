# iter-37 · 升级策略窗口滚动修复

- **日期**：2026-10-23
- **分支**：`bugfix/iter-37-escalation-window` → `master`
- **类型**：fix（升级策略窗口算法 + 回归测试）
- **触发**：19:00 夜间自动化重新启动；处理架构审计 P2 A13。

## 1. 问题

`EscalationPolicy.record()` 只在「距离上次事件超过 within」时重置窗口：

```java
if (state.lastAt > 0 && now - state.lastAt > within) {
    state.reset(now);
}
...
if (!state.escalated && state.count >= rule.consecutive()
        && now - state.firstAt <= within) {
    state.escalated = true;
}
```

一旦事件间隔小于窗口但持续很久，`firstAt` 会一直停在第一次事件：

- t0 / t9 / t18，窗口 10m、阈值 3：t18 时 `now - firstAt = 18m > 10m`，不升级；
- 之后即使 t19 / t20 密集发生，`firstAt` 仍是 t0，`now - firstAt` 永远大于窗口，
  **升级状态永远不可达**。

## 2. 修复

窗口是否过期改为看 `firstAt`，而不是 `lastAt`：

```java
if (state.firstAt == 0 || now - state.firstAt > within) {
    state.reset(now);
}
state.count++;
if (!state.escalated && state.count >= rule.consecutive()) {
    state.escalated = true;
}
```

- 每次评估时，如果当前事件已落在旧窗口之外，就把窗口滚动到当前事件（count=1）；
- 只要当前窗口内连续次数达到阈值就升级；
- 删除不再使用的 `lastAt` 字段，减少状态。
- 恢复场景 / `reset-on-success` 的清零逻辑不变。

## 3. 验证

新增 `EscalationPolicyTest.shouldRollWindowAfterLongGap`：

- t0 / t9 / t18 均不升级（每 9 分钟一次，10 分钟内凑不满 3 次）；
- t19、t20 连续两次后达到 3 次，`escalated=true`。
- 修复前 t20 仍为 false。

`EscalationPolicyTest` 7/7 通过。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，277 用例（264 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 阈值/窗口语义不变，只修正“旧窗口未滚动”的 bug；
- 原本已经能升级的短窗口连续事件行为不变；
- 无 public API 变更。

## 5. 未纳入

- A02/A03（失败记录粒度、dedupe release 部分成功重复发送）；
- A04（无渠道返回成功）；
- A06（队列丢弃不落盘）；
- A07（审计/落盘全链路脱敏）；
- A12（配置绑定但不生效）、A14（去重 fail-closed）、A15（观测性缺口）。

## 6. 回滚

单提交回滚；无 schema/配置变化。
