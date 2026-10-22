# iter-20 · 审计级别与生效级别不一致（真实邮件验证暴露）

- **日期**：2026-10-22
- **分支**：`bugfix/audit-effective-level` → `master`
- **类型**：fix（可观测性正确性）
- **关联**：ISSUE-017（新增并关闭）、journal/2026-10-22

## 1. 背景

真实邮件验证时，场景 `ORDER_CREATE_FAIL` 在 YAML 中配置 `level: ERROR`，
但审计日志输出 `level=WARN`。最初怀疑是命令行覆盖导致场景配置丢失，检查代码后确认：
**管线按「事件与场景取高」得到生效级别并用于渲染/优先级，但审计写的是事件原始级别**。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 审计记录非生效级别 | 真实运行：`NOTIFYX_AUDIT ... scene=ORDER_CREATE_FAIL level=WARN`，YAML 场景为 ERROR；`NotifyPipeline.handle` 已算出 `Level level`，但 `audit()` 读 `event.level()` | `dispatch → sendOne → audit` 传递生效 `level` |
| 2 | reject/defer 的审计调用没有生效级别 | `reject`/`defer` 直接调用旧签名 | 传 `event.level()`（未进入渲染链路，取原始级别即可） |
| 3 | 没有测试守住「生效级别」 | 现有用例只断言 status | 新增 2 个用例：事件 WARN+场景 ERROR → 审计 ERROR；事件 CRITICAL+场景 INFO → 审计 CRITICAL |

## 3. 改动点

- `NotifyPipeline`：`sendOne` 增加 `Level level` 参数；`audit(event, level, ...)` 使用该级别；
  `reject`/`defer` 以 `event.level()` 调用。
- `EffectiveLevelAuditTest`：2 个用例，分别覆盖「场景更高」与「事件更高」。
- 测试数：core 99 → 101；全量 188 → 190（另有 1 个 live 冒烟默认跳过）。

## 4. 验证

```text
mvn -B -pl notifyx-core test → Tests run: 101, Failures: 0
mvn -B clean verify          → BUILD SUCCESS（190 tests，live 冒烟 1 skipped）
```

用户已确认 iter-18 的两封真实邮件均收到；本修复不改变投递行为，只修正审计字段。

## 5. 回滚

还原 `sendOne/audit` 的签名与调用即可；测试可保留（它会重新暴露该不一致）。

## 6. 下一步

- k8s 部署验证（Secret 注入 SMTP 凭据）。
- P2：Redis store / 跨实例聚合。
