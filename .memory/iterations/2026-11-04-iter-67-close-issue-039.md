# iter-67 · ISSUE-039 废弃 dedupe.default-window

- **日期**：2026-11-04
- **类型**：fix / docs（关闭 ISSUE-039）
- **触发**：ISSUE-039 确认 `notifyx.dedupe.default-window` 是全链路死配置，配置语义需统一。
- **关联决策**：D-033

## 1. 问题

`notifyx.dedupe.default-window` 在整条装配链路中均未被消费：

| 层级 | 是否读取该字段 |
| --- | --- |
| `NotifyxProperties.Dedupe.defaultWindow` | ✅ 定义（默认 5m） |
| `NotifyxAutoConfiguration` 装配 scene | ❌ 用 `defaults.silent-window` 填 scene |
| `SceneRegistry.of` / `find` | ❌ 用 scene.silentWindow 或 defaults.silentWindow |
| `NotifyPipeline.resolveSilentWindow` | ❌ `event > scene > 硬编码 5m` |
| `DedupeFilter` | ❌ 用 `ctx.silentWindow()`（pipeline 已解析好的） |

Java 代码里唯一的读取点是 `NotifyxDiagnostics` 诊断表的打印行。

## 2. 决策（D-033）

二选一后选方案 1：**废弃 `dedupe.default-window`，统一用 `defaults.silent-window`**。

- 方案 2（让 dedupe.default-window 真正生效、插优先级链）会改变现有 scene 装配时已被 defaults 填充的行为，可能让已有依赖 scene.silentWindow = defaults.silentWindow 语义的用户在升级后 dedupe 窗口变成了 dedupe.default-window，属于静默行为变更。
- 方案 1 零行为影响（本来就没人读），向后兼容（YAML 里配了不会报错，启动诊断会提示废弃）。

## 3. 改动

| 文件 | 改动 |
| --- | --- |
| `NotifyxProperties.Dedupe.defaultWindow` 字段 + getter + setter | 加 `@Deprecated` + Javadoc 指向 `Defaults.silentWindow` |
| `NotifyxDiagnostics` 诊断表打印行 | 改用 `properties.getDefaults().getSilentWindow()` + 废弃提示文案 |
| `docs/design.md` §14.2 | 新增废弃配置项表 |
| `README.md` YAML 示例 dedupe 段 | 注释形式标注废弃 |
| `.memory/decisions.md` | 新增 D-033 |
| `.memory/issues.md` | ISSUE-039 → CLOSED |
| `.memory/STATE.md` | 新增 §19 |

## 4. 验证

`mvn -B -ntp clean verify` → BUILD SUCCESS（结果见 STATE §19）。
无新增测试——本迭代为配置语义治理，不改变运行时行为。

## 5. 兼容性

- ✅ 既有 YAML 配了 `dedupe.default-window` 不报错（字段保留）；
- ✅ 运行时行为完全不变（本来就没人读）；
- ✅ IDE Configuration Metadata 自动根据 `@Deprecated` 标记废弃；
- ⚠️ 1.1.0 小版本建议移除该字段（复审条件见 D-033）。
