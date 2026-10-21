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

## D-011 模板配置用「不做占位符替换」的 Binder 读取

- **日期**：2026-10-22（iter-04）
- **状态**：生效
- **背景**：模板语法是 `${userName}`。`@ConfigurationProperties` 的默认绑定会把 `${...}`
  当作 Spring 占位符解析，而 `SystemEnvironmentPropertySource` 会把 `userName` 关联到
  环境变量 `USERNAME`。实测 L3 发布 `userName=bob`，通知内容却渲染成 `admin`（Windows 用户名）。
  这是「看起来正常、内容悄悄错」的高危问题，且只在变量名与环境变量撞车时出现，极难排查。
- **决策**：`NotifyxAutoConfiguration` 单独用
  `new Binder(ConfigurationPropertySources.get(environment), value -> value)`
  重新绑定 `notifyx.templates`（identity PlaceholdersResolver），忽略已解析的 `NotifyxProperties` 值；
  绑定失败则回退到常规值并记 warn。
- **理由**：模板占位符与框架占位符必须有明确边界。让用户在 YAML 里写 `\${userName}` 转义
  既难记又容易漏，不如框架层彻底隔离。
- **影响**：模板配置**不支持** Spring 占位符（例如 `notifyx.templates.x.title=${spring.application.name}`
  不会被替换）。这是刻意的：模板变量只由事件参数填充。已写进 README 与发布测试。
- **复审条件**：若未来支持 FreeMarker/Thymeleaf 等外部模板（P2），在模板引擎层统一占位符语义。

## D-012 优雅关闭的 phase 晚于 Web 优雅停机

- **日期**：2026-10-22（iter-05）
- **状态**：生效
- **背景**：设计书 §9.10 写「phase 设为较早（比 Web 容器先停）」。但实际顺序上，
  先让 Web 容器停止接收新请求、再排空通知队列，才能避免关闭窗口里涌入的新事件被直接拒绝；
  同时 `SmartLifecycle` 仍早于普通 Bean 销毁，数据源/Redis 依旧可用（这是不用 `@PreDestroy` 的原因）。
- **决策**：`NotifyxLifecycle.PHASE = SmartLifecycle.DEFAULT_PHASE - 2048`，
  低于 Spring Boot Web 优雅停机阶段、高于普通 Bean 销毁；关闭期间新事件被
  `PriorityNotifyExecutor` 拒绝并记 warn，等失败落盘实现（D-003）后再改为落盘补偿。
- **理由**：最大限度减少「关闭过程中丢通知」的窗口；与 §9.10 的核心目的（依赖仍可用时排空）一致。
- **影响**：如果部署方自行调整 Web 优雅停机 phase，需要重新评估本值。
- **复审条件**：失败落盘文件实现后，补充「关闭时未排空事件落盘」并重估 phase。

## D-013 alias 坐标用 relocation + 依赖壳 + 标记类

- **日期**：2026-10-22（iter-07）
- **状态**：生效
- **背景**：ADR-001 要求主坐标 `notifyx-spring-boot-starter` + alias `notifyx-spring-boot3-starter`，
  并说 alias 的 POM 用 `<relocation>`；§19.2 又描述 alias 为「依赖壳」。两者需要合并落地，
  且 Central 对每个 jar 强制要求 sources/javadoc。
- **决策**：
  1. 原 `-boot3-` 模块 `git mv` 为主坐标，业务依赖全部切到主坐标；
  2. 新建 `-boot3-` alias 模块：`<relocation>` 指向主坐标 + 依赖主坐标（依赖壳兜底）；
  3. alias jar 里放一个不可实例化的标记类与 package-info，保证 sources/javadoc 有产物。
- **理由**：relocation 是给用户「静默迁移提示」的正确机制；依赖壳保证 relocation 在某些
  构建工具/镜像仓库不生效时仍能拿到实现；标记类是对 Central 发布校验的最小妥协（同 D-009）。
- **影响**：模块数 11 → 12；发布物多一个坐标；README 必须首屏解释「引哪个」。
- **复审条件**：Boot 4 成为主流（>50%）时评估废弃 alias（ADR-001 的复评条件）。

## D-014 聚合先做 T0 实例内，且默认关闭

- **日期**：2026-10-22（iter-08）
- **状态**：生效
- **背景**：§9.5 的完整聚合依赖跨实例状态（Redis `INCR` 或 MQ consumer 侧）。当前 SDK 没有
  Redis/MQ，若强行实现「看起来跨实例」的聚合会给出错误承诺。
- **决策**：
  1. 只做 **T0 实例内窗口聚合**，README / 诊断表明确「多实例下每个实例各发一条摘要」；
  2. 聚合**默认关闭**（`notifyx.aggregate.enabled=false`），开启后才在去重 REJECT 时记录；
  3. 聚合器自带 daemon 调度线程，生命周期关闭时「执行器排空 → 聚合汇总 → 渠道关闭」；
  4. 摘要带唯一 `dedupeKey=agg:<指纹>:<firstAt>`，避免被去重吞掉。
- **理由**：聚合是「行为变化」类能力，默认开启会让存量用户的通知条数骤变；先默认关、文档讲清边界，
  等 T1/T2 落地后再考虑默认值。
