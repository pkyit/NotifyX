# CLAUDE.md

本文件面向 Claude Code 及兼容 AGENTS.md 协议的 AI 编码助手。**不要重复 AGENTS.md 的内容**；本文件只做引导与强制声明，冲突时以 `AGENTS.md` 为准。

## 强制阅读顺序

1. `AGENTS.md` — 项目定位、模块边界、实现红线、质量门禁、Git 规范
2. `docs/design.md` — 架构契约（实现与文档冲突时先改文档或记录偏离）
3. `.memory/STATE.md` — 当前交付状态与范围（P0/P1 SDK；P2+ 未经明确要求不要实现）

## 必须使用 .skills/ 下的项目技能

`.skills/` 是本仓库的**强制规范**，触发对应场景时必须加载并遵循，不得绕过、不得用通用习惯替代：

| 技能 | 路径 | 强制使用场景 |
| --- | --- | --- |
| Git 提交标准 | `.skills/skills-git-commit-standard/SKILL.md` | 任何 `git add / commit / push` 之前 |
| NotifyX Git 工作流程 | `.skills/skills-notifyx-git-workflow/SKILL.md` | 推送代码、合并分支、升级版本号、发布 |
| K8s Manifest 生成 | `.skills/skills-k8s-manifest-generator/SKILL.md` | 生成或修改 Kubernetes YAML |
| Linux Bash | `.skills/skillslinux-bash/SKILL.md` | 编写或调试 bash 脚本（注意本机为 PowerShell，勿混用语法） |

- 技能文件与 `AGENTS.md` 冲突时，先在对话中提出，不得静默偏离。
- 新增 / 修改 `.skills/` 技能时，必须同步更新本表与 `AGENTS.md` 的「项目技能」小节。

## 核心红线速览（详见 AGENTS.md）

- 通知永远是旁路：切面 / Appender 内部异常不得抛回业务线程。
- 禁止阻塞业务线程的同步 I/O 与无超时 HTTP；异步路径必须有界、可关闭、可观测。
- 密钥、Token、收件人进入日志 / 指标 / `toString()` 前必须脱敏；不提交任何真实凭据。
- 交付前跑与改动范围匹配的 `mvn -B -ntp clean verify`；测试未通过不得提交。
- 未获明确授权不执行 `deploy`、不发布 Central、不打正式 tag、不改版本号。
