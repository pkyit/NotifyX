# 决策与刻意偏离（D-NNN）

> 记录**与设计书不一致**或**设计书未覆盖**的落地决策。格式：背景 → 决策 → 理由 → 影响 → 复审条件。
> 编号只增不改；推翻旧决策时新增条目并写 `supersedes D-00X`。

---

## D-001 core 使用自定义 `NotifyxOrdered`，不使用 Spring `Ordered`

- **日期**：2026-10（iter-00 基线确认）
- **状态**：生效
- **背景**：Filter / Channel 需要排序，Spring 的 `Ordered` 是最自然的选择。但 ADR-003 要求
  `notifyx-core` 零 Spring 依赖，且 `CoreArchitectureTest` 用 ArchUnit 强制断言。
- **决策**：core 自定义最小排序契约 `io.github.pkyit.notifyx.core.spi.NotifyxOrdered`
  （单方法 `int order()`，默认 0）。Spring 侧适配时再桥接，不在 core 引 Spring。
- **理由**：违反 ADR-003 的代价是「core 被打上 Spring 烙印」——无法在非 Spring 场景复用，
  并让 ArchUnit 铁律失效。排序契约只有 1 个方法，自研成本几乎为零。
- **影响**：用户自定义 Filter 若已实现 Spring `Ordered`，需要在注册时适配（后续可在
  `notifyx-spring` 加 `Ordered` → `NotifyxOrdered` 桥接，目前未做）。
- **复审条件**：若出现第三个与排序相关的 SPI，考虑抽出独立的排序抽象。

## D-002 场景解析前置，`SceneRoutingFilter` 只裁决「场景是否启用」

- **日期**：2026-10（iter-00 基线确认）
- **状态**：生效
- **背景**：设计书 §9.2 的漏斗把「场景路由」列为第 2 级 Filter。但 `FilterContext` 需要携带
  场景配置（模板、渠道、接收人、静默期、重试次数），否则每个 Filter 都要自己去查注册表。
- **决策**：`NotifyPipeline` 在进入 Filter 链**之前**完成场景解析与合并（场景配置 → defaults 兜底），
  `SceneRoutingFilter` 只回答一个问题：该场景是否 `enabled`，未配置时按默认策略放行或拒绝。
- **理由**：Filter 链保持「无状态裁决」，场景配置只解析一次，避免重复查表和语义分裂；
  同时让用户在 900+ 区插入的 Filter 能直接读 `ctx.scene()`，不必理解注册表。
- **影响**：`SceneRoutingFilter` 的职责比设计书描述的窄；新增「场景分流到不同渠道集」类需求时，
  应扩展场景解析器而不是往这个 Filter 里塞逻辑。
- **复审条件**：出现「同一场景按参数路由到不同渠道」的需求时重新设计（预计 P2 之后）。

## D-003 失败落盘当前是有界内存实现

- **日期**：2026-10（iter-00 基线确认）
- **状态**：临时（P1 后续补齐）
- **背景**：§9.9 要求失败落盘 + 补偿。P0 的验收不涉及落盘介质。
- **决策**：`FailureStore` SPI 已定稿，默认实现 `InMemoryFailureStore`（有界、淘汰最旧）。
  local-file / JDBC 实现推迟到 P1 后续。
- **理由**：先固定 SPI 形状与调用点（失败时写、启动时可重放），介质实现可以后补而不改调用方。
- **影响**：**进程重启即丢失败记录**，不能作为审计凭证；README 需明确声明。
- **复审条件**：P1 启动「失败落盘补偿」迭代时，新增 `LocalFileFailureStore` 并补重启重放测试。

## D-004 渠道自动配置放在各自渠道模块

- **日期**：2026-10（iter-00 基线确认）
- **状态**：生效
- **背景**：`notifyx-spring-boot3-autoconfigure` 集中装配最省事，但会让它直接依赖
  jakarta.mail / 各 IM SDK，违反第 5 章「autoconfigure 不直接依赖渠道」。
- **决策**：每个渠道模块自带 `*ChannelAutoConfiguration` + 自己的
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。
  业务方引哪个渠道，就激活哪段装配。
- **理由**：依赖方向 `channel-* → core`，autoconfigure 不反向依赖；用户不引渠道时
  classpath 中根本没有对应 SDK，天然满足「零依赖可启动」（T13）。
- **影响**：`ZeroDependencyStartupTest` 需要显式 `AutoConfigurations.of(..., LogChannelAutoConfiguration.class)`；
  自动装配顺序由 `@AutoConfiguration` 的相对顺序保证，不依赖 import 文件顺序。
- **复审条件**：若渠道数量超过 8 个导致 imports 文件难维护，再考虑聚合（但不得反向依赖 SDK）。

## D-005 新增 `notifyx-logback` 独立模块

- **日期**：2026-10-22（iter-01 设计）
- **状态**：生效
- **背景**：P0 要求 Logback Appender（§8.1，L0 接入），但 §5.1 模块树没有列出它。
  可选项：塞进 `notifyx-spring`（引入 logback-classic 到 AOP 模块）或塞进 autoconfigure
  （依赖方向错误）。
- **决策**：新增 `notifyx-logback`，依赖 `notifyx-core` + `logback-classic`（`provided`），
  Spring 绑定器放在同模块内并以 `@ConditionalOnClass` 守护。**不含业务依赖**。
- **理由**：Appender 只有 L0 用户需要，独立模块让不使用它的人零成本；logback 是
  provided，不向业务方传递日志实现版本。
- **影响**：模块树/依赖图需同步更新（README、§5.1 待补）；`notifyx-bom` 增加条目。
- **复审条件**：若 Appender 需要与 Spring 生命周期强绑定（例如优雅关闭时 flush），
  再评估把它并入 `notifyx-spring`。

