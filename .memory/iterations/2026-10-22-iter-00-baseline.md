# iter-00 · 基线盘点与记忆库建立

- **日期**：2026-10-22
- **分支**：`chore/memory-baseline` → `master`
- **类型**：chore（文档 / 工程记忆）
- **关联**：ISSUE-001、ISSUE-008、ISSUE-011

## 1. 背景

项目已有一个可构建的 1.0.0-SNAPSHOT：core + spring + autoconfigure + starter + 三渠道 + sample，
`mvn verify` 全绿。但「实际落地与设计书的偏差」「哪些事还没做」「踩过什么坑」只存在于对话里，
git 历史只有 commit 标题。按要求建立可持续回溯的 `.memory/` 记忆目录，并做一次基线盘点。

## 2. 问题点

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 无工程记忆，历史决策不可回溯 | 无 `.memory/`；`git log` 仅 commit 标题 | 本迭代建立记忆库与写入规则 |
| 2 | P0 发布链路缺失 | `pom.xml` 无 GPG / central-publishing / source+javadoc 绑定 | ISSUE-002，iter-02 |
| 3 | 质量门禁 / CI 缺失 | `Test-Path .github` → False | ISSUE-003，iter-03 |
| 4 | L3 Spring Event 桥接与 MDC 未实现 | `notifyx-spring` 仅 5 个类，无 Event 相关 | ISSUE-004，iter-04 |
| 5 | Health / 诊断表未实现 | 无 `HealthIndicator` 实现；`Diagnostics` 仅 2 字段 | ISSUE-006，iter-05 |
| 6 | 两份设计书内容完全相同 | SHA256 双方均为 `6F9AE595…73EE4` | ISSUE-008，待确认后收敛 |
| 7 | PowerShell 读 UTF-8 中文乱码 | `Get-Content` 输出 `鍏滃簳…`，`read` 工具正常 | ISSUE-011，脚本统一 `-Encoding UTF8` |

## 3. 改动点

新增 `.memory/`：

- `README.md`：记忆库规则（何时写、硬规则、回溯命令、分支约定）。
- `STATE.md`：状态快照（已实现 / P0 剩余 / P1 / 范围外 / 偏离 / 下一步队列）。
- `decisions.md`：D-001 ~ D-006，其中 D-001/D-002/D-003/D-004 是交付说明里点名的刻意偏离，
  D-005 是本轮新增模块决策，D-006 是运行期配置不可变的取舍。
- `issues.md`：ISSUE-001 ~ ISSUE-011，含证据与处理计划。
- `env.md`：构建工具链、`10.10.10.102` 服务器、k8s 六节点（**不含任何凭据**）。
- `iterations/`：本文件起头，后续每次合并一份。

**代码零改动**（本次只落记忆与盘点）。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS
→ Tests run: 20 (core) + 3 (autoconfigure)，共 26 个，0 失败
```

## 5. 回滚

删除 `.memory/` 目录即可；不影响任何生产代码。

## 6. 下一步

iter-01：`notifyx-logback`（L0 / §8.1 / T7），见 STATE.md 第 5 节队列。
