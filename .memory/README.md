# .memory —— NotifyX 项目记忆库

这个目录是**给未来的自己（也是给 AI 代理）看的工程记忆**：为什么这样改、踩过什么坑、哪些事还没做。
源码回答「是什么」，`docs/design.md` 回答「设计成什么样」，本目录回答「**实际落地时发生了什么**」。

## 什么时候写

| 时机 | 写什么 | 放在哪 |
| --- | --- | --- |
| 每个大改动（一个分支 / 一次合并） | 问题点、改动点、验证方式、回滚方式 | `iterations/YYYY-MM-DD-iter-NN-<slug>.md` |
| 偏离设计书或做出取舍 | 背景、决策、理由、影响、复审条件 | `decisions.md`（D-NNN） |
| 发现缺陷 / 技术债 / 环境坑 | 现象、证据、影响、处理计划、状态 | `issues.md`（ISSUE-NNN） |
| 状态变化（完成了什么、下一步是什么） | 覆盖更新快照 | `STATE.md` |
| 每日工作与思路（含验证结果、待查项） | 日期、目标、过程、结论、下一步 | `journal/YYYY-MM-DD.md` |
| 环境事实（服务器、构建工具链） | 只写非敏感信息 | `env.md` |

**硬规则**

1. 每次合并到 `master` 前，必须更新 `iterations/` 与 `STATE.md`，二者随代码同一个提交进仓库。
2. 问题点必须有证据（命令输出、测试名、文件行号），不写「感觉有问题」。
3. **不写任何明文凭据**（密码、token、私钥）。只写「凭据见交付方，不入库」。本仓库是公开仓库。
4. 记忆是追加式的：不删除旧结论；结论被推翻时新增一条并注明 `supersedes D-00X`。

## 目录

```
.memory/
├── README.md                  # 本文件：记忆库的规则
├── STATE.md                   # ★ 当前状态快照：已实现 / 未实现 / 下一步
├── decisions.md               # 决策与刻意偏离（D-NNN）
├── issues.md                  # 问题点与技术债（ISSUE-NNN）
├── env.md                     # 环境事实（构建/服务器/集群）
├── journal/                   # 每日工作与思路
│   └── YYYY-MM-DD.md
└── iterations/                # 每次大改动的改动记录
    └── YYYY-MM-DD-iter-NN-slug.md
```

## 怎么回溯（recall）

```bash
# 最近发生了什么
git log --oneline -20 -- .memory
# 某个模块/主题被改过几次
git log --oneline -- .memory | head
grep -rn "logback\|Appender" .memory
# 当前状态
cat .memory/STATE.md
# 全部未关闭问题
grep -n "状态：OPEN" .memory/issues.md
```

## 分支约定

每次大改动：新功能 `feature/<slug>`、缺陷修复 `bugfix/<slug>`，完成 → 本地 `mvn verify` → 写记忆 →
`git merge --no-ff` 回 `master` → push。合并提交信息里带 `[iter-NN]`，与 `iterations/` 文件一一对应。
开工前先 `git pull`。

## 与设计书的关系

`docs/design.md` 是**契约**（章节号可被引用，实现与文档冲突以文档章节号为准）。
本目录是**事实与偏差**：当实现刻意偏离设计书时，在 `decisions.md` 记一条 D-NNN，说明为什么偏离、
如何守住底线、什么条件下需要改回去。