- **影响**：每个启用聚合的实例多一个 daemon 线程；窗口内的摘要最多延迟 window（默认 10s）。
- **复审条件**：Redis store / MQ consumer 落地后，评估跨实例聚合与默认开启。

## D-015 升级只改渠道，不改 Level

- **日期**：2026-10-22（iter-09）
- **状态**：生效
- **背景**：§9.6 的 `upgrade-to` 是渠道列表。直觉上可以顺带把事件 Level 提到 CRITICAL，
  但 CRITICAL 在 §9.2 的旁路矩阵里会跳过免打扰与限流，影响面远大于「换渠道」。
- **决策**：升级态只替换 `SceneConfig.channels`（`withRouting(scene.level(), upgradeTo)`），
  Level 保持原值；需要级别提升时由场景配置/事件级别决定。
- **理由**：把「触达更强」与「绕过治理」解耦，避免一个升级动作悄悄关掉限流与免打扰。
- **影响**：升级消息的模板 `level` 仍是原级别。
- **复审条件**：如果用户明确需要「升级即 CRITICAL」，再增加 `upgrade-level` 显式配置项。

## D-016 升级计数包含被去重的事件

- **日期**：2026-10-22（iter-09）
- **状态**：生效
- **背景**：「连续 N 次未恢复」的语义是故障持续存在，而不是「成功发出了 N 条通知」。
  去重/静默会拦住重复通知，但故障本身仍在持续。
- **决策**：`EscalationPolicy.record` 在管线解析场景后立即调用，**早于**过滤链，
  因此重复事件也计入阈值；恢复靠 `reset-scenes` 或 `reset-on-success`。
- **理由**：否则「去重窗口很长」会让升级永远触发不了，与运维诉求相反。
- **影响**：被免打扰/限流拒绝的事件也计数；极高频噪声可能加速升级，需配合 `within` 窗口调节。
- **复审条件**：出现「噪声导致误升级」反馈时，改为只统计通过去重的事件。

## D-017 local-file 失败落盘：手写扁平 JSON + 容量上限 + 补偿边界

- **日期**：2026-10-22（iter-10）
- **状态**：生效（部分取代 D-003 的 local-file 部分）
- **背景**：core 只允许 SLF4J，不能引 Jackson；但 §9.9 要求 JSON 文件记录。
  同时磁盘上限、乐观锁、重启可读都必须成立。
- **决策**：
  1. `LocalFileFailureStore` 用手写扁平 JSON 读写（固定 9 个字段），只支持字符串/数字；
     损坏文件跳过并 debug 日志，不让整次扫描失败；
  2. 文件名白名单化，按 `yyyyMMddHH` 分片，写临时文件后 `ATOMIC_MOVE`；
  3. 容量默认 10,000 文件 / 100MB：超限先删最旧 DEAD、再删最旧文件，累计 `overflowCount()`；
  4. **只做存储与状态机，不做自动重投**：`envelope` 目前只有 eventId，完整重投需要
     `NotifyEnvelope` 序列化（ISSUE-015，随 MQ/JDBC 一起设计）。
- **理由**：先把「重启不丢 + 可查 + 可标记」做扎实；自动补偿涉及报文契约，仓促实现会返工。
- **影响**：默认仍是内存实现，`notifyx.fallback.store=file` 才启用；磁盘上限保护避免写满磁盘。
- **复审条件**：`NotifyEnvelope` 落地后实现补偿任务，并把本节第 4 条替换为完整状态机。

## D-018 补偿执行语义：优先真实结果，自定义门面按提交成功

- **日期**：2026-10-22（iter-16）
- **状态**：生效
- **背景**：`NotifyKit` 的 6 个方法全是 void（§6.7 刻意克制），补偿无法知道「重投到底成没成」。
  另外多实例可能同时扫同一存储，必须用乐观锁避免重复补偿。
- **决策**：
  1. `NotifyPipeline.handle` 改为返回 boolean（REJECT/异常 false、DEFER true、dispatch 无失败 true），
     `DefaultNotifyKit` 暴露 `deliverSync(event)` 供补偿器使用；
  2. 补偿器优先走 `deliverSync`；若用户自定义了 `NotifyKit`（非 `DefaultNotifyKit`），
     退化为 `send` 并**按提交成功处理**，在 README/本节声明这一限制；
  3. 定时调度默认关闭（`fallback.compensation.enabled=false`），开启后**每个实例都扫**，
     靠 `updateStatus(SENDING)` 的版本 CAS 抢占；ShedLock/MQ 单消费者属于 P2/P3；
  4. 解码失败直接 `DEAD`，不消耗重试次数；失败按 `retry-backoff` 重排，达到 `max-attempts` 转 `DEAD`。
- **理由**：不为了补偿而破坏 `NotifyKit` 的 6 方法契约；真实结果只在默认实现上可得，诚实标注退化路径。
- **影响**：`handle` 返回值是源码兼容、二进制不兼容的改动（1.0.0-SNAPSHOT 阶段可接受）。
- **复审条件**：MQ 单消费者模式落地后，补偿改由 consumer 负责，本执行器降级为本地兜底。