## D-006 场景配置在装配期合并，运行期不查环境

- **日期**：2026-10-22（iter-00 基线确认）
- **状态**：生效
- **背景**：§14.2 允许「YAML → Admin pull → 环境变量」多来源合并。
- **决策**：P0 只在装配期把 `notifyx.scenes.*` 合并成不可变 `SceneConfig`，运行期零查表。
  Admin pull 模型（ADR-007）落地时以「重置注册表引用」的方式热更新，不改 Filter 签名。
- **理由**：运行期不可变让 Filter 链完全无锁，也避免配置读取成为热点。
- **影响**：Admin 热更新前需评估注册表替换的可见性（P4）。
- **复审条件**：P4 做 Admin 配置同步时。

## D-007 Appender 的 `extractBizId` 用子元素而非属性

- **日期**：2026-10-22（iter-01）
- **状态**：生效
- **背景**：设计书 §8.1 示例为 `<extractBizId regex="orderId=(\w+)" group="1"/>`。
  实测 logback 1.5.34（Boot 3.5 默认）的 model 解析器**只读取嵌套元素的 `class` 属性**，
  普通属性不会写入对象：Joran status 显示 “Assuming default type [ExtractBizId]”，
  随后直接 `appender started`，`getRegex()` 为 null——静默失效，最危险的那种失败。
- **决策**：配置写法改为
  ```xml
  <extractBizId>
      <regex>orderId=(\w+)</regex>
      <group>1</group>
  </extractBizId>
  ```
  同时提供扁平写法 `<extractBizIdRegex>` / `<extractBizIdGroup>`；
  启动时若发现 `ExtractBizId` 存在但 regex 为空，写一条 WARN 到 logback status，
  把「抄了旧文档」变成可见告警而不是静默失效。
- **理由**：必须与 Boot 3.5 真实运行时一致；文档再漂亮，解析不了就是 bug。
- **影响**：README 与示例统一用子元素；与设计书 §8.1 存在文字差异（已在 README 标注）。
- **复审条件**：logback 修复嵌套属性解析后，恢复属性写法并保留子元素兼容。

## D-008 内部 logger 前缀硬编码，不做配置化

- **日期**：2026-10-22（iter-01）
- **状态**：临时
- **背景**：防自递归要求过滤 NotifyX 自身日志。实现硬编码两个前缀：
  `io.github.pkyit.notifyx`（框架 logger）与 `NOTIFYX_`（审计/兜底渠道 logger）。
- **决策**：P0 不做配置化；若业务方把自有代码放在 `io.github.pkyit.notifyx.*` 下会被一起过滤。
- **理由**：配置化会引入「用户写错前缀 → 自递归风暴」的新风险；硬编码覆盖 99.9% 场景。
- **影响**：示例工程必须用 `com.example.*` logger 才能演示（已改）。见 ISSUE-012。
- **复审条件**：P1 做诊断表时，评估增加 `exclude-logger-prefixes` 配置并加安全校验。

## D-009 Starter 增加 `NotifyxStarter` 标记类

- **日期**：2026-10-22（iter-02）
- **状态**：生效
- **背景**：Central 对 jar packaging 强制要求 `-sources.jar` 与 `-javadoc.jar`。
  `notifyx-spring-boot3-starter` 是刻意的空聚合模块，source 插件输出
  “No sources in project. Archive not created.”；只放 `package-info.java` 时 javadoc 直接报
  `error: No public or protected classes found to document.`，发布链路会卡死。
- **决策**：加一个不可实例化的 `NotifyxStarter` 标记类 + `package-info.java`；
  类中明确写「不要引用、不要加逻辑」。
- **理由**：这是在不破坏「starter 无逻辑」承诺的前提下，让发布链路可闭环的最小改动。
  另一种做法是 hack source/javadoc 插件强制产空 jar，脆弱且难维护。
- **影响**：starter jar 多一个 class 文件；用户依赖不变，零行为影响。
- **复审条件**：若未来 Maven Central 允许空模块豁免，可删除标记类。

## D-010 质量门禁：轻量格式检查 + 固定旧版 Checkstyle + 覆盖率 ratchet

- **日期**：2026-10-22（iter-03）
- **状态**：生效
- **背景**：§17.3 要求格式、静态分析、覆盖率三类门禁。试点发现三个现实约束：
  1. 全量格式化（palantir/google-java-format）会产生 6000+ 行纯机械 diff；
  2. Checkstyle 14.x 用 Java 21 目标编译，在 JDK 17 的 Maven 下抛 `UnsupportedClassVersionError`；
  3. 当前 core 覆盖率 52.9%，离设计目标 90% 很远。
- **决策**：
  - Spotless 只做**低风险步骤**：清理未使用 import、import 分组排序、去行尾空白、补文件末尾换行。
  - Checkstyle 固定 **10.26.1**（Java 11+ 可运行），规则集只保留会引发线上事故的项。
  - JaCoCo 采用**分模块 ratchet**：默认 40%、core 65%、logback 80%；
    设计目标（全局 80% / core 90%）作为 ISSUE-013 的待办，而不是立刻把门禁设成达不到的值。
  - 行尾统一 LF，并用 `.gitattributes` 固化，避免 Windows 开发机与 Linux CI 反复产生整文件 diff。
- **理由**：门禁的价值是「拦住新增问题」，不是「一次性把历史代码翻新」。
  先让门禁 100% 绿并可持续，再逐步抬高阈值，比设一个永远红的门禁更有效。
- **影响**：本次 92 个文件出现 2 行左右的机械变更（EOF/import），已单独成一次提交。
- **复审条件**：core 覆盖率到 80% 后把门禁抬到 0.80；到 90% 后抬到 0.90 并移除 ratchet 说明。
