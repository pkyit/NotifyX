# iter-09 · 升级策略（设计书 §9.6）

- **日期**：2026-10-22
- **分支**：`feat/escalation` → `master`
- **类型**：feat（P1 可靠性）
- **关联**：ISSUE-007（关闭 T0 部分）、D-015（新增）

## 1. 背景

§9.6：同一场景在窗口内连续 N 次未恢复，后续通知升级到更高触达渠道（短信/电话）。
需要「未恢复」判定、恢复信号、以及不破坏现有漏斗语义的接入点。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 管线没有「按场景计数并改道」的入口 | `handle()` 只做 scene 解析 → 过滤 → dispatch | 在场景解析后调用 `applyEscalation()`，命中阈值时用 `SceneConfig.withRouting` 替换渠道 |
| 2 | 升级是否要顺带把 Level 提到 CRITICAL？ | CRITICAL 会旁路免打扰/限流（§9.2 旁路矩阵），影响面大 | **只升级渠道，不改 Level**，保持漏斗语义不变；需要级别升级时由场景配置决定 |
| 3 | 「成功发送则清零」需要 dispatch 结果 | `dispatch()` 原本返回 void | 改为返回 `failed`；成功时回调 `escalation.recordSuccess(scene)` |
| 4 | 恢复场景自身会不会被吞掉？ | 恢复场景也是普通事件 | 恢复事件正常走管线投递；只额外执行 `recover()` 清零目标场景 |
| 5 | 多实例计数会偏慢 | T0 内存计数 | README/诊断表明确「多实例各自计数」；T1 Redis 计数随 P2 落地 |
| 6 | 首版测试窗口设 1ms 导致阈值判断失效 | 第二次 record 时 `now-lastAt > within` 直接重置 | 测试改为 10ms 窗口、间隔 5ms；并单独验证「间隔大于窗口 → 重新累计」 |

## 3. 改动点

**core 新增 `EscalationPolicy`**：

- `Rule(scene, consecutive, within, upgradeTo, resetOnSuccess)` 与 `resetScenes`（恢复场景 → 目标场景列表）。
- `record(scene, now)`：窗口过期自动重置；达到阈值后进入升级态并持续；返回 `Outcome(escalated, channels)`。
- `recover(recoveryScene)` / `reset(scene)` / `recordSuccess(scene)`（仅 `resetOnSuccess=true` 清零）。
- 每个场景一个状态对象，对象锁保护；规则数很小，无额外线程。

**core 管线**：

- `applyEscalation`：恢复场景 → 清零；普通场景 → 记录并（必要时）用 `withRouting` 替换渠道。
- `dispatch` 返回 `failed`；成功时回调 `recordSuccess`。
- builder 增加 `escalation(...)`。

**autoconfigure**：

- `NotifyxProperties.Escalation`（enabled 默认 false、rules、reset-scenes 映射）。
- `notifyxEscalationPolicy` Bean；注入管线。
- 启动诊断表增加「升级规则数 / 未启用」与多实例提示；装配日志打印 `escalationRules`。

**测试（8 个新增）**：

| 测试 | 覆盖 |
| --- | --- |
| `EscalationPolicyTest`（6） | 阈值升级并持续、窗口过期重置、恢复场景清零、reset-on-success 开关、未配置场景、规则规范化 |
| `EscalationPipelineTest`（2） | 连续 2 次后路由到 upgrade 渠道；恢复场景后回落到 primary（恢复事件本身正常投递） |

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，12 模块，131 tests，0 failures
  core 74 / spring 6 / autoconfigure 19 / logback 20 / webhook 12
→ Spotless 0 / Checkstyle 0 / JaCoCo 达标
```

## 5. 回滚

删除 `EscalationPolicy`、管线 `applyEscalation`/`escalation` 字段、autoconfigure Bean 与
`escalation.*` 配置即可；默认关闭，不配置时行为与之前完全一致。

## 6. 下一步

- 失败落盘补偿（D-003，local-file 优先）：关闭时未排空事件落盘。
- email 渠道契约测试（ISSUE-010）。
- 覆盖率债：core 约 70% → 90%；spring 21.6% → 60%+。
- 跨实例聚合/计数（T1 Redis / T2 MQ）属 P2，随 store/MQ 模块一起做。
