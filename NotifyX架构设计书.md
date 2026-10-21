# NotifyX 架构设计书

**业务语义层告警通知平台 · SDK + Admin + Consumer 三件套**

| 项 | 内容 |
| --- | --- |
| 项目名 | NotifyX（notification exchange，通知交换层） |
| 一句话定位 | 在业务代码里埋语义点，用一行代码 / 一个注解 / 一条日志，把「业务发生了什么」可靠地送到人手里 |
| 仓库 | `git@gitee.com:pengkaiyan/NotifyX.git` |
| GroupId | `io.github.pkyit` |
| 包名根 | `io.github.pkyit.notifyx` |
| 技术基线 | JDK 17（编译目标 17）、Spring Boot 3.5.16、MyBatis-Plus 3.5.x、sms4j 3.3.5 |
| 开源协议 | Apache-2.0 |
| 文档版本 | v1.0（架构评审版，基于讨论稿 v0.3 修订） |
| 文档日期 | 2026-10-21 |
| 文档状态 | 设计冻结候选 — 第 3 章 ADR 一经评审通过即进入实现阶段 |
| 对标产品形态 | XXL-JOB（SDK + Admin + 执行器）、Nacos（配置中心 + pull 同步 + 控制台）、sms4j（渠道聚合） |

> **本文档与讨论稿的关系**：讨论稿 v0.1 → v0.3 已确立方向与骨架，本文档在其基础上做架构收敛，并修正了 11 处会在生产环境暴露的设计缺陷。全部修订项集中列在 **第 23 章 评审修订清单**，每一处都给出了「原设计 / 问题 / 修正方案 / 影响面」，便于逐条确认。

---

## 目录

- 第 1 章 执行摘要
- 第 2 章 问题域、定位与边界
- 第 3 章 架构决策记录（ADR）
- 第 4 章 总体架构
- 第 5 章 模块划分与依赖纪律
- 第 6 章 核心领域模型
- 第 7 章 SPI 体系
- 第 8 章 零侵入接入：五层用法（L0–L4）
- 第 9 章 可靠性设计（去重 / 限流 / 防风暴 / 补偿）
- 第 10 章 渠道设计
- 第 11 章 MQ 解耦与分布式部署
- 第 12 章 存储层与信创数据库适配
- 第 13 章 Admin 控制台与 REST API 契约
- 第 14 章 配置字典
- 第 15 章 可观测性
- 第 16 章 安全与合规
- 第 17 章 工程规范与质量门禁
- 第 18 章 测试策略
- 第 19 章 发布与版本策略
- 第 20 章 非功能性需求（NFR）量化指标
- 第 21 章 路线图与里程碑
- 第 22 章 风险登记册
- 第 23 章 评审修订清单（对讨论稿 v0.3 的 11 处修正）
- 第 24 章 附录

---

## 第 1 章 执行摘要

### 1.1 我们要解决的真问题

现有监控体系（Prometheus、Heartbeat、SkyWalking）盯的是**基础设施层**：进程活着吗、堆满了吗、QPS 掉了吗。但线上真正需要人立刻介入的事件，绝大多数发生在**业务语义层**：订单创建失败、支付回调超时、对账不平、风控命中、超管给某个手机号开了账户需要通知机主。

这类事件今天的处理方式是**散点式硬编码**：`mailService.send(...)`、`smsTemplate.send(...)`、钉钉 webhook 的 HTTP 调用散落在几十个类里。后果是四个「无法」：

1. **无法换渠道** —— 从短信改钉钉，要改代码、要发版、要回归。
2. **无法统一频控** —— 一个死循环异常能在 3 分钟内发出去 2 万封邮件，把 SMTP 账号打进黑名单。
3. **无法审计** —— 出了「谁给谁发过什么」的合规问题，答不上来。
4. **无法一键止血** —— 通知风暴发生时，唯一手段是重启应用或改代码。

### 1.2 解决方案形态

NotifyX 提供**三种埋点姿势**，侵入度从零到一行：

```java
// ① 一行门面调用（最直观，就是需求原始形态）
notifyKit.alarm("ORDER_CREATE_FAIL", e, order);
notifyKit.send("ACCOUNT_CREATED", phone, Map.of("user", name));

// ② 一个注解（业务方法体零改动）
@AlarmProtect(scene = "PAY_CALLBACK_FAIL", silent = "10m")
public void onCallback(PayCallback cb) { ... }

// ③ 一行代码都不改（存量系统靠日志 marker 接入）
log.error(ALARM, "orderId={} 创建失败", id);
```

三种姿势最终都收敛到同一条**责任链管线**：

```
场景路由 → 免打扰 → 去重 → 限流 → 聚合 → 投递 → 渠道发送 → 重试 → 落盘补偿 → 审计
```

走什么渠道、发给谁、用什么模板、多久静默一次，**全部由 `scene`（场景码）在配置里决定**，业务代码只负责声明「发生了什么」。

### 1.3 三个不可妥协的产品承诺

这三条是本项目的立身之本，任何实现细节与之冲突时，以此为准：

| # | 承诺 | 技术含义 |
| --- | --- | --- |
| P1 | **零依赖也能跑** | 没有 Redis、没有 MQ、没有数据库、没有 Admin，只引 starter + 配一个 SMTP，应用照常启动、邮件照发。能力逐级降级，**绝不因缺中间件而启动失败**。这是老系统与信创离线环境能否采纳的分水岭。 |
| P2 | **故障不扩散** | 通知链路任何一环挂掉，都不能拖慢或拖死业务主线程。异步、隔离、超时、有界、丢弃计数五件套必须齐全。 |
| P3 | **配置即运维** | 上线后加渠道、关噪声场景、改静默期、一键全局静默，全部改配置生效，不发版。 |

### 1.4 关键架构取舍（详见第 3 章 ADR）

| 决策点 | 结论 | 核心理由 |
| --- | --- | --- |
| 命名 | 主推 `notifyx-spring-boot-starter`，`-boot3-` 作为 alias artifact | Boot 3.5.x 已 EOL，带数字命名会在 Boot 4 时代变成负债 |
| 短信 | 复用 sms4j 3.3.5 | 20+ 厂商适配与故障转移已现成，自研是纯浪费 |
| IM（钉钉/企微/飞书） | **自研**，不用 sms4j-oa-core | 路由链（场景→通道→模板→频控→审计→升级）的控制权必须在自己手里 |
| MyBatis-Plus | 只进 store/admin 模块，**core 零 Spring 零 MP 零 DB** | 否则承诺 P1 直接破产 |
| 渲染时机 | 管线内渲染，`NotifyContext` 携带渲染结果 | 渠道实现压到 80 行以内，模板引擎可替换 |
| MQ 模式 | producer-only + 独立 consumer | 把「要不要 MQ」从框架决策降为配置决策 |
| Admin 同步 | pull 模型 + 版本号比对 | 无 Admin 时 YAML 兜底，网络抖动不影响发送 |

### 1.5 交付节奏

P0（4 周）出 0.1.0：core + 管线 + 门面 + 注解 + Logback Appender + email/webhook 双渠道 + 可发布到 Maven Central 的完整工程骨架。**第一件事是把发布链路跑通**——多数开源项目不是死于设计，是死于最后一步发不出去。

完整路线图见第 21 章，风险登记册见第 22 章。

---

## 第 2 章 问题域、定位与边界

### 2.1 分层定位：NotifyX 站在哪一层

```
┌───────────────────────────────────────────────────────────────┐
│ 运维基础设施层                                                 │
│ 「进程还活着吗」— 端口探活、JVM 堆/GC、中间件存活、指标阈值      │
│ Prometheus · Heartbeat · SkyWalking · Uptime Kuma              │
├───────────────────────────────────────────────────────────────┤
│ ★ 业务语义层  ← NotifyX 的地盘                                 │
│ 「业务还正常吗」— 订单创建失败、账号开通、风控命中、对账不平     │
│ 埋点方式：一行门面 / 一个注解 / 一条日志 marker                 │
├───────────────────────────────────────────────────────────────┤
│ 业务代码层                                                     │
│ 只声明「发生了什么」，不关心「怎么送出去」                       │
└───────────────────────────────────────────────────────────────┘
                              ↓
        渠道插件：email │ webhook │ dingtalk │ wecom │ feishu │ sms4j
```

**与 Prometheus 是互补而非替代**，且存在一条反向数据流：NotifyX 自身以 Micrometer 暴露 `notifyx.*` 指标，可被 Prometheus 抓取。也就是说「业务语义告警的元状态」（发送成功率、队列积压、渠道熔断）仍然由运维体系监控。这形成了一个闭环：NotifyX 负责把业务事件送给人，Prometheus 负责盯着 NotifyX 自己别成为新盲区。

**与消息队列的关系**：MQ 在 NotifyX 里是一种**可选的传输实现**（`notifyx.mode=mq`），不是产品定位。第 2.3 节会明确划出这条边界。

### 2.2 目标用户与采纳路径

| 用户画像 | 环境特征 | 采纳姿势 | 依赖能力梯度 |
| --- | --- | --- | --- |
| 存量老系统维护者 | Boot 2.x/3.x、无 Redis、不敢改代码 | **L0 日志 Appender** | 只有 JVM |
| 单体应用开发者 | Boot 3.x、有 MySQL | L2 注解 + YAML | JVM + DB |
| 微服务团队 | 有 Redis + MQ + 配置中心 | L4 门面 + MQ 模式 + Admin | 全量 |
| 信创/内网离线项目 | 国产 JDK + 达梦/金仓 + TongWeb | L2 注解 + 本地 YAML | JVM + 国产 DB |

**采纳路径设计原则**：用户第一次接入的成本必须低于 10 分钟，且不需要理解责任链、SPI、漏斗这些内部概念。最小路径是「引 starter → 配 3 行 YAML（一个 SMTP）→ 写一行 `notifyKit.send(...)`」。所有高级能力都在这条路径之上按需展开。

### 2.3 非目标（边界划死，防范围蔓延）

这一节的价值高于功能列表。开源项目死于范围蔓延的比死于技术难度的多得多。

| ❌ 明确不做 | 为什么不做 | 边界外的正确做法 |
| --- | --- | --- |
| 通用消息队列 | 会变成第二个 RocketMQ，投入无底洞 | 需要跨系统可靠投递时，NotifyX 把事件写进用户已有的 MQ |
| exactly-once 语义 | 分布式下代价极高，通知场景不需要 | **at-least-once + 幂等键**即止，渠道侧幂等 |
| 业务鉴权 / 权限中台 | 是业务系统的职责，不是通知组件的 | 只管自身接口访问控制 + 发送留痕 |
| 工作流 / 审批流 | 产品形态完全不同 | 用户可用 webhook 渠道对接自己的工作流引擎 |
| 站内信系统 | 需要存储、已读未读、UI，是完整产品线 | 可作为一个渠道插件由社区贡献 |
| 指标采集与告警规则引擎 | 那是 Prometheus 的活 | 反向：把 `notifyx.*` 指标喂给 Prometheus |
| 短信厂商 SDK 适配 | sms4j 已做且做得好 | 直接复用，见 ADR-002 |

**一句话边界**：NotifyX 只做「通知的最后 100 米」——从业务事件产生，到消息抵达渠道网关为止。

### 2.4 术语表（全文统一口径）

| 术语 | 定义 | 示例 |
| --- | --- | --- |
| **scene（场景码）** | 全局唯一锚点，模板/通道/频控/接收人全部挂在它上面。`UPPER_SNAKE_CASE` | `ORDER_CREATE_FAIL` |
| **channel（渠道）** | 一种送达手段的插件实现 | `email` / `dingtalk` |
| **event（事件）** | 一次业务语义发生，`NotifyEvent` 对象 | 订单 X 创建失败 |
| **dedupeKey（指纹）** | 事件去重标识，稳定可复现 | `sha256(scene‖bizId‖...)` |
| **receiver（接收人）** | 手机号/邮箱/openid/机器人 webhook 标识 | `13888888888` |
| **silentWindow（静默期）** | 同指纹在此窗口内只发一次 | `5m` |
| **funnel（漏斗）** | 事件从入口到投递经过的过滤链 | 见 9.2 |
| **capability tier（能力梯度）** | 环境可用中间件决定的降级档位 | T0 只有 JVM / T1 有 Redis / T2 有 MQ |
| **L0–L4** | 五层接入姿势，按侵入度递增 | 见第 8 章 |

---

## 第 3 章 架构决策记录（ADR）

> 每条 ADR 采用统一结构：**背景 → 候选方案 → 决策 → 理由 → 代价 → 复评触发条件**。
> 「复评触发条件」是刻意保留的字段——它承认决策有时效性，避免架构决定被当成永久真理。

### ADR-001 命名与 artifact 发布策略

**背景**：需求方要求 artifact 名体现「业务语义级告警通知」且形如 `xxxx-spring-boot3-starter`。同时 Spring Boot 3.5.16（2026-06-25 发布）是 3.5.x 系列**最后一个开源版本**，该分支 OSS 支持已于 2026-06-30 结束，官方导向迁移至 4.0.x/4.1.x（4.0 于 2025-11 GA，基于 Spring Framework 7）。

**候选方案**：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| A. 只发 `notifyx-spring-boot3-starter` | 明确表达 Boot 3 | Boot 4 时代必须发新 artifact，用户被迫改 `dependency`，历史版本坐标永久带「3」 |
| B. 只发 `notifyx-spring-boot-starter` | 符合官方惯例、面向未来 | 不满足需求方对显式 Boot 3 标识的诉求 |
| **C. 主推 B + alias 发布 A（同一份 jar 双坐标）** | 两个诉求同时满足；未来切 4.x 只改 BOM | 构建配置略复杂；需向用户解释两个坐标关系 |

**决策**：采用 **方案 C**。

- 对外文档、README、快速开始**一律主推** `notifyx-spring-boot-starter`。
- `notifyx-spring-boot3-starter` 作为 **alias artifact** 发布同一份 jar（`maven-jar-plugin` 产物 + 两份 `maven-install`/`nexus-publish` 坐标，alias 的 pom 用 `<relocation>` 指向主坐标，让 IDE 给出迁移提示）。
- 官方约定「不要以 `spring-boot` 开头」（该前缀留给 Spring 官方）——`notifyx-spring-boot-starter` 未违反。

**理由**：Boot 3.5.x 已 EOL 是**已发生的事实**而非预测。一个生命周期预期 3–5 年的开源基础库，不能把「当前依赖的框架大版本号」焊死在主 artifact 名里。

**代价**：alias 坐标会长期存在，需要在 README 首屏一句话解释清楚，否则用户困惑「两个该引哪个」。

**复评触发条件**：Spring Boot 4.x 成为 NotifyX 用户主流（>50%）时，评估是否废弃 `-boot3-` alias。

---

### ADR-002 短信复用 sms4j，IM 渠道自研

**背景**：需求方提出短信直接用 sms4j，避免自己适配各大厂商。

**候选方案**：

| 方案 | 说明 |
| --- | --- |
| A. 短信 + IM 全走 sms4j（含 `sms4j-oa-core`） | 依赖最少，但 OA 层只提供发送能力 |
| **B. 短信复用 sms4j，IM 自研，邮件自研** | 各取所长 |
| C. 全部自研 | 完全可控，但要维护 20+ 短信厂商适配 |

**决策**：采用 **方案 B**。

- **短信** → `org.dromara.sms4j:sms4j-spring-boot-starter:3.3.5`（Apache-2.0，覆盖 20+ 厂商，已适配 Boot 3.x / Jakarta）。NotifyX 侧只写一个 `Sms4jChannel implements NotifyChannel` 适配层，委托 `SmsFactory.getSmsBlend(cfg)`。**optional 依赖**，不引就不存在。
- **IM（钉钉/企微/飞书）** → 自研。每个渠道本质是「一次 HTTP POST + 一个签名函数」，实现成本低于 80 行，换来的是对路由链的完全控制权。
- **邮件** → 自研（Jakarta Mail）。邮件逻辑简单，引入另一个聚合层反而降低可控性。

**理由**：NotifyX 的**核心资产是那条路由链**——场景码 → 通道 → 模板 → 频控 → 审计 → 升级。如果把 IM 交给 sms4j-oa-core，去重、聚合、审计、熔断、升级这些能力就插不进去，NotifyX 会退化成一个「sms4j 的薄包装」，失去存在价值。而短信厂商适配是纯粹的重复劳动，没有差异化价值，正好外包。

**代价**：
1. sms4j 的传递依赖（历史上含 fastjson 等）需要 `<exclusions>` 处理，必须在 CI 里做依赖树断言。
2. 用户会困惑「为什么短信和 IM 来源不同」——README 里必须写清「短信用 sms4j，IM 用我们」。

**复评触发条件**：sms4j 停止维护、协议变更、或其 OA 模块开放足够的扩展点（能挂进去我们的漏斗）时重评。

---

### ADR-003 core 模块零 Spring 依赖

**背景**：承诺 P1 要求「什么都没有的环境也能跑」。

**决策**：`notifyx-core` 只允许依赖 **SLF4J API**，其余全部纯 Java（JDK 17 标准库）。模型、SPI 接口、指纹算法、限流算法、脱敏、模板引擎默认实现，都在 core。

**理由**：
1. **可移植性** —— 未来可复用于 Solon / Quarkus / 纯 Java 进程 / Android 服务端组件，不被 Spring 绑死。
2. **GraalVM 友好** —— 无反射、无动态代理的 core 更容易做 native image（需额外提供 reachability metadata，见 17.5）。
3. **测试成本** —— core 的单测不需要 Spring 上下文，毫秒级执行，能支撑 80% 覆盖率门禁。
4. **架构清晰** —— Spring 相关的东西（AOP、事件桥接、`@ConfigurationProperties`、条件装配）全部隔离在 `notifyx-spring` 与 `notifyx-spring-boot3-autoconfigure`。

**代价**：core 里不能用 `@Autowired`、不能用 Spring 的 `StringUtils`/`Assert`，需要自己写少量工具类（约 200 行）。这是值得的。

**执行方式**：ArchUnit 断言固化（见 5.3），CI 不过即失败。

---

### ADR-004 渲染前置：渠道只看到已渲染的内容

**背景**：模板渲染放在哪一层？

**候选方案**：

| 方案 | 渠道实现复杂度 | 模板引擎可替换性 | 一致性 |
| --- | --- | --- | --- |
| A. 各渠道自己渲染 | 高（每个渠道都要处理模板） | 差 | 各渠道渲染结果可能不一致 |
| **B. 管线统一渲染，Context 携带结果** | 极低 | 好（TemplateEngine 是 SPI） | 强一致 |

**决策**：采用 **方案 B**。`NotifyContext` 里携带 `renderedTitle` 与 `renderedContent`，渠道只负责「把这段内容按自己的报文格式打包 + 签名 + POST」。

**理由**：这是让「新增渠道成本 = 一个 HTTP 调用 + 一个签名函数」成立的关键。渠道实现通常 <80 行，社区贡献门槛极低——对开源项目的生态繁荣至关重要。

**代价**：渠道无法做「按自身格式定制渲染」（例如钉钉 Markdown 卡片 vs 邮件 HTML）。解决方式是引入 **`RenderFormat` 枚举**（`PLAIN` / `MARKDOWN` / `HTML`），管线按目标渠道声明的格式渲染，渠道通过 `NotifyChannel#preferredFormat()` 声明。这样既保持渲染前置，又允许格式差异。**这是本文档对讨论稿 v0.3 的补充设计。**

---

### ADR-005 漏斗顺序：去重必须在限流之前

**背景**：去重、限流、免打扰、聚合的执行顺序。

**决策**：固定顺序，不可配置：

```
① 场景路由 → ② 免打扰拦截 → ③ 去重 → ④ 限流 → ⑤ 聚合窗口 → ⑥ 投递
```

**理由**：如果限流在去重之前，被限流丢弃的请求会**污染去重窗口**——它们已经占了去重槽位但实际没发出去，导致「限流结束后正常流量也被挡住」的经典 bug，且极难排查（现象是「告警莫名消失」）。

**代价**：无实质代价，纯粹是正确的顺序。

**补充约束（本文档新增）**：`CRITICAL` 级别事件的旁路规则必须显式定义，否则「系统持续崩溃」会因为去重而被静默——那正是最需要告警的时刻。完整旁路矩阵见 **9.2**。讨论稿只提到 CRITICAL 直通免打扰与限流，**遗漏了去重环节的旁路策略**，这是一处必须补上的缺口。

---

### ADR-006 MQ 模式：producer-only + 独立 consumer

**背景**：需求方环境不确定，可能有 MQ 也可能没有。

**决策**：抽象一层 `NotifyBus` SPI，模式由配置决定，**业务代码一行不改**：

```yaml
notifyx:
  mode: local    # local | mq
  bus: kafka     # 仅在 mode=mq 时生效
```

- `local` 模式：SDK 内直接走渠道发送。
- `mq` 模式：SDK 退化为 **producer-only**——只做「序列化 + 发出 + 本地落盘兜底」，**不做消费**。统一发送由独立的 `notifyx-consumer` 服务承担。

**理由**：
1. 把「要不要 MQ」从**框架决策**降为**配置决策**，这是应对不确定环境的唯一正确解法。
2. 跨实例重复发送问题交给 **MQ 单消费者 + 幂等键** 解决，**不在 SDK 里用 ShedLock 打补丁**——那是引入第二套分布式协调机制，徒增复杂度和故障面。
3. consumer 独立部署后，渠道密钥、限流规则、聚合窗口可以集中管理，运维成本反而下降。

**代价**：MQ 模式下 `NotifyEvent` 的 `onSuccess`/`onError` 回调钩子**无法跨进程传递**（不可序列化）。这是设计矛盾，必须显式处理——见 **6.4 序列化契约**。

---

### ADR-007 Admin 配置同步：pull 模型

**背景**：Admin 如何把配置下发到 SDK？

**候选方案**：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| A. push（Admin 主动推 / 长连接） | 实时性高 | 需要在业务侧维持连接、Admin 需知道所有客户端地址、网络抖动即失效 |
| **B. pull（客户端定时拉 + 版本号比对）** | 无 Admin 时 YAML 兜底；网络抖动不影响发送；业务侧无 agent | 生效有延迟（秒级） |
| C. 配置中心代理（Nacos/Apollo） | 复用现有设施 | 强绑定，违背零依赖承诺 |

**决策**：采用 **方案 B**，支持长轮询优化实时性。客户端启动后每 N 秒向 Admin 拉取所属应用的场景配置快照，本地缓存 + `version` 比对，变了才热刷新路由表。

**理由**：**pull 模型的容错性是天然的**——Admin 挂了，客户端用本地缓存继续跑；网络断了，用 YAML 兜底。而 push 模型下 Admin 挂掉会导致配置状态不可知。通知系统的配置同步必须「失败无害」。

**代价**：配置生效有秒级延迟。可接受——通知配置不是秒级敏感的东西。需要紧急止血时用「一键全局静默」接口，该接口设计为**客户端每次 pull 都会读到的强制位**，绕过快照版本比对。

---

### ADR-008 线程池拒绝策略：不用 DiscardOldestPolicy

**背景**：讨论稿 v0.2/v0.3 规定「有界队列 + `discard_oldest` + 丢弃计数器」，并强调禁用 `CallerRunsPolicy`。

**问题**：禁用 `CallerRunsPolicy` 完全正确（会把通知压力反压回业务线程，违背承诺 P2）。但 **`DiscardOldestPolicy` 对告警场景是错的**：

`ThreadPoolExecutor` 默认用 `LinkedBlockingQueue`（FIFO），「最旧的任务」= **最早入队的任务**。而在通知风暴场景下，最早入队的往往是**第一个发现的、最重要的 CRITICAL 告警**；后面涌入的是大量重复的低价值事件。`discard_oldest` 会精确地丢掉最该发的那一条，保住最不该发的重复项。

**决策**：改用 **`PriorityBlockingQueue` + 自定义拒绝策略 `NotifyxRejectionHandler`**：

1. 队列元素按 `Level` 降序 + 入队时间升序排序（`CRITICAL > ERROR > WARN > INFO`，同级 FIFO）。
2. 队列满时，拒绝策略执行「**丢弃优先级最低的那一个**」（若新任务优先级高于队尾，则替换队尾），而不是丢最旧。
3. 被丢弃的任务**必须**：计入 `notifyx.send.total{status=discard}` 指标 + 写入本地丢弃日志（含 scene 与 dedupeKey）+ 可选落盘到 `FailureStore` 供事后补偿。
4. `PriorityBlockingQueue` 无界，因此需配合**软上限检查**：入队前判断 `size() >= queue-capacity` 则走拒绝逻辑，避免 OOM。

**理由**：告警系统的核心矛盾是「资源不足时先保住什么」。答案是保住高优先级，而不是保住最早的。这个策略调整只影响 `notifyx-spring` 的一个类，但对生产表现影响巨大。

**代价**：`PriorityBlockingQueue` 的入队是 O(log n)，比 `LinkedBlockingQueue` 的 O(1) 慢。在 4096 容量下约 12 次比较，相对一次 HTTP 发送（毫秒级）可忽略。

---

### ADR-009 Spring Boot 3.5.16 EOL 的应对

**背景**：Boot 3.5.16 是 3.5.x 最后一个 OSS 版本，OSS 支持已于 2026-06-30 结束（商业支持至 2032-06）。Boot 4.0 于 2025-11 GA，基于 Spring Framework 7，带来了 Starter 模块化重构、Jackson 3、JSpecify 空安全、虚拟线程默认化等破坏性变化。⚠️ 单源待校验：Boot 4 具体变更清单来自社区技术文章，实现前应以官方 Upgrading 文档逐条核对。

**决策**（四条同时执行）：

1. **开发基线锁定 3.5.16**（满足需求），但 `spring-boot.version` **必须抽成 BOM 中的 property**，任何模块不得硬编码。
2. **CI 增加 `boot4` profile**：在 Boot 4.x 下只做**编译期检查 + autoconfigure 装配验证**（`ApplicationContextRunner`），不要求全量测试通过。目的是让「未来升级不是重写」，而是能提前看到断裂点。
3. **命名留后路**：见 ADR-001，主坐标不含版本号。
4. **隔离易变 API**：
   - JSON 序列化只通过内部 `NotifyxJson` 门面（默认 Jackson，可切换），**不在业务代码里直接 import Jackson 类**——这是为 Jackson 3 迁移做的隔离。
   - 自动装配只用 `AutoConfiguration.imports`（Boot 3 已废弃 `spring.factories`），不依赖任何在 Boot 4 中被重构的 starter 内部类。
5. **README 首屏写明**：「NotifyX 当前基于 Boot 3.5.16 构建，已预留 Boot 4 兼容路径；信创/存量项目请优先使用 3.5.16。」

**理由**：诚实面对 EOL，而不是假装它不存在。开源项目最忌讳的是「用户升到 Boot 4 发现你的 starter 直接炸了，而作者半年没提交」。

---

### ADR-010 SPI 准入守则：有第二个实现才抽象

**背景**：讨论稿已提出「一个 SPI 必须有第二个实现才保留」。本节将其升级为可执行的守则。

**决策**：新增任何 SPI 接口必须同时满足：

1. **存在至少两个真实实现**（不含 no-op），或已有一个真实实现 + 一个明确的社区需求 issue。
2. **变更频率不同**——被抽象的部分与稳定的部分演进速度不同（例如渠道协议频繁变，路由链稳定）。
3. **有 no-op 默认实现**，保证零依赖可跑（承诺 P1）。
4. 标注 `@Since("x.y")`；未稳定的标 `@Incubating`，破坏性变更走 major 版本。

**不满足时的替代方案**：用**策略枚举 + switch** 或**配置项分支**。等第二个实现真的出现时再重构为 SPI——那时你对差异点的理解会准确得多。

**理由**：过早抽象的代价是**接口错误**，而接口一旦发布就很难改（语义化版本约束）。策略枚举改起来是内部重构，SPI 改起来是 breaking change。

---

### ADR-011 不使用 Lombok

**决策**：全项目禁用 Lombok。POJO 用 `record`（不可变）或手写 getter/setter（可变配置类）。

**理由**：
1. 基础库用 Lombok 会给**使用者**带来负担：他们必须装 IDE 插件，否则点进去看到一堆「找不到方法」的红线。
2. Lombok 与 JDK 新版本、与 annotation processor 生态（MapStruct、AutoService）存在长期兼容摩擦。
3. JDK 17 的 `record` 已覆盖 Lombok `@Value` 的大部分场景，且是语言级特性。
4. javadoc.jar 生成时 Lombok 会产生空文档问题。

**代价**：配置类（`NotifyxProperties` 及其嵌套类）需要手写大量 getter/setter，约 800 行样板代码。**接受**——用 IDE 生成一次即可，且这些代码永不出错。

---

## 第 4 章 总体架构

### 4.1 三件套拓扑

```
       ┌──────────────────────────┐          ┌──────────────────────────┐
       │      notifyx-admin       │          │    notifyx-consumer      │
       │  控制台 + 配置中心        │          │  MQ 模式下唯一发送方      │
       │  (独立 jar / Docker)      │          │  (可选部署)               │
       └────────────┬─────────────┘          └────────────▲─────────────┘
                    │                                     │
          ① pull 配置快照                        ② 消费 MQ 消息
          (HMAC 签名鉴权)                        (幂等键去重)
                    │                                     │
                    ▼                                     │
       ┌────────────────────────────────────────────────────────────────┐
       │        业务系统：notifyx-spring-boot-starter（SDK，用户引入）    │
       │                                                                │
       │  【埋点层】                                                     │
       │   L0 Logback Appender │ L1 @RestControllerAdvice               │
       │   L2 @AlarmProtect/@NotifyOn + AOP │ L3 Spring Event │ L4 门面  │
       │                              ↓                                 │
       │  【责任链管线】（EventFilter chain，顺序固定）                   │
       │   路由 → 免打扰 → 去重 → 限流 → 聚合 → 投递                      │
       │                              ↓                                 │
       │  【执行层】                                                     │
       │   有界优先级队列 → 渠道插件 → 重试(退避+jitter) → 熔断            │
       │                              ↓                                 │
       │  【旁路】审计 → Micrometer 指标 → 失败落盘补偿                    │
       └────────────────────────────────────────────────────────────────┘
```

### 4.2 三角色职责与必选性

| 角色 | 职责 | 必选 | 缺失时的行为 |
| --- | --- | --- | --- |
| **SDK**（starter） | 埋点、组装事件、责任链处理、调用渠道、记审计 | ✅ 必选 | — |
| **Admin** | 场景/渠道/模板配置、发送记录、大盘、规则、权限、接入指引 | ⭕ 可选 | 纯 YAML 驱动，功能完整，只是配置改动需要发版 |
| **Consumer** | MQ 模式下统一消费发送 | ⭕ 可选 | `mode=local` 时完全不需要 |

**关键设计承诺**：没有 Admin、没有 Redis、没有 MQ、没有数据库，starter 也能正常启动并发送邮件/webhook。

### 4.3 能力梯度：三层自动降级

这是应对「不确定用户环境」的核心机制。**自动探测，逐级退化，永不启动失败**。

| 梯度 | 环境能力 | 探测方式 | 行为 |
| --- | --- | --- | --- |
| **T0** | 只有 JVM | 默认 | 内存去重（Caffeine）+ 内存限流（滑动窗口）+ 本地文件落盘（`~/.notifyx/pending`）+ 单实例发送。多实例会重复发，靠静默期缓解 |
| **T1** | 有 Redis | classpath 存在 `spring-boot-starter-data-redis` 且 `RedisConnectionFactory` Bean 可用 | 分布式去重（Redis Lua 滑动窗）+ 分布式限流（Lua 令牌桶）+ 跨实例聚合 |
| **T2** | 有 MQ | `notifyx.mode=mq` 且对应 bus 模块在 classpath | producer-only 模式：事件序列化进 topic，由独立 consumer 统一发送 |
| **T3** | 有 DB | 对应 store 模块在 classpath + DataSource 可用 | 审计落库（MyBatis-Plus）+ 配置持久化 + 失败补偿入库 |

**探测原则**：
- 一律用 `@ConditionalOnClass` + `@ConditionalOnBean` + `ObjectProvider` 的**惰性探测**，不用 `Class.forName` 硬试。
- 探测失败**不报错、不 warn 刷屏**，只在启动诊断表格里标一行「降级：Redis 不可用，去重退化为内存」。
- **T0 是所有其他梯度的基础**，任何梯度失效都必须能退回 T0，而不是启动失败。

### 4.4 一次告警的完整时序（L2 注解路径）

```
业务线程                          NotifyX 内部线程池                渠道
   │                                     │                          │
   │ 1. 方法抛异常                        │                          │
   ├─→ AOP AroundAdvice 捕获            │                          │
   │ 2. 组装 NotifyEvent                 │                          │
   │    (scene/level/params/bizId)       │                          │
   │ 3. 从 MDC 取 traceId                │                          │
   │ 4. 异常原样 rethrow  ← ★关键        │                          │
   ├─→ 5. 入有界优先级队列 (O(log n))    │                          │
   │ 6. 业务线程立即返回                  │                          │
   │                                     │ 7. 出队                   │
   │                                     ├─→ 8. 渲染模板             │
   │                                     ├─→ 9. 场景路由             │
   │                                     ├─→ 10. 免打扰判定          │
   │                                     ├─→ 11. 去重判定            │
   │                                     ├─→ 12. 限流判定            │
   │                                     ├─→ 13. 聚合窗口            │
   │                                     ├─→ 14. 渠道发送 ──────────→│ 15. HTTP POST
   │                                     │                          │ 16. 响应
   │                                     │←─────────────────────────┤
   │                                     ├─→ 17. 失败? 退避+jitter重试│
   │                                     ├─→ 18. 连续失败≥5? 熔断30s │
   │                                     ├─→ 19. 仍失败 → 落盘        │
   │                                     └─→ 20. 审计 + 指标          │
```

**第 4 步是 AOP 铁律**：`@AlarmProtect` 只观察异常，**绝不吞掉异常**。业务原本要抛的异常必须原样抛给上层。切面自身任何异常都不得外抛（见 8.3）。

### 4.5 数据流与控制流分离

| 流 | 载体 | 方向 | 失败影响 |
| --- | --- | --- | --- |
| **数据流**（事件） | `NotifyEvent` → 队列 → 渠道 | SDK → 外部渠道 | 丢通知，不影响业务 |
| **控制流**（配置） | 配置快照 pull | Admin → SDK | 用旧配置继续跑，不影响发送 |
| **观测流**（指标/审计） | Micrometer / JDBC | SDK → 监控系统 / DB | 丢观测数据，不影响发送 |

三条流**互不阻塞、互不传染**。控制流失败不能让数据流停摆（Admin 挂了照样发通知），观测流失败也不能让数据流停摆（DB 挂了照样发通知，审计降级为日志）。这是承诺 P2 的架构落地。

---

## 第 5 章 模块划分与依赖纪律

### 5.1 模块树（最终版）

```
notifyx/
├── notifyx-bom                          # 统一版本；spring-boot.version 抽 property
│
├── notifyx-core                         # ★ 零依赖：模型、SPI、指纹、限流算法、脱敏
│                                        #   仅允许 SLF4J API
├── notifyx-spring                       # AOP、Spring Event 桥接、异步管线、MDC、优先级队列
├── notifyx-spring-boot3-autoconfigure   # 自动装配 + 配置元数据 + 启动诊断
├── notifyx-spring-boot3-starter         # 空 pom 聚合（+ alias 发布策略，见 ADR-001）
│
├── notifyx-channel-email                # P0：jakarta.mail，Transport 池化
├── notifyx-channel-webhook              # P0：通用 HTTP，HMAC-SHA256 签名 + 防重放
├── notifyx-channel-dingtalk             # P1：机器人 webhook + secret 加签
├── notifyx-channel-wecom                # P1：群机器人 / 应用消息双路
├── notifyx-channel-feishu               # P1：机器人 webhook + 富文本卡片
├── notifyx-channel-sms4j                # P2：适配 sms4j（optional）+ 号级限流
│
├── notifyx-bus-kafka                    # MQ 解耦（optional）
├── notifyx-bus-rabbitmq                 # MQ 解耦（optional）
├── notifyx-bus-rocketmq                 # MQ 解耦（optional）
│
├── notifyx-store-mybatisplus            # 审计 + 配置落库（MP 3.5.x）
│   └── sql/{mysql,dm,kingbase,opengauss}/V1__init.sql
├── notifyx-store-redis                  # 分布式去重/限流（optional）
│
├── notifyx-admin                        # 控制台（Spring Boot Web + Vue3 dist）
├── notifyx-consumer                     # 独立消费服务（MQ 模式）
│
├── notifyx-test                         # WireMock + Testcontainers 测试支撑
└── notifyx-sample                       # 可跑通示例工程
```

### 5.2 依赖方向（严格单向）

```
                     notifyx-bom (dependencyManagement only)
                              │
        ┌─────────────────────┴─────────────────────┐
        ▼                                           ▼
   notifyx-core  ◄──────────────  notifyx-spring ◄── notifyx-spring-boot3-autoconfigure
        ▲                              ▲                        ▲
        │                              │                        │
   ┌────┴────────────┐        ┌────────┴────────┐               │
   │ channel-* 模块   │        │ bus-* 模块       │               │
   │ store-* 模块     │        └─────────────────┘               │
   └─────────────────┘                                          │
                                                                 │
                                          notifyx-spring-boot3-starter (空 pom 聚合)
                                                                 ▲
                                                    ┌────────────┴───────────┐
                                              用户业务应用              notifyx-sample
                                              
   notifyx-admin  ──→ store-mybatisplus ──→ core   （admin 不依赖 spring/autoconfigure）
   notifyx-consumer ─→ core + bus-* + channel-*    （consumer 不依赖 autoconfigure 的 SDK 装配）
```

### 5.3 三条铁律（ArchUnit 断言固化，CI 不过即失败）

**铁律 1 — starter 的 compile classpath 必须极干净**

除 `core` / `spring` / `autoconfigure` 外，不得出现任何渠道 SDK、任何 MQ 客户端、任何 DB 驱动。全部 `<optional>true</optional>` 或 `runtime`。

验收方式：`mvn dependency:tree -pl notifyx-spring-boot3-starter` 输出行数 ≤ 25，且断言脚本扫描不出现 `aliyun`/`kafka`/`rocketmq`/`rabbitmq`/`mysql`/`dameng`/`kingbase`/`sms4j`/`mail` 关键字。

**铁律 2 — core 不得依赖 Spring**

```java
// ArchUnit 测试（notifyx-core/src/test）
@AnalyzeClasses(packages = "io.github.pkyit.notifyx.core")
class CoreDependencyArchTest {
    @ArchTest
    static final ArchRule core_must_not_depend_on_spring =
        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                                  "com.baomidou..", "org.apache.ibatis..");

    @ArchTest
    static final ArchRule core_may_only_use_slf4j =
        classes().should().onlyDependOnClassesThat()
            .resideOutsideOfPackages("org.slf4j..")   // 允许
            .orShould().beAnnotatedWith(Since.class);  // 说明性注解豁免
}
```

**铁律 3 — 渠道模块之间互相不可见**

`notifyx-channel-dingtalk` 不得依赖 `notifyx-channel-wecom`。避免传递依赖污染，也避免用户引一个渠道却拖进五个。

### 5.4 模块职责边界表

| 模块 | 允许的依赖 | 明确禁止 | 代码量预估 |
| --- | --- | --- | --- |
| `core` | JDK 17、SLF4J API | Spring、任何三方 SDK、任何 IO 框架 | ~3000 行 |
| `spring` | core、spring-context、spring-aop、spring-boot | 渠道 SDK、DB、MQ | ~1500 行 |
| `autoconfigure` | spring、spring-boot-autoconfigure、actuator(optional) | 直接引渠道 SDK（用 `@ConditionalOnClass` 字符串引用） | ~1200 行 |
| `channel-*` | core、该渠道 SDK(optional)、JDK HttpClient | 其他 channel 模块、spring-boot-starter-web | 每个 <200 行 |
| `bus-*` | core、对应 MQ 客户端(optional) | 渠道模块 | 每个 <300 行 |
| `store-mybatisplus` | core、mybatis-plus、JDBC | Spring MVC | ~800 行 |
| `admin` | spring-boot-starter-web、store、Vue3 dist | **不得扫描业务 classpath** | ~4000 行 + 前端 |

### 5.5 为什么 Admin 与 Consumer 不复用 autoconfigure

`notifyx-admin` 与 `notifyx-consumer` 是**独立部署的服务端应用**，不是被业务系统引入的 SDK。它们直接依赖 `core` + 具体 `channel-*` / `bus-*` / `store-*`，自己写 `@Configuration`。

如果让它们复用 `autoconfigure`，会出现「服务端应用被 SDK 的条件装配逻辑误装配」的问题——例如 admin 里不需要 `@AlarmProtect` 的 AOP 切面，但 autoconfigure 会装上。保持分离，各自装配，边界清晰。

---

## 第 6 章 核心领域模型

### 6.1 NotifyEvent —— 唯一入参对象

```java
package io.github.pkyit.notifyx.core.model;

/**
 * 业务语义事件。不可变、可序列化（MQ 模式下跨进程传输）。
 *
 * @since 1.0
 */
public final class NotifyEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 场景码，全局唯一锚点。必填，正则 ^[A-Z][A-Z0-9_]{0,63}$ */
    private final String scene;

    /** 级别。默认 WARN。CRITICAL 享有旁路特权，见 §9.2 */
    private final Level level;

    /** 可选标题。未配置模板时作为标题使用 */
    private final String title;

    /** 模板变量。构造时深拷贝 + 不可变包装 */
    private final Map<String, Object> params;

    /** 接收人（手机号/邮箱/openid/机器人标识）。为空则取场景配置的默认接收人 */
    private final Set<String> receivers;

    /** 业务主键（如 orderId），用于去重与追溯 */
    private final String bizId;

    /** 自定义去重指纹。为空则按 §9.1 规则自动生成 */
    private final String dedupeKey;

    /** 本次事件的静默期，覆盖全局配置。null 表示不覆盖 */
    private final Duration silentWindow;

    /** 是否异步。默认 true；同步仅用于「必须确认送达」的极少数场景 */
    private final boolean async;

    /** 扩展标签（env/tenant/region/instanceGroup），用于路由与聚合 */
    private final Map<String, String> tags;

    /** 事件产生时间戳（epoch millis），聚合窗口与审计依赖它 */
    private final long occurredAt;

    /** 事件唯一 ID，evt_{ULID}。审计追溯主键 */
    private final String eventId;

    /** ⚠ 不参与序列化，见 §6.4 */
    private final transient Runnable onSuccess;
    /** ⚠ 不参与序列化，见 §6.4 */
    private final transient Consumer<Throwable> onError;

    // 私有构造 + Builder，省略 getter
}
```

**构造期约束（防御式编程）**：

| 字段 | 约束 | 违反时的行为 |
| --- | --- | --- |
| `scene` | 必填，`^[A-Z][A-Z0-9_]{0,63}$` | 抛 `IllegalArgumentException` —— **这是调用方错误，绝不能吞** |
| `params` | 防御性拷贝 + `Collections.unmodifiableMap` | value 仅允许 `String/Number/Boolean/Date/Temporal/Enum`；其余类型 `toString()` 并记一条 warn（防止序列化爆炸与内存泄漏） |
| `params` 深度 | 嵌套 Map/List 最多 3 层，超出截断 | 记 warn，截断处理 |
| `params` 总大小 | 序列化后 ≤ 64KB | 超出则截断并记 warn（防止一条事件撑爆 MQ 报文） |
| `level` | 默认 `WARN` | — |
| `receivers` | 元素去空白、去重 | 非法格式不在此校验（交给渠道，因为格式随渠道而异） |
| `onSuccess/onError` | `transient` | MQ 模式下静默丢弃并记 warn |

**为什么 `scene` 非法要抛异常而不是降级**：场景码是路由的唯一依据。写错场景码意味着这条通知会走默认路由发到错误的地方，或者根本发不出去却无人知晓——**静默失败比抛异常危险得多**。这属于编码期错误，应该在开发阶段就炸出来。

### 6.2 Level 枚举与旁路特权

```java
public enum Level {
    INFO,       // 业务通知，受全部漏斗约束
    WARN,       // 默认级别
    ERROR,      // 异常告警
    CRITICAL;   // 直通免打扰、直通限流、去重窗口减半，见 §9.2

    public boolean atLeast(Level other) { return this.ordinal() >= other.ordinal(); }
}
```

### 6.3 NotifyContext —— 渠道看到的唯一世界

```java
package io.github.pkyit.notifyx.core.spi;

/**
 * 渠道执行上下文。★ 渲染在管线内完成，渠道不自渲染（ADR-004）。
 *
 * @since 1.0
 */
public final class NotifyContext {

    private final NotifyEvent event;

    /** ★ 已渲染内容。渠道只需按自身报文格式打包 */
    private final String renderedContent;
    /** ★ 已渲染标题 */
    private final String renderedTitle;
    /** ★ 目标渲染格式（PLAIN/MARKDOWN/HTML），由渠道声明，管线据此渲染 */
    private final RenderFormat renderFormat;

    /** 该渠道的配置快照（不可变，避免运行中被改） */
    private final ChannelConfig channelConfig;

    /** 单个接收人。多接收人时管线会 fan-out 成多个 Context */
    private final String receiver;

    private final String traceId;
    private final String spanId;
    private final String instanceId;

    /** 第几次尝试，从 1 开始。渠道可据此调整行为（如重试时加「重发」前缀） */
    private final int attempt;

    /** 幂等键，透传给支持幂等的渠道 */
    private final String idempotencyKey;

    /** 只读属性访问，供渠道做条件判断，不暴露可变状态 */
    public <T> T attribute(ContextKey<T> key) { ... }
}
```

**`receiver` 单值而非集合的设计说明**：讨论稿 v0.3 中 `NotifyEvent.receivers` 是 `Set<String>`，但 `NotifyContext` 未明确粒度。本文档确定为**管线层 fan-out**：一个 event 有 N 个 receiver × M 个 channel，则产生 N×M 个 `NotifyContext`，每个独立发送、独立重试、独立审计。

理由：
1. **审计粒度正确** —— 「发给了谁、成功没有」必须按单个接收人记录，否则 3 个手机号里 1 个失败会被记成全失败或全成功。
2. **重试粒度正确** —— 单个接收人失败只重试它，不影响其他。
3. **限流粒度正确** —— 号级限流（短信场景运营商频控）必须按单号计算。

代价：fan-out 会增加队列元素数量。缓解方式是 `CRITICAL` 之外的事件在**聚合窗口**内合并（见 9.5），且渠道批量接口（如邮件多收件人）可通过 `NotifyChannel#supportsBatch()` 声明，管线据此**不 fan-out** 而走批量。

### 6.4 序列化契约（MQ 模式的硬约束）

`NotifyEvent` 要跨进程传输，必须解决三个问题：

**问题 1：回调钩子不可序列化**

`onSuccess` / `onError` 是 `Runnable` / `Consumer`，通常捕获了业务对象。方案：

- 声明为 `transient`，JSON 序列化时**直接跳过**。
- `mode=mq` 时，SDK 在入队前检测：若事件带了回调，**记一条 warn**（`"onSuccess/onError 回调在 MQ 模式下不生效，eventId={}"`），然后正常发送。
- 文档明确：需要链路闭环回调的场景，请用 `mode=local`，或在 consumer 侧通过 webhook 回调业务系统。

**问题 2：`params` 中的复杂对象**

约束已在 6.1 定义（只允许基本类型 + `toString()` 降级）。序列化用统一的 `NotifyxJson` 门面（ADR-009 中为 Jackson 3 迁移预留的隔离层），**禁止渠道模块自行 new ObjectMapper**。

**问题 3：版本兼容**

MQ 报文必须带 `schemaVersion` 字段。consumer 收到高版本报文时：

| 情况 | 行为 |
| --- | --- |
| consumer 版本 ≥ producer | 正常处理 |
| consumer 版本 < producer，且新增字段 | **忽略未知字段**（`FAIL_ON_UNKNOWN_PROPERTIES=false`），正常处理 |
| consumer 版本 < producer，且必填字段语义变更 | 拒绝 + 进死信队列 + 告警（用 NotifyX 自己告警自己，dogfooding） |

规则：**只做加法，不做减法**。字段一旦发布永不删除，废弃字段标 `@Deprecated` 并保留至少两个 minor 版本。

### 6.5 NotifyEnvelope —— MQ 报文契约

```json
{
  "schemaVersion": 1,
  "eventId": "evt_01J9ZK8M3N4P5Q6R7S8T9V0W1X",
  "idempotencyKey": "sha256:8f3a...",
  "dedupeKey": "sha256:c71b...",
  "scene": "ORDER_CREATE_FAIL",
  "level": "ERROR",
  "title": null,
  "params": { "orderId": "SO20261021001", "reason": "库存不足" },
  "receivers": ["admin@example.com", "13888888888"],
  "bizId": "SO20261021001",
  "tags": { "env": "prod", "tenant": "order-service", "region": "cn-south" },
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "instanceId": "order-service-7d9f8b-x2k4p",
  "silentWindowSeconds": 300,
  "attempts": 0,
  "occurredAt": 1729500000000,
  "createdAt": 1729500000123
}
```

**字段说明**：

| 字段 | 必填 | 用途 |
| --- | --- | --- |
| `schemaVersion` | ✅ | 报文版本，兼容性判定依据（6.4 问题 3） |
| `eventId` | ✅ | ULID，全局唯一，审计主键。**不用 UUID** 是因为 ULID 时间有序，DB 索引友好 |
| `idempotencyKey` | ✅ | consumer 侧 at-least-once 去重（Redis `SET NX` 或 DB 唯一索引） |
| `dedupeKey` | ✅ | 业务语义去重，同时作 Kafka 分区 key 保序 |
| `attempts` | ✅ | 已尝试次数，consumer 据此决定退避 |
| 其余 | 视字段 | 从 `NotifyEvent` + 运行时上下文组装 |

### 6.6 SendResult 与异常体系

```java
public record SendResult(
    Status status,
    String messageId,      // 渠道返回的消息 ID（邮件 Message-ID / 钉钉 errcode=0）
    String rawResponse,    // 原始响应，截断 1KB
    Duration cost,
    String failureReason   // 失败时的分类原因，见下表
) {
    public enum Status {
        SUCCESS,             // 发送成功
        FAIL_RETRYABLE,      // 可重试失败（超时、5xx、连接拒绝）
        FAIL_NON_RETRYABLE,  // 不可重试失败（认证失败、4xx 参数错、接收人不存在）
        SUPPRESSED,          // 被免打扰/静默开关拦截
        DEDUPED,             // 被去重拦截
        RATE_LIMITED,        // 被限流拦截
        CIRCUIT_OPEN         // 渠道熔断中，未尝试发送（本文档新增状态）
    }
}
```

**异常体系**：

```
RuntimeException
  └── NotifyException                    # 非受检，统一出口
        ├── ChannelException             # 渠道侧失败，保留 cause
        │     ├── ChannelAuthException        # → FAIL_NON_RETRYABLE
        │     ├── ChannelTimeoutException     # → FAIL_RETRYABLE
        │     ├── ChannelRateLimitException   # → FAIL_RETRYABLE（长退避）
        │     └── ChannelProtocolException    # → FAIL_NON_RETRYABLE（对方 API 改版）
        ├── ConfigException              # 配置错误（密钥缺失、格式非法）
        ├── TemplateRenderException      # 模板渲染失败
        └── NotifyRejectedException      # 队列满被拒（携带被丢弃事件的摘要）
```

**为什么异常必须细分类**：`ChannelException` 的子类直接决定重试策略是否正确。把「AccessKey 错了」当可重试，会连续重试 3 次全部失败，浪费 6 秒并把错误率指标打高，掩盖真正的问题；把「网络抖动」当不可重试，会丢掉本该发出的告警。**这是渠道实现质量的第一个考核点**，Code Review 必查。

### 6.7 门面 NotifyKit —— 克制在 6 个方法

```java
package io.github.pkyit.notifyx.core.api;

/**
 * 用户唯一需要认识的接口。方法数刻意控制在 ≤6，防止 API 膨胀。
 *
 * @since 1.0
 */
public interface NotifyKit {

    /** 全量控制。需要精细定制时用 */
    void send(NotifyEvent event);

    /** ★ 最常用：场景码 + 参数，接收人走配置 */
    void send(String scene, Map<String, ?> params);

    /** ★ 需求原始形态：场景码 + 指定接收人 + 参数 */
    void send(String scene, String receiver, Map<String, ?> params);

    /** 异常直抛：自动抓 stack、自动定级 ERROR、scene 取 UNCAUGHT_EXCEPTION */
    void alarm(Throwable t);

    /** ★ 最常用：指定场景的异常告警，context 会被摊平进 params */
    void alarm(String scene, Throwable t, Object... context);

    /** 显式异步（等价于 event.async=true 的 send，语义更清晰） */
    void sendAsync(NotifyEvent event);
}
```

**刻意不提供的 API**（及理由）：

| 不提供 | 理由 |
| --- | --- |
| `sendMessage(phone, text)` | 把通道和文案写死在调用点，**正是本项目要消灭的东西** |
| `sendEmail(...) / sendSms(...)` | 通道特定方法会诱导用户绕过场景路由，破坏配置驱动 |
| `sendSync(...)` | 同步发送违反承诺 P2。极少数「必须确认送达」场景用 `NotifyEvent.builder().async(false)` 显式声明 |
| Builder 之外的 20 个重载 | 重载爆炸。复杂场景一律用 `NotifyEvent.builder()` |

**静态访问的取舍**：讨论稿未明确。本文档决定**默认只提供 Spring Bean 注入**，另提供 `NotifyKitHolder.get()` 静态门面（`@ConditionalOnProperty("notifyx.static-holder.enabled")`，**默认关闭**）。

理由：静态 Holder 是反模式（隐藏依赖、难测试），但存量老系统的静态工具类里确实需要它。默认关闭 + 显式开启，把选择权交给用户，同时不污染默认体验。

---

## 第 7 章 SPI 体系

### 7.1 SPI 总览

| SPI | no-op / 默认实现 | 真实实现 | 所属模块 | 准入依据（ADR-010） |
| --- | --- | --- | --- | --- |
| `NotifyChannel` | 无（必须有实现才能发） | email/webhook/dingtalk/wecom/feishu/sms4j/log | `channel-*` | ✅ 6 个实现 |
| `TemplateEngine` | `${key}` 简单替换（零依赖） | FreeMarker / Thymeleaf | `core` + 可选 | ✅ 3 个实现 |
| `DedupeStore` | Caffeine 内存 | Redis Lua / JDBC | `core` + `store-*` | ✅ 3 个实现 |
| `RateLimiter` | 内存滑动窗口 | Redis Lua 令牌桶 | `core` + `store-redis` | ✅ 2 个实现 |
| `FailureStore` | 本地文件 `~/.notifyx/pending` | JDBC / MQ | `core` + `store-*` | ✅ 3 个实现 |
| `AuditRecorder` | 空实现（no-op，只记日志） | MyBatis-Plus + Flyway | `core` + `store-*` | ✅ 2 个实现 |
| `ReceiverResolver` | 配置静态列表 | 查库 / LDAP / 组织服务 | `core`（用户自实现） | ⚠️ 仅 1 个内置实现，**标 `@Incubating`** |
| `Masker` | 手机号/邮箱/身份证掩码 | 自定义正则 | `core` | ✅ 2 个实现 |
| `NotifyBus` | `local`（直发） | kafka/rabbitmq/rocketmq | `bus-*` | ✅ 4 个实现 |
| `EventFilter` | 内置 6 个漏斗节点 | 用户自定义 | `core` | ✅ 7+ 个实现 |
| `CircuitBreaker` | 简单计数熔断 | Resilience4j（optional） | `core` | ⚠️ 仅 1 个内置实现，**标 `@Incubating`** |

**守则执行结果**：`ReceiverResolver` 与 `CircuitBreaker` 目前只有一个内置实现，按 ADR-010 应标 `@Incubating`，在 1.0 前不承诺 API 稳定性。**诚实标注优于假装完备**。

### 7.2 NotifyChannel —— 插件化的根

```java
package io.github.pkyit.notifyx.core.spi;

/**
 * 通知渠道 SPI。新增渠道 = 实现本接口 + 一个配置类。
 *
 * @since 1.0
 */
public interface NotifyChannel extends Ordered {

    /** 渠道标识，全局唯一。如 "email" / "dingtalk" / "sms4j" */
    String name();

    /** 运行时开关。可做灰度、租户、环境判断。返回 false 时静默跳过 */
    default boolean isEnabled(NotifyContext ctx) { return true; }

    /** ★ 真正发送。实现应保持极薄（目标 <80 行）：打包报文 → 签名 → POST → 解析响应 */
    SendResult send(NotifyContext ctx) throws ChannelException;

    /** 声明偏好的渲染格式，管线据此渲染（ADR-004） */
    default RenderFormat preferredFormat() { return RenderFormat.PLAIN; }

    /** 最大重试次数。渠道可按自身特性覆盖（短信建议 1，避免重复计费） */
    default int maxRetries() { return 3; }

    /** 重试退避基数 */
    default Duration retryBackoff() { return Duration.ofSeconds(2); }

    /** 不可重试异常类型白名单 */
    default Set<Class<? extends Throwable>> nonRetryable() { return Set.of(); }

    /** 是否支持批量接收人（邮件多收件人）。true 时管线不 fan-out */
    default boolean supportsBatch() { return false; }

    /** 健康探测。默认不实现（返回 UNKNOWN），避免探活产生真实流量 */
    default HealthStatus health() { return HealthStatus.UNKNOWN; }

    /** 渠道关闭时释放资源（连接池、token 缓存） */
    default void close() { }
}
```

**发现机制：双模式**

| 环境 | 机制 |
| --- | --- |
| Spring | 各渠道模块 `@Bean` 注册，`ObjectProvider<NotifyChannel>` 注入，按 `Ordered` 排序 |
| 非 Spring（Solon/Quarkus/纯 Java） | `META-INF/services/io.github.pkyit.notifyx.core.spi.NotifyChannel` + `ServiceLoader` |

两者共存成本极低（一个 `ChannelRegistry` 类同时接受两种来源），且是「信创/非标容器可用」的保证。`ChannelRegistry` 负责去重（同名渠道以 Spring Bean 优先）与冲突告警。

### 7.3 EventFilter —— 最重要的扩展点

```java
package io.github.pkyit.notifyx.core.spi;

/**
 * 事件过滤器。责任链节点。用户可插入自定义 Filter（如「只发给我自己」的调试 Filter）。
 *
 * @since 1.0
 */
public interface EventFilter extends Ordered {

    /** 过滤器名，用于诊断输出与指标标签 */
    String name();

    /**
     * @return PASS 继续 / REJECT 终止并给出原因 / DEFER 延后到指定时间
     */
    FilterVerdict doFilter(FilterContext ctx);

    /** 该 Filter 是否对当前事件生效（可据此跳过，省开销） */
    default boolean supports(NotifyEvent event) { return true; }
}

public record FilterVerdict(Decision decision, String reason, Instant deferUntil) {
    public enum Decision { PASS, REJECT, DEFER }
    public static FilterVerdict pass() { ... }
    public static FilterVerdict reject(String reason) { ... }
    public static FilterVerdict deferUntil(Instant t) { ... }
}
```

**内置 Filter 与固定顺序**：

| Order | Filter | 职责 | 拒绝时的 Status |
| --- | --- | --- | --- |
| 100 | `GlobalMuteFilter` | **一键全局静默开关**（运维半夜救命的按钮） | `SUPPRESSED` |
| 200 | `SceneRoutingFilter` | 场景 → 通道列表 + 模板 + 接收人解析 | `SUPPRESSED`（场景未启用） |
| 300 | `QuietHoursFilter` | 免打扰时段，CRITICAL 直通 | `SUPPRESSED` / `DEFER` |
| 400 | `DedupeFilter` | 指纹 + 时间窗去重 | `DEDUPED` |
| 500 | `SceneRateLimitFilter` | scene 级令牌桶 | `RATE_LIMITED` |
| 600 | `GlobalRateLimitFilter` | 全局令牌桶，触发时**发汇总摘要** | `RATE_LIMITED` |
| 700 | `AggregateFilter` | 聚合窗口收拢（跨实例） | — |
| 800 | `CircuitBreakerFilter` | 渠道熔断判定 | `CIRCUIT_OPEN` |
| 900 | *（用户自定义 Filter 插入区）* | — | — |

**Order 间隔留 100** 是刻意的：用户插自定义 Filter 时能精确控制位置，不需要改内置值。文档明确「900+ 为用户区，100–800 为框架保留区」。

**为什么 Filter 而不是硬编码 if-else**：漏斗的每一环都是「可能被用户关掉、可能被用户扩展、可能需要单独出指标」的独立关注点。Filter 化后：
- 每环可独立 `enabled` 开关；
- 每环的拦截量自动进 `notifyx.send.total{status=...}`；
- 用户加「调试期只发给自己」的 Filter 只需 20 行代码。

### 7.4 其他 SPI 要点

**TemplateEngine**

```java
public interface TemplateEngine {
    String name();                                    // "simple" / "freemarker"
    String render(String template, Map<String, Object> params, RenderFormat format);
    default boolean supportsConditionals() { return false; }  // simple 引擎不支持 if/for
}
```

内置 `${key}` 引擎**只做占位替换，不支持条件与循环**。这是安全与性能的取舍：简单引擎无注入风险、无解析开销。需要条件逻辑时用户引入 FreeMarker，此时 `NotifyxAutoConfiguration` 通过 `@ConditionalOnClass` 自动切换。

**DedupeStore**

```java
public interface DedupeStore {
    /**
     * 原子地「检查并占位」。
     * @return true=首次出现，放行；false=窗口内已存在，拦截
     */
    boolean tryAcquire(String dedupeKey, Duration window);
    void release(String dedupeKey);   // 发送失败时释放槽位，避免「占了坑却没发出去」
    long size();                      // 诊断用
}
```

**`release()` 是本文档对讨论稿的重要补充**：讨论稿只定义了 `tryAcquire`，但发送失败时若不释放去重槽位，会出现「第一条发送失败被去重挡住，后续同类事件全部静默」——**故障期间告警彻底消失**，这是最危险的失效模式。因此发送最终失败（含落盘）后必须 `release()`，让下一次同类事件能重新触发。

**RateLimiter**

```java
public interface RateLimiter {
    /** @return true=获取令牌成功 */
    boolean tryAcquire(String key, int permits, Duration window);
    /** 当前窗口已用量，诊断与摘要消息用 */
    long currentCount(String key, Duration window);
}
```

**FailureStore**（落盘补偿）

```java
public interface FailureStore {
    void save(FailedRecord record);
    List<FailedRecord> loadPending(int limit);
    void updateStatus(String recordId, RecordStatus status, int attempts);  // 乐观锁
    long pendingCount();
}
```

**Masker**（脱敏）

```java
public interface Masker {
    String maskPhone(String v);      // 138****8888
    String maskEmail(String v);      // a***@example.com
    String maskIdCard(String v);     // 110***********1234
    /** 对 params 做递归脱敏，按 key 名匹配规则 */
    Map<String, Object> maskParams(Map<String, Object> params);
    String maskText(String text);    // 对渲染后内容做正则脱敏（兜底）
}
```

---

## 第 8 章 零侵入接入：五层用法（L0–L4）

侵入度从低到高排列。**L0 与 L2 是两个杀手特性**，也是最该投入打磨的部分——L0 打动存量系统，L2 是新项目的主力姿势。

### 8.1 L0 · 日志即告警（零代码，存量项目首选）

```xml
<!-- logback-spring.xml -->
<appender name="NOTIFYX" class="io.github.pkyit.notifyx.logback.NotifyxAppender">
    <scene>UNCAUGHT_EXCEPTION</scene>
    <level>ERROR</level>
    <marker>ALARM</marker>                            <!-- 只抓带此 marker 的日志 -->
    <extractBizId regex="orderId=(\w+)" group="1"/>   <!-- 从日志文本抽业务主键 -->
    <suppressRegex>Connection reset|Broken pipe</suppressRegex>  <!-- 噪声过滤 -->
    <maxStackTraceLines>30</maxStackTraceLines>       <!-- 堆栈截断，防超大 payload -->
    <queueSize>1024</queueSize>
    <discardingThreshold>0</discardingThreshold>
</appender>
```

业务代码一行不改，靠日志 marker 接入：

```java
private static final Marker ALARM = MarkerFactory.getMarker("ALARM");
log.error(ALARM, "orderId={} 创建失败", id, e);
```

**Appender 的四条实现铁律**：

| # | 铁律 | 违反的后果 |
| --- | --- | --- |
| 1 | **必须异步**（内部 `AsyncAppender` 语义，有界队列） | 阻塞日志线程 = 阻塞业务线程 = 违反承诺 P2 |
| 2 | **队列满时丢弃并计数**，绝不阻塞、绝不 `CallerRuns` | 通知风暴时把整个应用的日志系统拖死 |
| 3 | **Appender 内部任何异常必须吞掉**，最多 `addError()` 到 logback status | Appender 抛异常会导致 logback 停止该 appender 甚至影响其他 appender |
| 4 | **防自递归**：NotifyX 自身的日志（logger name 以 `io.github.pkyit.notifyx` 开头）必须被过滤掉 | 通知失败 → 记日志 → 日志触发通知 → 通知失败 → 无限循环。**这是最隐蔽也最致命的 bug** |

第 4 条值得单独强调：自递归风暴能在几秒内产生数万条事件，压垮队列并可能真的发出大量短信（产生真实费用）。实现上除了 logger name 过滤，还要在 `NotifyxAppender` 里加**线程级重入保护**（`ThreadLocal<Boolean>`）作为第二道防线。

**`suppressRegex` 的价值**：存量系统的 ERROR 日志里充满无害噪声（连接重置、客户端断开、探活失败）。没有噪声过滤，L0 接入的第一天就会被运维要求关掉。**这个配置项决定了 L0 的实际可用性**，必须在 README 里给出一份「常见噪声正则清单」。

### 8.2 L1 · 全局异常处理器（开关控制）

```yaml
notifyx:
  web:
    exception-handler:
      enabled: true          # 默认 false，避免噪声
      scene: UNHANDLED_WEB_EXCEPTION
      level: ERROR
      exclude-exceptions:    # 业务预期内的异常不告警
        - com.example.BizException
        - org.springframework.web.bind.MethodArgumentNotValidException
      include-request-info: true   # 附带 URI/method/参数（脱敏后）
```

`@RestControllerAdvice` + `@ConditionalOnWebApplication(type = SERVLET)` 装配。

**默认关闭的理由**：一个没做任何异常分类的项目，开启后每个 400 参数校验失败都会告警，第一天就会被关掉。默认关 + 文档里给「建议排除清单」，是更负责的做法。

**必须做的事**：处理器捕获后**必须原样重新抛出或交给 Spring 的默认处理链**，不能改变 HTTP 响应。NotifyX 只做旁路观察。

### 8.3 L2 · 注解 + AOP（主力用法）

```java
@NotifyOn(
    scene = "ORDER_CREATE_FAIL",
    level = Level.ERROR,
    receivers = {"admin@example.com"},
    when = "#r.code != 0",          // SpEL，返回值判定
    dedupe = "5m",                  // 同指纹 5 分钟只发一次
    bizId = "#cmd.orderNo",         // SpEL 取业务主键
    context = {"#cmd", "#r"}        // 摊平进 params
)
public Result<OrderVO> create(OrderCmd cmd) { ... }

@AlarmProtect(scene = "PAY_CALLBACK_FAIL", silent = "10m")  // 抛异常即告警
public void onCallback(PayCallback cb) { ... }
```

**注解语义划分**：

| 注解 | 触发条件 | 是否改变业务行为 | 典型场景 |
| --- | --- | --- | --- |
| `@AlarmProtect` | 方法抛异常 | ❌ 异常原样抛出 | 支付回调、定时任务、外部接口调用 |
| `@NotifyOn` | SpEL `when` 为真 **或** 抛异常 | ❌ 返回值原样返回 | 业务码判定（`code != 0`）、成功通知 |

**AOP 的四条铁律**（违反任何一条都是业务线 P0 事故）：

1. **切面内部任何异常不得外抛**。整个 advice 逻辑包在 `try { ... } catch (Throwable t) { log.warn(...); }` 里。
2. **SpEL 求值失败一律降级**为「不求值、放行、记一条 warn」。绝不让 SpEL 语法错误变成业务异常。
3. **`@AlarmProtect` 绝不吞异常**。业务原本要抛的必须原样抛给上层。
4. **切面顺序**：`@Order(Ordered.LOWEST_PRECEDENCE - 100)`，确保在事务切面**内层**——事务回滚后仍能告警，且告警时能看到已回滚的事实。同时避免与 `@Async` 切面冲突（`@Async` 应在更外层）。

**SpEL 性能与安全**：

- **表达式缓存**：`ConcurrentHashMap<String, Expression>` 缓存 `SpelExpressionParser.parseExpression()` 结果，避免每次调用重新解析（解析开销约为求值的 10 倍）。
- **求值上下文**：默认用 `SimpleEvaluationContext`（只读属性、**无方法调用**、无类型引用），防止 SpEL 注入。提供 `notifyx.spel.allow-method-invocation=false`（默认）与白名单机制。
- **变量绑定**：`#cmd`（参数名，需 `-parameters` 编译选项）、`#p0`/`#a0`（位置，兜底）、`#r`（返回值）、`#e`（异常）、`#method`（方法签名）。
  ⚠️ **`-parameters` 是个坑**：若用户项目未开启该编译选项，参数名会退化为 `arg0`，`#cmd` 静默失效。实现上必须检测并记一条明确的 warn（`"@NotifyOn 的 #cmd 无法解析，请确认已开启 -parameters 编译选项；当前可用 #p0/#a0 替代"`），而不是静默返回 null。

**为什么用 `AroundAdvice` 而不是 `AfterThrowingAdvice`**：`@NotifyOn` 需要同时处理**异常路径**和**返回值路径**，且需要测量耗时。`@Around` 一个切面覆盖两种注解，避免两套逻辑。

### 8.4 L3 · Spring Event（彻底解耦）

```java
// 业务代码：只发 Spring 事件，完全不知道 NotifyX 存在
applicationEventPublisher.publishEvent(new BizEvent("ACCOUNT_CREATED", phone, args));
```

框架侧一个 `@EventListener` 统一翻译为 `NotifyEvent`。

**适用团队**：对第三方库零依赖要求严格、或已有事件驱动架构的团队。业务代码连 `notifyKit` 都不用注入。

**代价**：多一层间接，排查时需要知道这个翻译层的存在。因此**翻译时必须把原始事件类名与 `toString()` 放进 `params.__source`**，便于审计追溯。

### 8.5 L4 · 门面直调（需求原始形态）

```java
// 场景一：异常告警
catch (Exception e) {
    notifyKit.alarm("ORDER_CREATE_FAIL", e, order);   // 一行
}

// 场景二：业务通知（对应需求里的「账号弄好了」）
notifyKit.send("ACCOUNT_CREATED", phone, Map.of("user", name));
```

**`alarm(scene, throwable, context...)` 的 context 摊平规则**（这是易用性的关键，讨论稿未定义）：

| context 元素类型 | 处理方式 |
| --- | --- |
| `Map` | 直接 `putAll` 进 params |
| `Throwable` | 取 `class.simpleName` + `message` + 截断堆栈（30 行），key 为 `exception` / `exceptionMessage` / `stack` |
| `String` | 若含 `=`，按 `k=v` 拆分；否则作为 `arg0`/`arg1`... |
| 其他 POJO | **不做全量反射**（性能与隐私风险），只调用 `toString()` 存入 `ctx0`/`ctx1`。用户需要字段级参数时应显式传 Map |

不自动反射 POJO 是刻意的：全量反射会把敏感字段（密码、身份证）意外带进通知内容，且在大对象上有性能风险。**宁可让用户多写一行 Map.of()，也不做隐式的危险魔法。**

### 8.6 五层用法选型表（写进 README）

| 层级 | 侵入度 | 适用场景 | 能力上限 | 建议 |
| --- | --- | --- | --- | --- |
| L0 日志 | 零 | 存量系统、不敢改代码 | 只能拿到日志文本，params 靠正则抽取 | 快速试点首选 |
| L1 全局异常 | 一行配置 | Web 层兜底 | 只覆盖未处理异常 | 默认关，配好排除清单再开 |
| **L2 注解** | 一个注解 | **新项目主力** | 全能力（SpEL 判定、bizId、静默期） | **推荐** |
| L3 Spring Event | 一行发布 | 严格解耦团队 | 全能力，但多一层间接 | 有事件架构时用 |
| L4 门面 | 一行调用 | 精细控制、动态场景 | 全能力 | 与 L2 混用最常见 |

---

## 第 9 章 可靠性设计

> 这是本项目的技术含金量所在，也是最容易在 Code Review 里被放过的部分。

### 9.1 指纹生成（必须稳定可复现）

```
dedupeKey = sha256( scene ‖ "\u0001" ‖ bizId ‖ "\u0001" ‖ md5( sorted(params).join("&") ) )
```

**为什么必须稳定可复现**：跨实例、跨进程、跨重启算出来必须一致，否则分布式去重直接失效——每个实例都认为是「新事件」，100 个实例发 100 条。

**实现细节（每一条都是踩坑总结）**：

| 细节 | 要求 | 违反的后果 |
| --- | --- | --- |
| 分隔符 | 用不可见控制符 `\u0001`，不用 `|` 或 `:` | 用户参数里含分隔符时指纹碰撞（`scene="A|B"` 与 `scene="A", bizId="B"` 同指纹） |
| params 排序 | 按 key **字典序**排序后拼接，`TreeMap` 保证 | `HashMap` 遍历顺序不稳定 → 同样内容算出不同指纹 → 去重完全失效 |
| 嵌套结构 | 递归排序，List 保持原序（顺序有语义） | 嵌套 Map 未排序 → 同上 |
| 数值归一化 | `1` / `1.0` / `Long(1)` 统一按 `Number.longValue()` 或 `doubleValue()` 归一 | `Integer(1)` 与 `Long(1)` 的 `toString()` 相同但类型不同，跨进程序列化后可能变化 |
| null 值 | 统一转为字面量 `"__NULL__"` | `null.toString()` 抛 NPE |
| 时间类型 | 统一转 epoch millis | `Date.toString()` 受时区与 Locale 影响 |
| **异常堆栈** | **不进入指纹计算** | ⚠️ **关键**：堆栈含内存地址、行号可能因编译差异变化，且同一异常在不同实例上堆栈可能不同（lambda 序号、内部类名）。把堆栈放进指纹会导致**同一故障在不同实例算出不同指纹，去重彻底失效** |
| bizId 为空 | 退化为 `scene ‖ md5(renderedContent)` | 粒度变粗（按内容去重），文档需明确告知这一退化 |

**最后一行的退化路径需要特别说明**：`renderedContent` 依赖模板渲染结果，因此指纹计算必须**在渲染之后**。这与漏斗顺序（9.2）中「去重在渲染之后」是一致的。若 bizId 为空且渲染内容含时间戳，去重会失效——**文档必须明确建议：需要去重的场景请务必传 bizId**。

### 9.2 三级漏斗（顺序不可颠倒）

```
① 全局静默 → ② 场景路由 → ③ 免打扰拦截 → ④ 去重 → ⑤ scene限流 → ⑥ 全局限流 → ⑦ 聚合窗口 → ⑧ 熔断判定 → ⑨ 投递
```

**CRITICAL 旁路矩阵**（本文档对讨论稿 v0.3 的重要补充）：

| 漏斗节点 | INFO | WARN | ERROR | **CRITICAL** | 理由 |
| --- | --- | --- | --- | --- | --- |
| ① 全局静默 | 拦截 | 拦截 | 拦截 | **拦截** | 一键止血必须绝对有效，否则运维失去最后手段 |
| ② 场景路由 | 生效 | 生效 | 生效 | 生效 | 场景禁用是明确意图 |
| ③ 免打扰 | defer | defer | defer | **直通** | 半夜的系统崩溃必须叫醒人 |
| ④ 去重 | 全窗口 | 全窗口 | 全窗口 | **窗口减半**（`min(silentWindow, 60s)`） | ⚠️ **不能完全旁路**，否则持续崩溃会刷屏；但也不能全窗口，否则「故障持续恶化」这个信号会丢失 |
| ⑤ scene 限流 | 生效 | 生效 | 生效 | **直通** | 限流拦掉 CRITICAL 等于自欺欺人 |
| ⑥ 全局限流 | 生效 | 生效 | 生效 | **直通**（但计入摘要） | 同上 |
| ⑦ 聚合窗口 | 聚合 | 聚合 | 聚合 | **不聚合，立即发** | CRITICAL 的时效性优先于降噪 |
| ⑧ 熔断判定 | 生效 | 生效 | 生效 | **生效**（切备用渠道而非丢弃） | 渠道真挂了，硬发也是失败 |

**第 ④ 行的设计权衡值得展开**：讨论稿只说「CRITICAL 直通免打扰与限流」，**没有定义去重行为**。两个极端都是错的：
- **完全旁路去重** → 一个每秒崩溃的循环，CRITICAL 告警每秒发一条短信，10 分钟 600 条，运营商判骚扰、账号被封，**真正的告警反而发不出去**。
- **完全走去重** → 故障持续 2 小时，静默期 30 分钟，运维只在开始时收到 1 条，无法感知「问题还在恶化」。

「窗口减半且不超过 60 秒」是折中：既保证持续故障每分钟有一个心跳信号，又不至于刷屏。同时在**升级策略**（9.6）里配合：连续 N 次 CRITICAL 未恢复 → 升级到电话渠道。

### 9.3 各级限流的手段与触发动作

| 层级 | 粒度 | 手段 | 触发后的动作 | 指标 |
| --- | --- | --- | --- | --- |
| 去重 | `scene` + `bizId` | Caffeine/内存 LRU 或 Redis 滑动窗 | 静默丢弃 | `status=dedup` |
| scene 限流 | per scene | 令牌桶（内存 / Redis Lua） | **丢弃优先级最低的**（ADR-008） | `status=rate-limited` |
| 全局限流 | 全实例 | 令牌桶，阈值最高 | **发一条汇总摘要**，绝不完全静默 | `status=rate-limited` + `summary.sent` |
| 号级限流 | per receiver | 仅短信渠道，运营商频控 | **排队延后**，不计失败 | `sms.deferred` |
| 免打扰 | 全局/scene | `quiet-hours` 时间窗 | CRITICAL 直通，其余 defer 到工作时间 | `status=suppressed` |
| 聚合窗口 | scene | 10~30s 收拢 | 合并成「涉及 N 台实例，首次 T1，最近 T2」 | `aggregate.merged` |

**全局限流为什么要发摘要而不是静默丢弃**：`丢弃 = 失忆，摘要 = 知情`。运维看到「过去 1 分钟有 3,200 条 ORDER_CREATE_FAIL 被限流，样本如下」，能立刻判断是配置错误还是真故障；而完全静默会让运维以为「一切正常」。**这是防风暴设计里最容易被忽略但最重要的一条。**

**号级限流的必要性**：运营商侧对同一手机号有硬性频控（通常 1 分钟 1 条、1 小时 5 条、1 天 10 条）。超出后运营商会**直接拦截并可能封禁签名**，影响的是整个应用的所有短信，不只是这一条。因此号级限流必须做，且触发时**排队延后而非丢弃**（短信通常是必须送达的业务通知）。

### 9.4 Redis Lua 实现（原子滑动窗）

**限流（令牌桶语义的滑动窗实现）**：

```lua
-- KEYS[1] = nx:lim:{scene}
-- ARGV[1] = nowMs, ARGV[2] = windowMs, ARGV[3] = limit
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2])
local c = redis.call('ZCARD', KEYS[1])
if c >= tonumber(ARGV[3]) then
    return 0
end
-- ★ 用 nowMs 拼接递增序号，不用 math.random()（见下方修正说明）
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[1] .. ':' .. c)
redis.call('PEXPIRE', KEYS[1], ARGV[2] + 1000)
return 1
```

**⚠️ 对讨论稿 v0.3 的修正**：原稿用 `math.random()` 作为 member 后缀。这有两个问题：
1. **Redis 3.2+ 之前，`math.random()` 在脚本中是不确定的**，会导致主从复制与 AOF 重放不一致（Redis 会在脚本报错 `Write commands not allowed after non-deterministic commands`）。虽然现代 Redis 已用 `redis.replicate_commands()` 缓解，但依赖这个行为是脆弱的。
2. **可能碰撞**：同一毫秒内多次调用，`math.random()` 理论上可能返回相同值，`ZADD` 会覆盖而非新增，导致计数偏小、限流失效。

改用 `nowMs .. ':' .. c`（当前时间戳 + 当前计数）既确定又唯一，且可复制安全。

**去重（SET NX + 过期）**：

```lua
-- KEYS[1] = nx:dedup:{key}, ARGV[1] = windowSeconds
-- 返回 1 = 首次出现放行；0 = 窗口内已存在拦截
return redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) and 1 or 0
```

去重用 `SET NX EX` 即可（不需要精确滑动窗，因为语义就是「窗口内只发一次」），比讨论稿建议的 `ZREM RANGEBYSCORE + ZADD` 更简单高效。**但必须支持 `DEL`（对应 `DedupeStore.release()`）**，用于发送失败时释放槽位（见 7.4）。

**降级策略**：

```java
// 伪代码：Redis 不可用时自动降级，绝不抛异常中断发送
public boolean tryAcquire(String key, Duration window) {
    try {
        return redisDedupeStore.tryAcquire(key, window);
    } catch (Exception e) {                    // 含 RedisConnectionFailureException、超时
        if (circuitBreaker.recordFailure()) {   // 连续 N 次失败则熔断 Redis 调用 30s
            log.warn("Redis 去重不可用，降级为内存去重：{}", e.getMessage());
        }
        return memoryDedupeStore.tryAcquire(key, window);   // ★ 降级而非失败
    }
}
```

**关键**：Redis 挂了不能导致通知发不出去。降级后**多实例会各自去重**（可能重复发），但这是可接受的——重复发比不发好。这个降级行为必须写进文档，让用户知道「Redis 故障期间可能收到重复通知」。

**不强绑 Jedis/Lettuce**：通过 `ObjectProvider<StringRedisTemplate>` 获取，用户引了 `spring-boot-starter-data-redis` 才生效，否则走内存实现。

### 9.5 跨实例聚合

**问题**：同一 `scene` 在 100 个实例上同时报错 → 100 条相同告警。

**方案**：聚合窗口 10~30s（可配），窗口内同 `scene` + 同 `dedupeKey` 的事件收拢为一条摘要：

```
【ORDER_CREATE_FAIL】聚合告警
涉及实例：100 台（order-service-7d9f8b-x2k4p 等）
时间跨度：2026-10-21 21:15:03 ~ 21:15:28（25s）
事件总数：3,247 条
首次样本：
  bizId=SO20261021001, reason=库存不足
  java.lang.IllegalStateException: stock not enough
      at com.example.OrderService.create(OrderService.java:142)
      ... (截断至 10 行)
最近样本：
  bizId=SO20261021347, reason=库存不足
```

**实现位置的关键决策**：聚合需要跨实例状态，因此**只能在 T1（有 Redis）或 T2（有 MQ）梯度下生效**：

| 梯度 | 聚合方式 |
| --- | --- |
| T0（只有 JVM） | **实例内聚合**（同实例内 10s 窗口收拢），跨实例靠去重 + 静默期缓解。文档必须诚实说明「单实例部署下有效，多实例下每个实例各发一条」 |
| T1（有 Redis） | Redis `INCR` + `HSET` 存窗口计数与样本，第一个到达的实例负责在窗口结束时发送摘要（用 `SET NX` 抢锁） |
| T2（有 MQ） | **consumer 侧聚合**（推荐）——所有事件汇到单消费者，天然全局视图，聚合最准确 |

**数据结构现在就要留字段**（讨论稿已指出）：`NotifyContext` 必须带 `instanceId`，即使 P0 阶段不做聚合。字段后加的代价远高于现在留着。

`instanceId` 生成规则：`${spring.application.name}-${HOSTNAME}-${random 4位}`，启动时确定并缓存，**不能用 PID**（容器重启后 PID 常为 1，无区分度）。

### 9.6 升级策略（Escalation）

```yaml
notifyx:
  escalation:
    enabled: true
    rules:
      - scene: ORDER_CREATE_FAIL
        consecutive: 5           # 连续 5 次未恢复
        within: 10m              # 且在 10 分钟内
        upgrade-to: [sms, phone] # 升级到短信/电话
        reset-on-success: true   # 期间有成功发送则重置计数
```

**「未恢复」的判定**：同 `scene` 在窗口内持续产生事件即视为未恢复。恢复信号有两种：
1. **静默恢复**：窗口内不再产生该 scene 的事件 → 计数自然过期。
2. **显式恢复**：业务发送 `scene=ORDER_CREATE_RECOVERED` 的事件，配置 `escalation.reset-scenes` 关联 → 立即重置计数并可选发一条恢复通知。

显式恢复更准确，推荐在关键场景配置。**恢复通知本身很有价值**——运维半夜被叫醒后，最想知道的是「现在好了没有」。

**计数器存储**：T0 内存、T1 Redis（`INCR` + `EXPIRE`）。多实例下 T0 会导致「每个实例各自计数，升级触发更慢」，文档需说明。

### 9.7 渠道熔断

```
某渠道连续失败 ≥ 5 次（可配）
  → 熔断该渠道 30s（可配）
  → 期间事件切到备用渠道（fallback chain）
  → 30s 后半开：放 1 条试探
      ├─ 成功 → 关闭熔断，恢复正常
      └─ 失败 → 重新熔断 30s（退避翻倍，上限 5min）
```

**备用渠道链**：

```yaml
notifyx:
  channels:
    fallback-chain:
      email: [webhook, log]       # 邮件挂了走 webhook，再挂走日志
      dingtalk: [wecom, email, log]
      sms4j: [dingtalk, email]
      webhook: [log]
```

**最终兜底永远是 `log` 渠道**——`notifyx-channel-log` 只是把渲染后的内容以结构化日志输出。它保证「即使所有外部渠道都挂了，通知内容也不会凭空消失」，事后可从日志里捞回来。这个模块只有 30 行代码，价值极高。

**熔断状态的指标暴露**：`notifyx.channel.circuit{channel,state}`，让 Prometheus 能监控到「钉钉渠道正在熔断」这件事。

### 9.8 重试与幂等

**退避算法**：指数退避 + 随机抖动（防雪崩）

```
delay(attempt) = min(baseDelay × 2^(attempt-1), maxDelay) × (0.5 + random(0, 0.5))
```

- `baseDelay` 取渠道的 `retryBackoff()`（默认 2s）
- `maxDelay` 默认 60s
- **抖动系数 0.5~1.0**：不加抖动时，100 个实例的重试会在同一时刻打向同一个渠道，形成二次风暴

**放弃条件**：同一事件累计重试时间超过 `notifyx.retry.max-window`（默认 30m）→ 放弃 + 落盘。

**幂等（关键）**：外部渠道的重试必须考虑幂等，否则用户会收到两条「账号已开通」。

| 渠道 | 幂等手段 |
| --- | --- |
| webhook | 请求头带 `X-Notifyx-Idempotency-Key`，由接收方去重 |
| 短信 | ⚠️ **运营商侧无幂等能力**，重试必然重复发送且重复计费。因此 `Sms4jChannel.maxRetries()` **默认为 1**（即不重试），仅对「明确的网络超时」重试一次 |
| 邮件 | SMTP 层面重试可能导致重复投递，`Message-ID` 固定为 `eventId`，由收件方去重 |
| 钉钉/企微/飞书 | 无幂等能力，但重试仅在「未收到响应」时进行；**收到明确失败响应（如 errcode != 0）不重试**，因为那说明请求已被处理 |

**短信不重试这个决策需要写进文档**：宁可丢一条短信，也不要给用户发两条并double计费。这是产品判断而非技术判断。

### 9.9 失败落盘与补偿

**落盘结构**：

```
~/.notifyx/pending/
├── 2026102121/                    # 按小时分片，便于清理与定位
│   ├── evt_01J9ZK8M3N.json        # 一个事件一个文件，避免大文件并发写
│   └── evt_01J9ZK8M4P.json
└── 2026102122/
```

**单文件内容**：

```json
{
  "recordId": "evt_01J9ZK8M3N",
  "status": "PENDING",
  "version": 0,
  "envelope": { /* 完整 NotifyEnvelope */ },
  "channel": "email",
  "failureReason": "ChannelTimeoutException: connect timed out after 10s",
  "attempts": 3,
  "createdAt": 1729500000000,
  "nextRetryAt": 1729500300000
}
```

**状态机**：

```
PENDING ──(补偿任务拾取，CAS version)──→ SENDING ──(成功)──→ SENT ──(7天后清理)──→ 删除
                                            │
                                            └──(失败，attempts < max)──→ PENDING (nextRetryAt 后移)
                                            └──(失败，attempts ≥ max)──→ DEAD (人工介入)
```

**乐观锁的必要性**：多实例可能同时扫描同一个共享目录（NFS 场景），或用 JDBC store 时多实例扫同一张表。用 `version` 字段做 CAS 更新（`UPDATE ... SET status='SENDING', version=version+1 WHERE recordId=? AND version=?`），更新影响行数为 0 说明被别人抢走，跳过。

**补偿任务的单实例问题**：

| 方案 | 说明 | 建议 |
| --- | --- | --- |
| A. 每实例都扫，靠乐观锁去重 | 简单，但 N 个实例扫同一目录有 IO 放大 | **默认方案**（T0/T1 下） |
| B. ShedLock 选 leader | 只一个实例扫，需要 JDBC/Redis | 有 DB 时可选（`notifyx.fallback.leader-election=shedlock`） |
| C. MQ 单消费者 | T2 模式下天然单点 | `mode=mq` 时自动如此 |

**不在 SDK 里默认引入 ShedLock**（ADR-006 的一致立场）：那是第二套分布式协调机制。默认走方案 A，乐观锁足够——落盘补偿本身是低频兜底路径，IO 放大可接受。

**磁盘保护**：`~/.notifyx/pending` 必须设**容量上限**（默认 100MB / 10000 文件），超出后**丢最旧的 DEAD 记录**，并在启动诊断与指标里暴露 `notifyx.fallback.overflow`。否则通知持续失败会把磁盘写满，**把业务系统拖死——这是违反承诺 P2 的最隐蔽路径**。

### 9.10 优雅关闭

```java
// SmartLifecycle，phase 设为较早（比 Web 容器先停）
public class NotifyxLifecycle implements SmartLifecycle {
    @Override public void stop(Runnable callback) {
        // 1. 置 shutdown 标志，拒绝新事件（新事件直接落盘，不入队）
        accepting.set(false);
        // 2. 等待队列排空，最多 notifyx.async.shutdown-await（默认 10s）
        executor.shutdown();
        boolean drained = executor.awaitTermination(awaitSeconds, SECONDS);
        // 3. 未排空的部分强制落盘（不丢）
        if (!drained) {
            List<Runnable> remaining = executor.shutdownNow();
            failureStore.saveAll(remaining);
            log.warn("NotifyX 关闭超时，{} 条事件已落盘待补偿", remaining.size());
        }
        // 4. 释放渠道资源（SMTP 连接池、HTTP client、token 缓存）
        channels.forEach(NotifyChannel::close);
        callback.run();
    }
}
```

**为什么要 `SmartLifecycle` 而不是 `@PreDestroy`**：`@PreDestroy` 的执行时机在 Bean 销毁阶段，此时数据源、Redis 连接可能已经关闭，落盘补偿会失败。`SmartLifecycle.stop()` 在容器关闭的**早期**执行，依赖仍然可用。

**K8s 场景补充**：`terminationGracePeriodSeconds` 必须 > `notifyx.async.shutdown-await`，否则 SIGKILL 会打断落盘。文档需明确建议 `terminationGracePeriodSeconds: 30` + `shutdown-await: 10s`。

### 9.11 异步执行与队列（ADR-008 的落地）

```yaml
notifyx:
  async:
    enabled: true
    core-pool-size: 4
    max-pool-size: 16
    queue-capacity: 4096            # 软上限，超出走拒绝策略
    keep-alive: 60s
    thread-name-prefix: notifyx-
    rejected-policy: drop-lowest    # ★ 丢弃优先级最低的（ADR-008），不是 discard_oldest
    shutdown-await: 10s
```

**实现要点**：

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    corePoolSize, maxPoolSize, keepAlive, SECONDS,
    new PriorityBlockingQueue<>(initialCapacity, taskComparator),  // 按 Level 降序 + 时间升序
    new NotifyxThreadFactory(threadNamePrefix),                     // daemon=false，命名清晰便于 jstack
    new NotifyxRejectionHandler(failureStore, metrics)              // 自定义，含软上限检查
);
```

**入队前的软上限检查**（`PriorityBlockingQueue` 无界，必须自己控）：

```java
public boolean offer(NotifyTask task) {
    if (queue.size() >= queueCapacity) {
        NotifyTask lowest = findLowestPriorityInQueue();   // O(n) 扫描，只在满载时执行
        if (lowest != null && task.priority().compareTo(lowest.priority()) > 0) {
            queue.remove(lowest);      // 挤掉优先级更低的
            rejectionHandler.onDiscard(lowest, "replaced-by-higher-priority");
        } else {
            rejectionHandler.onDiscard(task, "queue-full");
            return false;
        }
    }
    return queue.offer(task);
}
```

**O(n) 扫描的代价**：只在队列满载时触发，正常情况不走这条路径。满载本身就是异常状态，此时多花几百微秒换取「保住 CRITICAL 告警」是值得的。

**禁用 `CallerRunsPolicy`**（承诺 P2 的底线）：它会把通知压力反压回业务线程，一个 SMTP 超时 10s 就能让业务接口 RT 从 50ms 变成 10s。

**daemon 线程的取舍**：设为 `false`（非 daemon），配合 `SmartLifecycle` 显式关闭。若设为 daemon，JVM 退出时会直接杀掉未完成的发送且**不落盘**，静默丢通知。

---

## 第 10 章 渠道设计

### 10.1 落地优先级与实现要点

| 优先级 | 渠道 | 模块 | 实现要点 |
| --- | --- | --- | --- |
| **P0** | email | `channel-email` | Jakarta Mail；`Transport` 连接复用与池化；TLS/SSL 双模式；发送超时 10s；失败四分类（认证/拒收/超时/DNS）；附件上限 10MB 可配；`Message-ID` 固定为 `eventId`（幂等） |
| **P0** | webhook | `channel-webhook` | 通用 HTTP；HMAC-SHA256 签名头 + 时间戳防重放；可配 header/期望状态码；响应体截断 1KB；超时 5s；支持签名禁用（内网调试） |
| **P0** | log | `channel-log` | ⚠️ **提前到 P0**（讨论稿未列）。结构化日志输出，30 行代码，是所有渠道的最终兜底（9.7） |
| **P1** | dingtalk | `channel-dingtalk` | 机器人 webhook + secret 加签（timestamp + HMAC-SHA256）；Markdown 卡片；`@`指定人（手机号/userId）；IP 白名单提示 |
| **P1** | wecom | `channel-wecom` | 双路：群机器人 webhook / 应用消息（corpid+agentId+secret）；文本 + Markdown |
| **P1** | feishu | `channel-feishu` | 机器人 webhook + 签名校验；富文本卡片；`tenant_access_token` 缓存（走应用方式时） |
| **P2** | sms4j | `channel-sms4j` | 适配层委托 `SmsFactory.getSmsBlend(cfg)`；**号级限流必须做**；`maxRetries()=1`（9.8）；排除传递依赖冲突 |

### 10.2 渠道实现的统一骨架

为保证「新增渠道 <80 行」，提供一个抽象基类：

```java
package io.github.pkyit.notifyx.channel.support;

/**
 * HTTP 类渠道的通用骨架。子类只需实现 buildPayload() 与 parseResponse()。
 *
 * @since 1.0
 */
public abstract class AbstractHttpChannel implements NotifyChannel {

    protected final NotifyxHttpClient http;   // 内部 HTTP 门面，见 §16.6

    /** 子类实现：把 Context 打包成渠道特定报文 */
    protected abstract HttpPayload buildPayload(NotifyContext ctx);

    /** 子类实现：解析响应，判定成功/失败并分类 */
    protected abstract SendResult parseResponse(HttpResponse resp, NotifyContext ctx);

    @Override
    public final SendResult send(NotifyContext ctx) throws ChannelException {
        HttpPayload payload = buildPayload(ctx);
        try {
            HttpResponse resp = http.post(payload);          // 超时、重试由 http 门面统一处理
            return parseResponse(resp, ctx);
        } catch (SocketTimeoutException e) {
            throw new ChannelTimeoutException(name(), e);     // → FAIL_RETRYABLE
        } catch (ConnectException e) {
            throw new ChannelTimeoutException(name(), e);     // → FAIL_RETRYABLE
        } catch (IOException e) {
            throw new ChannelException(name(), e);
        }
    }
}
```

**子类实现示例（钉钉，约 60 行）**：

```java
public class DingTalkChannel extends AbstractHttpChannel {

    @Override public String name() { return "dingtalk"; }
    @Override public RenderFormat preferredFormat() { return RenderFormat.MARKDOWN; }
    @Override public Set<Class<? extends Throwable>> nonRetryable() {
        return Set.of(ChannelAuthException.class);   // 签名错误重试也没用
    }

    @Override
    protected HttpPayload buildPayload(NotifyContext ctx) {
        DingTalkConfig cfg = ctx.channelConfigAs(DingTalkConfig.class);
        String url = cfg.signedUrl();     // webhook + timestamp + sign（HMAC-SHA256 + Base64 + URLEncode）
        Map<String, Object> body = Map.of(
            "msgtype", "markdown",
            "markdown", Map.of("title", ctx.renderedTitle(), "text", ctx.renderedContent()),
            "at", Map.of("atMobiles", ctx.atMobiles(), "isAtAll", cfg.atAll())
        );
        return HttpPayload.json(url, body).header("Content-Type", "application/json");
    }

    @Override
    protected SendResult parseResponse(HttpResponse resp, NotifyContext ctx) {
        if (resp.statusCode() != 200) {
            return SendResult.retryable(resp.truncatedBody(1024), resp.cost());
        }
        JsonNode json = NotifyxJson.readTree(resp.body());
        int errcode = json.path("errcode").asInt(-1);
        if (errcode == 0) {
            return SendResult.success(null, resp.truncatedBody(1024), resp.cost());
        }
        // ★ 关键分类：310000（签名/IP 白名单）、400013（群被解散）属不可重试
        if (NON_RETRYABLE_ERRCODES.contains(errcode)) {
            throw new ChannelAuthException(name(), "钉钉返回 errcode=" + errcode + ": " + json.path("errmsg").asText());
        }
        // 130101（发送太快）属限流，长退避重试
        if (errcode == 130101) {
            throw new ChannelRateLimitException(name(), json.path("errmsg").asText());
        }
        return SendResult.retryable(json.path("errmsg").asText(), resp.cost());
    }
}
```

### 10.3 各渠道的错误码分类表（必须写进代码常量）

这是渠道实现质量的核心。**分类错误的代价见 6.6**。

| 渠道 | 不可重试（`FAIL_NON_RETRYABLE`） | 可重试（`FAIL_RETRYABLE`） | 限流（长退避） |
| --- | --- | --- | --- |
| email | SMTP 535（认证失败）、550（收件人不存在/拒收）、552（超配额） | 421（服务不可用）、450（邮箱忙）、连接超时、DNS 失败 | 452（存储不足，退避后重试） |
| webhook | 400/401/403/404/422 | 5xx、超时、连接重置 | **429**（读 `Retry-After` 头） |
| dingtalk | 310000（签名/IP 白名单）、400013（群已解散）、300001（token 无效） | 5xx、超时 | 130101（发送太快）、410100（频率限制） |
| wecom | 40001（secret 无效）、301048（机器人已删除）、93000 | 5xx、超时、-1（系统繁忙） | 45009（接口调用超频） |
| feishu | 19001（签名校验失败）、19002（token 无效）、19022 | 5xx、超时 | 11232（频率限制）、9499 |
| sms4j | 厂商返回的「签名不合法」「模板未审核」「余额不足」「手机号非法」 | 超时、连接失败 | 厂商频控码 |

⚠️ **表中错误码需在实现时以各平台最新官方文档逐条核对**。IM 平台 API 改版频繁（见第 22 章风险 R7），因此每个渠道模块**必须有 WireMock 契约测试**，把这些错误码的响应体固化成测试夹具，API 变动能在 CI 里被发现。

### 10.4 webhook 渠道的签名规范

webhook 是最通用的渠道，也是对接第三方系统的入口，签名必须严谨：

**请求头**：

```
X-Notifyx-Signature: sha256=<hex>
X-Notifyx-Timestamp: 1729500000
X-Notifyx-Event-Id: evt_01J9ZK8M3N4P5Q6R7S8T9V0W1X
X-Notifyx-Idempotency-Key: sha256:8f3a...
X-Notifyx-Delivery-Attempt: 1
Content-Type: application/json; charset=utf-8
User-Agent: NotifyX/1.0
```

**签名算法**：

```
signContent = timestamp + "." + rawBody
signature   = HmacSHA256(signContent, secret) 转 hex 小写
```

**接收方验签示例（写进文档，这是渠道价值的一部分）**：

```java
@PostMapping("/notifyx/hook")
public ResponseEntity<Void> onNotify(@RequestBody String rawBody,
                                     @RequestHeader("X-Notifyx-Signature") String sig,
                                     @RequestHeader("X-Notifyx-Timestamp") long ts) {
    // 1. 防重放：时间戳偏差 > 5 分钟直接拒绝
    if (Math.abs(System.currentTimeMillis() / 1000 - ts) > 300) {
        return ResponseEntity.status(408).build();
    }
    // 2. 验签（常量时间比较，防时序攻击）
    String expected = "sha256=" + hmacSha256Hex(ts + "." + rawBody, secret);
    if (!MessageDigest.isEqual(expected.getBytes(UTF_8), sig.getBytes(UTF_8))) {
        return ResponseEntity.status(401).build();
    }
    // 3. 幂等：按 X-Notifyx-Idempotency-Key 去重
    if (!idempotencyStore.tryAcquire(idemKey)) return ResponseEntity.ok().build();
    // 4. 业务处理
    ...
}
```

**为什么用 `timestamp + "." + rawBody` 而不是只签 body**：只签 body 无法防重放——攻击者截获一个合法请求可以无限次重放。加入时间戳 + 接收方校验时间窗，把重放窗口压到 5 分钟。

**为什么用常量时间比较**：`String.equals()` 在第一个不匹配字节处返回，攻击者可通过响应时间逐字节猜出签名（时序攻击）。`MessageDigest.isEqual()` 是常量时间的。

### 10.5 IM 渠道的共通设计

| 关注点 | 设计 |
| --- | --- |
| **配置热刷新** | webhook URL / secret 必须支持从配置中心读取 + 运行时刷新（配合 Admin 的 pull 同步）。用 `volatile` 持有配置快照，刷新时整体替换而非逐字段改 |
| **token 缓存** | 企微应用消息、飞书应用消息需要 `access_token`，有效期 7200s。本地缓存 + 提前 300s 刷新 + 失败时强制刷新一次（应对被提前吊销）。**多实例下各自缓存**，不共享（token 接口有频控，但各实例独立请求在正常频控范围内） |
| **@人** | 钉钉用 `atMobiles`/`atUserIds`，企微用 `mentioned_list`，飞书用 `<at user_id="...">`。抽象为 `NotifyContext.atList()`，各渠道自行转换 |
| **长文本处理** | 钉钉 Markdown 上限约 20000 字符，企微文本 2048 字节，飞书 30KB。超限时**截断 + 追加「（内容已截断，完整内容见 eventId=xxx）」**，不报错 |
| **消息类型** | 按 `Level` 自动选：`CRITICAL`/`ERROR` 用 Markdown + `@所有人`（可配），`INFO` 用纯文本 |
| **域名可配** | ⚠️ **信创要求**：钉钉/企微/飞书的 API 域名**不得写死**，必须可配（`base-url`）。内网专有云部署时域名不同 |

### 10.6 sms4j 适配层的三个陷阱

```java
public class Sms4jChannel implements NotifyChannel {

    @Override public String name() { return "sms4j"; }
    @Override public int maxRetries() { return 1; }   // ★ 陷阱 1：短信重试会重复计费

    private final ObjectProvider<SmsFactory> smsFactoryProvider;   // ★ 陷阱 2：惰性获取

    @Override
    public boolean isEnabled(NotifyContext ctx) {
        // 用户没引 sms4j 时，SmsFactory 类不存在，这里不能触发类加载
        return smsFactoryProvider.getIfAvailable() != null;
    }

    @Override
    public SendResult send(NotifyContext ctx) throws ChannelException {
        SmsBlend blend = smsFactoryProvider.getObject().getSmsBlend(cfgId(ctx));
        // ★ 陷阱 3：sms4j 的模板变量顺序敏感，必须按渠道配置的模板 ID 传 LinkedHashMap
        SmsResponse resp = blend.sendMessage(ctx.receiver(), ctx.renderedContent());
        return resp.isSuccess()
            ? SendResult.success(resp.getBizId(), resp.toString(), resp.getCost())
            : classifyFailure(resp);   // 按 §10.3 分类
    }
}
```

| 陷阱 | 说明 | 对策 |
| --- | --- | --- |
| 1. 重试重复计费 | 运营商侧无幂等能力，重试 = 用户收到两条 + 双份费用 | `maxRetries()=1`，且只对「未收到响应」的超时重试 |
| 2. 传递依赖冲突 | sms4j 历史版本含 fastjson 等，可能与用户项目冲突 | `<exclusions>` 显式排除；CI 加依赖树断言（`maven-enforcer-plugin` 的 `bannedDependencies`） |
| 3. 模板变量顺序 | 部分厂商（如阿里云）的模板变量是**位置敏感**的，`Map` 顺序错会导致短信内容错位 | 用 `LinkedHashMap` 保持顺序；文档明确要求「模板变量顺序要和厂商后台完全匹配」 |

**号级限流实现**：

```yaml
notifyx:
  channels:
    sms4j:
      receiver-rate-limit:
        enabled: true
        rules:
          - window: 1m
            limit: 1
          - window: 1h
            limit: 5
          - window: 24h
            limit: 10
        on-exceed: defer        # defer（排队延后）| drop
        max-defer: 30m          # 延后超过 30m 则丢弃并落盘
```

三条规则**全部满足**才放行。触发时**排队延后**而非丢弃——短信通常是必须送达的业务通知（验证码除外，验证码应配 `on-exceed: drop`，因为过期验证码毫无意义）。

---

## 第 11 章 MQ 解耦与分布式部署

### 11.1 Bus SPI

```java
package io.github.pkyit.notifyx.core.spi;

/**
 * 传输总线 SPI。mode=mq 时启用。
 *
 * @since 1.0
 */
public interface NotifyBus extends Ordered {
    /** kafka / rabbitmq / rocketmq */
    String name();

    /** 发布报文。实现必须保证：失败时抛异常（由 SDK 落盘兜底），不能静默吞掉 */
    void publish(NotifyEnvelope envelope) throws BusException;

    /** 批量发布，聚合场景下用 */
    default void publishBatch(List<NotifyEnvelope> envelopes) throws BusException {
        envelopes.forEach(this::publish);
    }

    default void close() { }
}
```

每个 MQ 一个独立 optional 模块。模式由配置决定，**业务代码一行不改**：

```yaml
notifyx:
  mode: local      # local | mq
  bus: kafka       # 仅在 mode=mq 时生效
  bus-config:
    topic: notifyx-events
    bootstrap-servers: ${KAFKA_SERVERS}
```

### 11.2 三种 MQ 的实现差异与对策

| MQ | 保序 | 重试退避 | 幂等 | 实现要点 |
| --- | --- | --- | --- | --- |
| **Kafka** | `key = dedupeKey` 保证同事件有序 | 自建（producer 侧退避后重投） | consumer 侧按 `idempotencyKey` 去重 | 单 consumer 组消费；`acks=all` + `enable.idempotence=true`（producer 级幂等）；分区数 ≥ consumer 实例数 |
| **RocketMQ** | MessageQueue 选择器按 `dedupeKey` hash | ⭐ **直接用延迟级别**（1s/5s/10s/30s/1m/2m/…10m），天然实现指数退避 | consumer 侧 `SET NX` 或 DB 唯一索引 | 延迟级别比自建延迟队列干净得多，**优先推荐** |
| **RabbitMQ** | 单队列单消费者可保序，多消费者不保序 | `x-dead-letter-exchange` + 多级 TTL 队列（`retry.5s` / `retry.1m` / `retry.5m`） | consumer 侧幂等 | TTL 队列数量固定（3~4 级），不要为每次重试建队列 |

**RocketMQ 延迟级别的优势值得强调**：其他两种 MQ 要做退避重试，都需要自建延迟队列或死信队列链，代码量和运维复杂度都不小。RocketMQ 的 18 个固定延迟级别正好覆盖退避需求（1s→10m），`message.setDelayTimeLevel(level)` 一行搞定。因此**如果用户环境有 RocketMQ，优先推荐它**。

### 11.3 producer-only 模式的边界

SDK 侧只做三件事：

1. **序列化** —— `NotifyEvent` + 运行时上下文 → `NotifyEnvelope`（含 `schemaVersion`）
2. **发出** —— `NotifyBus.publish()`
3. **本地落盘兜底** —— 发出失败时写 `FailureStore`，由补偿任务重投

**明确不做**：消费、聚合、去重的最终裁决（这些都在 consumer 侧）。

**为什么不在 SDK 里用 ShedLock 解决多实例重复发送**（ADR-006 的一致立场）：那是引入第二套分布式协调机制。ShedLock 需要 DB 或 Redis，需要处理锁续期、锁泄漏、时钟漂移，故障面显著增大。而 MQ 单消费者 + 幂等键是**行业标准解法**，成熟且易理解。

### 11.4 consumer 服务的职责与幂等

`notifyx-consumer` 是独立部署的服务端应用：

```
MQ topic ──→ consumer ──→ 幂等检查 ──→ 完整责任链（去重/限流/聚合/免打扰）──→ 渠道发送 ──→ 审计
                              │
                              └─ 重复消息 → 直接 ACK 丢弃
```

**幂等实现**：

| 梯度 | 手段 |
| --- | --- |
| 有 Redis | `SET nx:idem:{idempotencyKey} 1 NX EX 86400`（保留 24h，覆盖 MQ 最大重投窗口） |
| 有 DB | `nx_idempotency` 表 + 唯一索引，插入冲突即视为重复 |
| 都没有 | ⚠️ 内存 LRU（**会在重启后失效**），文档必须警告「无持久化幂等存储时，consumer 重启可能导致重复发送」 |

**consumer 的部署形态**：单实例（最简，天然无重复）或多实例 + 分区消费（Kafka 按 key 分区，同 `dedupeKey` 必到同一 consumer）。**推荐单实例起步**——通知量通常不需要横向扩展，单实例能彻底规避分布式协调问题。

**consumer 自身的高可用**：consumer 挂了，消息在 MQ 里堆积不丢（这正是引入 MQ 的价值）。恢复后自动消费积压。但需注意 **积压告警**：`notifyx.consumer.lag` 指标必须暴露，堆积超过阈值时……用 NotifyX 自己告警（dogfooding，但要走**独立的告警通道**，避免自己依赖自己形成循环——具体做法是 consumer 的堆积告警直接写日志 + Prometheus 指标，由 Prometheus 侧配规则告警）。

### 11.5 多实例重复发送：选型表（README 必备章节）

这是最容易踩的坑，必须给明确指引：

| 方案 | 适用场景 | 优点 | 缺点 | 配置 |
| --- | --- | --- | --- | --- |
| **A. 静默期容忍** | 多实例 ≤ 5、告警时效性要求不高 | 零依赖、零配置 | 每实例各发一条，N 实例 = N 条重复 | `dedupe.store=memory` + `silent-window: 5m` |
| **B. Redis 分布式去重** | 多实例、有 Redis、需要严格单发 | 精确、实时、运维简单 | 依赖 Redis；Redis 故障期降级为方案 A | `dedupe.store=redis` |
| **C. MQ 单消费者** | 大规模、通知量大、需要集中管控 | 根治问题、天然聚合、密钥集中管理 | 架构复杂度上升、需部署 consumer 服务 | `mode=mq` + `notifyx-consumer` |
| **D. JDBC 去重** | 有 DB 无 Redis（信创场景常见） | 复用现有 DB | DB 写入压力；需清理过期记录 | `dedupe.store=jdbc` |

**决策建议**：

```
通知量 < 100 条/天 且 实例数 ≤ 3     → 方案 A（别过度设计）
有 Redis                              → 方案 B（默认推荐）
通知量 > 10000 条/天 或 实例数 > 20   → 方案 C
信创环境有国产 DB 无 Redis             → 方案 D
```

**计费渠道的特殊警告**：短信是**按条计费**的，方案 A 下 N 个实例 = N 倍费用。因此**短信渠道强制要求方案 B/C/D**——实现上，若 `Sms4jChannel` 检测到 `dedupe.store=memory` 且实例数 > 1（通过配置 `notifyx.instance-count-hint` 或 Admin 注册信息判断），启动诊断里打一条 **WARN**：「短信渠道在内存去重 + 多实例下可能重复计费，建议配置 Redis 去重或 MQ 模式」。

---

## 第 12 章 存储层与信创数据库适配

### 12.1 定位与边界

MyBatis-Plus **只出现在** `notifyx-store-mybatisplus` 和 `notifyx-admin`。负责三件事：审计落库、配置持久化、控制台查询。

**core 保持零 Spring、零 MP、零 DB**（ADR-003）。这条底线破了，承诺 P1 就破产。

### 12.2 表结构设计

```sql
-- ============================================================
-- nx_app：接入应用注册（Admin 侧的应用身份）
-- ============================================================
create table nx_app (
    id            bigint       not null primary key,
    app_id        varchar(64)  not null,               -- 客户端标识
    app_name      varchar(128) not null,
    app_secret    varchar(256) not null,               -- ★ 加密存储，见 §16.3
    tenant        varchar(64),
    sdk_version   varchar(32),                         -- 客户端上报，用于版本升级提示
    instance_count int         default 0,              -- 客户端上报，用于 §11.5 警告
    status        tinyint      default 1,              -- 1=启用 0=禁用
    remark        varchar(512),
    create_time   datetime,
    update_time   datetime,
    constraint uk_nx_app_app_id unique (app_id)
);

-- ============================================================
-- nx_scene_config：场景配置（Admin 可视化的主体）
-- ============================================================
create table nx_scene_config (
    id             bigint       not null primary key,
    app_id         varchar(64)  not null,              -- ★ 按应用隔离，见下方说明
    scene          varchar(64)  not null,
    level          varchar(16)  default 'WARN',
    channels       varchar(256),                       -- JSON: ["email","dingtalk"]
    template_code  varchar(64),
    receivers      varchar(1024),                      -- JSON: ["admin@x.com"]
    receivers_from varchar(128),                       -- 从 params 取接收人的表达式
    silent_seconds int          default 300,
    enabled        tinyint      default 1,
    tenant         varchar(64),
    description    varchar(512),
    version        int          default 0,             -- ★ 乐观锁 + 配置快照版本
    deleted        tinyint      default 0,             -- 逻辑删除
    create_time    datetime,
    update_time    datetime,
    constraint uk_nx_scene_app_scene unique (app_id, scene)
);

-- ============================================================
-- nx_channel_config：渠道配置（密钥加密存储）
-- ============================================================
create table nx_channel_config (
    id            bigint       not null primary key,
    app_id        varchar(64),                         -- null = 全局共享渠道
    channel       varchar(32)  not null,               -- email/dingtalk/...
    config_json   text         not null,               -- ★ 整体加密存储，非明文
    enabled       tinyint      default 1,
    priority      int          default 0,              -- 同渠道多配置时的选择顺序
    version       int          default 0,
    create_time   datetime,
    update_time   datetime,
    constraint uk_nx_channel_app_channel unique (app_id, channel)
);

-- ============================================================
-- nx_template：消息模板
-- ============================================================
create table nx_template (
    id            bigint       not null primary key,
    template_code varchar(64)  not null,
    channel       varchar(32),                         -- null = 通用模板
    format        varchar(16)  default 'PLAIN',        -- PLAIN/MARKDOWN/HTML
    title_tpl     varchar(512),
    content_tpl   text         not null,
    variables     varchar(1024),                       -- JSON: 变量名 + 说明 + 示例，供「在线试发」用
    version       int          default 0,
    enabled       tinyint      default 1,
    create_time   datetime,
    update_time   datetime,
    constraint uk_nx_template_code_channel unique (template_code, channel)
);

-- ============================================================
-- nx_rule：限流 / 升级 / 免打扰规则
-- ============================================================
create table nx_rule (
    id            bigint       not null primary key,
    app_id        varchar(64),
    rule_type     varchar(32)  not null,               -- RATE_LIMIT/ESCALATION/QUIET_HOURS/GLOBAL_MUTE
    scope         varchar(64),                         -- scene 名 或 'GLOBAL'
    rule_json     text         not null,
    priority      int          default 0,
    enabled       tinyint      default 1,
    version       int          default 0,
    create_time   datetime,
    update_time   datetime
);

-- ============================================================
-- nx_audit：发送审计（写入量最大的表，重点优化）
-- ============================================================
create table nx_audit (
    id            bigint       not null primary key,
    event_id      varchar(64)  not null,
    scene         varchar(64),
    level         varchar(16),
    biz_id        varchar(128),
    channel       varchar(32),
    receiver      varchar(256),                        -- ★ 脱敏后存储，见 §16.2
    status        varchar(16),                         -- SUCCESS/FAIL_*/DEDUPED/RATE_LIMITED/...
    error_msg     varchar(1024),
    attempts      int,
    cost_ms       bigint,
    instance_id   varchar(64),
    trace_id      varchar(64),
    app_id        varchar(64),
    tenant        varchar(64),
    created_at    datetime     not null,
    sent_at       datetime,
    constraint idx_nx_audit_scene_created  index (scene, created_at),
    constraint idx_nx_audit_receiver_created index (receiver, created_at),
    constraint idx_nx_audit_event index (event_id)
);

-- ============================================================
-- nx_idempotency：consumer 侧幂等表（仅 MQ 模式）
-- ============================================================
create table nx_idempotency (
    idempotency_key varchar(128) not null primary key,
    event_id        varchar(64)  not null,
    created_at      datetime     not null,
    constraint idx_nx_idem_created index (created_at)   -- 用于定期清理
);

-- ============================================================
-- nx_failure_record：失败落盘补偿（JDBC 版 FailureStore）
-- ============================================================
create table nx_failure_record (
    record_id       varchar(64)  not null primary key,
    envelope_json   text         not null,
    channel         varchar(32),
    status          varchar(16)  default 'PENDING',    -- PENDING/SENDING/SENT/DEAD
    failure_reason  varchar(1024),
    attempts        int          default 0,
    version         int          default 0,            -- ★ 乐观锁，见 §9.9
    next_retry_at   datetime,
    created_at      datetime,
    updated_at      datetime,
    constraint idx_nx_fail_status_retry index (status, next_retry_at)
);
```

**关于 `nx_scene_config.app_id` 的设计说明**（本文档对讨论稿的补充）：讨论稿的 `nx_scene_config` 只有 `scene` 唯一约束。但 Admin 是**多应用共享**的（否则每个应用部署一套 Admin 毫无意义），不同应用的 `ORDER_CREATE_FAIL` 场景配置可能完全不同。因此唯一键必须是 `(app_id, scene)`，且 SDK pull 配置时按自己的 `appId` 拉取。

### 12.3 nx_audit 的容量治理

审计表是**写入量最大**的表。按 10 万事件/天 × 平均 2 个渠道 × 2 个接收人 = 40 万行/天，一年 1.4 亿行。必须治理：

| 手段 | 配置 | 说明 |
| --- | --- | --- |
| **保留期清理** | `notifyx.audit.retention-days: 30` | `@Scheduled` 每日凌晨按 `created_at` 批量删除，**分批 1000 行**避免大事务锁表 |
| **采样** | `notifyx.audit.sample-rate: 1.0` | 高频场景可设 0.1（只记 10%），但 **失败记录永远 100% 记录** |
| **异步批量写** | `notifyx.audit.batch-size: 100` + `flush-interval: 5s` | 攒批插入，DB 压力降一个数量级 |
| **分区表（可选）** | 按月分区 | 大流量场景推荐，清理变成 `DROP PARTITION`（秒级）而非 `DELETE`（分钟级） |
| **归档** | 超期数据导出到对象存储 | 满足等保的长期留痕要求，同时不给在线库压力 |

**`retention-days` 与等保的冲突**：等保三级要求日志留存 ≥ 6 个月。因此默认 30 天是**在线库保留期**，超期数据应归档而非删除。文档必须写清这一点，并提供归档 SQL/脚本。

### 12.4 信创数据库适配清单（MP 侧）

**基线**：`mybatis-plus-spring-boot3-starter` 3.5.x（配套 `mybatis-plus-jsqlparser` 同版本，用 `mybatis-plus-bom` 管版本）。⚠️ 具体小版本号实现时以官方最新发布为准。

| # | 要求 | 理由 |
| --- | --- | --- |
| 1 | **禁止手动 `setDbType`** | `PaginationInnerInterceptor` 靠 JDBC 运行时自动识别方言，天然支持 DM/金仓。手动设置会在多库环境下出错 |
| 2 | **各库独立 DDL**：`sql/mysql/`、`sql/dm/`、`sql/kingbase/`、`sql/opengauss/` | **禁止复用 MySQL 脚本**。数据类型、索引语法、保留字差异都很大 |
| 3 | **主键统一 `ASSIGN_ID`（雪花）** | 避开达梦/金仓的自增与序列语法差异。雪花 ID 需要 `workerId` 配置，多实例下必须唯一（用 IP 后两段或配置显式指定） |
| 4 | **HikariCP 配置**：`max-lifetime` 比服务端超时小 30s；`connection-test-query: SELECT 1`；`auto-commit` **不得为 false** | 国产库的连接空闲超时通常比 MySQL 短，`max-lifetime` 设大会拿到已断开的连接 |
| 5 | **驱动作 optional**：`com.dameng:DmJdbcDriver18`、`cn.com.kingbase:kingbase8`、`org.opengauss:opengauss-jdbc` | 用户不用的库不拖进 classpath |
| 6 | **CI 开国产库 profile 跑冒烟**（建表 + 一条 CRUD + 一次分页） | 不做 CI 验证的「支持」是假支持 |

**方言雷区对照表**：

| MySQL 写法 | 问题 | 通用/金仓写法 |
| --- | --- | --- |
| `FIND_IN_SET(a, b)` | 金仓不支持 | `POSITION(a IN b) > 0` 或改为 JSON 字段 + 应用层解析 |
| `IFNULL(a, b)` | 金仓/DM 兼容性不稳 | **`COALESCE(a, b)`**（SQL 标准，全支持） |
| `GROUP_CONCAT(x)` | 金仓不支持 | **`STRING_AGG(x, ',')`** 或应用层聚合 |
| `ON DUPLICATE KEY UPDATE` | 金仓/DM 不支持 | **`MERGE INTO ... USING ... WHEN MATCHED THEN UPDATE`**（SQL 标准） |
| `LIMIT 10, 20` | 逗号语法非标准 | `LIMIT 20 OFFSET 10`（交给 MP 分页插件自动生成） |
| 表/列名 `user`、`name`、`level` | ⚠️ **金仓保留字**，且大小写敏感 | 加双引号，或用 MP 的 `column-format` 统一加引号；**更好的做法是改列名**（`app_user`、`scene_name`、`event_level`） |
| `tinyint(1)` | DM/金仓无 `tinyint` 显示宽度概念 | 用 `smallint` 或 `int`；布尔语义用 `char(1)` `'Y'/'N'` 更稳 |
| `datetime` | 各库精度不同 | 用 `timestamp`；DM 需 `timestamp(0)` 明确精度 |
| `text` | DM 用 `clob`，金仓用 `text` | 各库 DDL 独立编写的原因 |
| 索引内联 `index idx_xxx(a,b)` | DM 不支持建表内联索引 | **独立 `create index` 语句** |

**关于 `level` 列名的特别提醒**：`nx_audit.level` 和 `nx_scene_config.level` 都用了 `level`——这在金仓里是保留字。**建议改名为 `event_level`**，从根上规避，而不是靠加引号（加引号后 MyBatis 的手写 SQL 处处要小心，维护成本高）。这是本文档对讨论稿表设计的一处具体修正。

### 12.5 诚实声明（必须写进 README）

> **框架层一等支持 ≠ 换库就能上线。**
>
> NotifyX 的 JDBC 模块在 MySQL、达梦（DM8）、人大金仓（KingbaseES V8）、openGauss 上通过了 CI 冒烟测试（建表、CRUD、分页、乐观锁）。但**你的生产环境仍需要做模块级回归**，原因包括：数据库版本差异、字符集与排序规则、schema 与用户权限配置、以及你的 MyBatis-Plus 版本组合。
>
> 信创环境首次部署时，请预留 1~2 天做完整回归，不要假设「配个 JDBC URL 就能跑」。

这段声明看起来是示弱，实际是**降低使用者的预期偏差**，避免他们在生产环境踩坑后对项目失去信任。开源项目的信誉来自诚实，不来自夸大。

---

## 第 13 章 Admin 控制台与 REST API 契约

### 13.1 形态与耦合度

| 项 | 设计 |
| --- | --- |
| 部署形态 | 独立 jar（内嵌 Servlet 容器 + Vue3 静态资源）+ Docker 镜像 |
| 与 SDK 的耦合 | **零耦合**。starter 不依赖 admin，admin 也不扫描业务 classpath |
| 配置同步 | pull 模型 + 版本号比对（ADR-007），支持长轮询 |
| 客户端鉴权 | Admin 注册应用 → 生成 `appId`/`appSecret` → 请求签名（HMAC + 时间戳） |
| 前端 | Vue3 + Vite + Element Plus，源码在 `notifyx-admin/ui`，产物打进 `resources/static`，一个 jar 交付 |

**选 Element Plus 而非 Naive UI 的理由**：Admin 的主体是**表格 + 表单**（场景配置、发送记录、渠道配置），Element Plus 在这两类组件上成熟度和文档完备度更高，国内生态与招聘熟悉度也更好。轻量的代价是包体积，但 Admin 是内网系统，包体积不敏感。

### 13.2 功能清单（MVP → 完整版）

| 模块 | MVP（P4） | 后续 |
| --- | --- | --- |
| **场景管理** | 增删改查、启停、level、通道勾选、静默期、模板绑定 | 批量导入、场景分组、**噪声评分**（自动建议关闭高频低价值场景） |
| **渠道配置** | email/webhook/钉钉/企微/飞书/短信表单，密钥加密存储与打码显示 | 多厂商短信权重与故障转移、通道健康探测 |
| **模板管理** | 变量预览、**在线试发**（填 params 看渲染结果） | 版本历史、多语言模板 |
| **发送记录** | 分页查询（scene/receiver/时间/状态）、详情、**重发** | 导出、全文检索 |
| **大盘** | 今日发送量、成功率、Top 噪声场景、渠道耗时 P95 | 趋势图、订阅日报 |
| **规则** | 限流阈值、免打扰时段、升级策略、**一键全局静默** | 告警联动（指标喂回 Prometheus） |
| **接入中心** | 生成 Maven 坐标 + 接入代码片段 + appSecret | SDK 版本升级提示 |
| **系统** | 登录、角色、数据权限（租户隔离）、操作审计 | LDAP/SSO |

**「在线试发」是 Admin 最有价值的单一功能**：运维配完模板立刻填参数看渲染结果，能消除绝大多数线上模板错误（变量名写错、格式不对、字段缺失）。实现上必须是**纯服务端渲染 + 不落审计**（避免试发数据污染统计），且**不真发**（只返回渲染结果预览）；另有「真实试发」按钮走完整链路但强制发到操作者自己的地址。

**「一键全局静默」的交互要求**：必须是首屏可见的红色按钮，二次确认，且**记录操作者与时间**到操作审计。它是运维半夜救命的按钮，不能在三级菜单里。

### 13.3 REST API 契约

遵循资源化 URL 设计，统一 `/api/v1` 前缀便于未来版本演进。

#### 13.3.1 客户端 SDK 侧接口（供 starter pull 配置）

| 方法 | 路径 | 说明 | 鉴权 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/client/config` | 拉取本应用的完整配置快照（场景 + 渠道 + 规则 + 模板） | HMAC 签名 |
| `GET` | `/api/v1/client/config/version` | **轻量版本探测**，只返回 `{version, globalMute}` | HMAC 签名 |
| `POST` | `/api/v1/client/heartbeat` | 上报 SDK 版本、实例数、队列水位、渠道健康 | HMAC 签名 |
| `POST` | `/api/v1/client/audit/batch` | 批量上报审计（DB 不在业务侧时用） | HMAC 签名 |

**长轮询设计**：`GET /api/v1/client/config?sinceVersion=42&waitSeconds=30`

- 服务端比对当前版本，若 `> sinceVersion` 立即返回新快照；
- 否则挂起最多 `waitSeconds`（服务端上限 60s），期间版本变化立即返回；
- 超时无变化返回 `304 Not Modified`（不带 body，省带宽）。

这样配置生效延迟从「轮询间隔」降到「秒级」，同时请求量远低于短轮询。

**客户端签名算法**：

```
signContent = appId + "\n" + timestamp + "\n" + nonce + "\n" + sha256(requestBody)
X-Notifyx-App-Id:     {appId}
X-Notifyx-Timestamp:  {epochSeconds}
X-Notifyx-Nonce:      {随机 16 字节 hex}
X-Notifyx-Signature:  sha256=HmacSHA256(signContent, appSecret)
```

服务端校验：时间戳偏差 ≤ 300s（防重放）+ nonce 5 分钟内不重复（Redis/内存 LRU）+ 签名匹配。

#### 13.3.2 管理台接口（供前端调用）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/scenes?appId=&enabled=&page=&size=` | 场景分页列表 |
| `POST` | `/api/v1/scenes` | 新建场景 |
| `GET` | `/api/v1/scenes/{id}` | 场景详情 |
| `PUT` | `/api/v1/scenes/{id}` | 全量更新（带 `version` 乐观锁） |
| `PATCH` | `/api/v1/scenes/{id}` | 部分更新（启停、改静默期） |
| `DELETE` | `/api/v1/scenes/{id}` | 逻辑删除 |
| `GET/POST/PUT/DELETE` | `/api/v1/channels[...]` | 渠道配置 CRUD |
| `POST` | `/api/v1/templates/{code}/preview` | ★ **在线试发**：入参 `{params:{...}, channel:"dingtalk"}`，返回渲染结果 |
| `GET` | `/api/v1/audits?scene=&receiver=&status=&from=&to=&page=&size=` | 发送记录查询 |
| `POST` | `/api/v1/audits/{eventId}/resend` | 重发 |
| `GET` | `/api/v1/dashboard/summary?date=` | 大盘汇总 |
| `POST` | `/api/v1/rules/global-mute` | ★ **一键全局静默**：`{muted:true, duration:"2h", reason:"..."}` |
| `GET/POST` | `/api/v1/apps[...]` | 接入应用管理 + 生成 appSecret |

**统一响应包装**：

```json
{ "code": 0, "message": "ok", "data": { }, "traceId": "..." }
```

| code | 含义 | HTTP 状态 |
| --- | --- | --- |
| 0 | 成功 | 200/201 |
| 40001 | 参数校验失败（`data` 含字段级错误） | 400 |
| 40101 | 未认证 / 签名无效 | 401 |
| 40301 | 无权限（租户隔离拦截） | 403 |
| 40401 | 资源不存在 | 404 |
| 40901 | **乐观锁冲突**（`version` 不匹配，提示刷新） | 409 |
| 50001 | 服务端错误 | 500 |

**乐观锁必须做**：多个运维同时改同一个场景配置时，后提交的应该收到 409「配置已被他人修改，请刷新」，而不是静默覆盖。`PUT` 请求体必须带 `version`，SQL 用 `UPDATE ... WHERE id=? AND version=?`，影响行数为 0 即冲突。

#### 13.3.3 Actuator 端点（SDK 侧）

`/actuator/notifyx`（**只读**）：

```json
{
  "version": "1.0.0",
  "mode": "local",
  "capabilityTier": "T1",
  "instanceId": "order-service-7d9f8b-x2k4p",
  "globalMute": false,
  "configVersion": 42,
  "configSource": "admin",
  "lastConfigSyncAt": "2026-10-21T21:15:03+08:00",
  "channels": [
    { "name": "email",     "enabled": true, "circuit": "CLOSED", "successRate": 0.998, "p95Ms": 320 },
    { "name": "dingtalk",  "enabled": true, "circuit": "OPEN",   "successRate": 0.412, "p95Ms": 5010,
      "circuitReason": "连续 5 次超时，熔断至 21:16:03" },
    { "name": "sms4j",     "enabled": false, "reason": "classpath 未检测到 sms4j" }
  ],
  "scenes": { "total": 23, "enabled": 21, "topNoise": [
    { "scene": "ORDER_CREATE_FAIL", "count24h": 12450, "deduped": 11200, "suggestion": "去重率 90%，建议延长静默期" }
  ]},
  "queue": { "size": 12, "capacity": 4096, "discarded24h": 0, "largestTask": null },
  "dedupe": { "store": "redis", "size": 340, "degraded": false },
  "rateLimit": { "store": "redis", "sceneLimited24h": 45, "globalLimited24h": 0 },
  "fallback": { "store": "local-file", "pending": 0, "dead": 0, "path": "~/.notifyx/pending", "diskUsageKb": 0 },
  "recentAudits": [ /* 最近 20 条，接收人已脱敏 */ ]
}
```

**这个端点的价值高于全部文档**：生产排查时，运维第一眼要看的就是「哪个渠道熔断了、队列有没有积压、去重降级了没有、配置同步到第几版」。**配置快照输出必须脱敏**（密钥打码、接收人掩码）。

### 13.4 前端页面结构

```
登录
├── 大盘（首页）        今日发送量 / 成功率 / Top 噪声场景 / 渠道耗时 P95 / 全局静默按钮
├── 场景管理            列表 → 编辑抽屉（通道勾选 + 模板绑定 + 静默期 + 接收人）
├── 模板管理            列表 → 编辑器（左：模板 + 变量表；右：填参数实时预览）★在线试发
├── 渠道配置            卡片式，每个渠道一张卡，密钥打码，「测试连通性」按钮
├── 发送记录            表格（scene/receiver/时间/状态筛选）→ 详情抽屉（含渲染内容、错误、traceId）→ 重发
├── 规则中心            限流阈值 / 免打扰时段 / 升级策略 / 熔断参数
├── 接入中心            应用列表 → 生成 appSecret → Maven 坐标 + 代码片段一键复制
└── 系统管理            用户 / 角色 / 租户 / 操作审计
```

**「测试连通性」按钮的实现约束**：必须走 `NotifyChannel#health()`，且**默认不发真实消息**（邮件只做 SMTP `NOOP`/`AUTH`，IM 只校验 token 有效性）。避免运维点一下按钮就给全组发了一条测试消息。

---

## 第 14 章 配置字典

### 14.1 完整 YAML 全貌

```yaml
notifyx:
  enabled: true                        # 总开关，false 时整个 starter 不装配
  env: prod                            # 打在全局模板里的环境标识
  tenant: ${spring.application.name:unknown}
  mode: local                          # local | mq
  bus: kafka                           # 仅 mode=mq 生效
  instance-id:                         # 留空则自动生成 {app}-{host}-{rand4}

  # ---------- Admin 配置同步（可选，不配则纯 YAML 驱动）----------
  admin:
    enabled: false
    base-url: http://notifyx-admin:8080
    app-id: ${NOTIFYX_APP_ID}
    app-secret: ${NOTIFYX_APP_SECRET}   # 建议 Jasypt 加密
    pull-interval: 30s                  # 短轮询间隔
    long-polling: true                  # 推荐开启，生效延迟降到秒级
    fallback-to-yaml: true              # ★ Admin 不可用时用本地 YAML 兜底（默认 true）
    connect-timeout: 3s
    read-timeout: 35s                   # 需 > 长轮询 waitSeconds

  # ---------- 异步执行 ----------
  async:
    enabled: true
    core-pool-size: 4
    max-pool-size: 16
    queue-capacity: 4096
    keep-alive: 60s
    thread-name-prefix: notifyx-
    rejected-policy: drop-lowest        # ★ drop-lowest（ADR-008），非 discard-oldest
    shutdown-await: 10s

  # ---------- 默认值（场景未指定时使用）----------
  defaults:
    receivers: [admin@example.com]
    level: WARN
    silent-window: 5m
    template: default

  # ---------- 场景配置：一切以 scene 为锚 ----------
  scenes:
    ORDER_CREATE_FAIL:
      level: ERROR
      channels: [email, dingtalk]
      template: order-fail
      silent-window: 5m
      retry: 3
      receivers: [ops@example.com]
      escalation:                        # 场景级升级（覆盖全局规则）
        consecutive: 5
        within: 10m
        upgrade-to: [sms4j]
    ACCOUNT_CREATED:
      level: INFO
      channels: [sms4j]
      template: account-created
      receivers-from: params.receiver     # ★ 从参数里取接收人
      silent-window: 0                    # 业务通知不去重
    UNCAUGHT_EXCEPTION:
      level: ERROR
      channels: [dingtalk, log]
      silent-window: 10m

  # ---------- 渠道配置 ----------
  channels:
    fallback-chain:                       # 熔断时的备用渠道链（§9.7）
      email: [webhook, log]
      dingtalk: [wecom, email, log]
      sms4j: [dingtalk, email]
    circuit-breaker:
      enabled: true
      failure-threshold: 5
      open-duration: 30s
      max-open-duration: 5m
    email:
      enabled: true
      host: smtp.qq.com
      port: 465
      ssl: true
      starttls: false
      username: noreply@example.com
      password: ${SMTP_PASS}              # ★ 建议 Jasypt ${ENC(...)}
      from: noreply@example.com
      from-name: NotifyX
      connect-timeout: 5s
      write-timeout: 10s
      pool-size: 5                        # Transport 池化
      max-attachment-mb: 10
    webhook:
      enabled: true
      url: https://hooks.example.com/notifyx
      secret: ${WEBHOOK_SECRET}
      sign-enabled: true                  # 内网调试可关
      headers:
        X-Source: notifyx
      expect-status: [200, 201, 204]
      connect-timeout: 3s
      read-timeout: 5s
    dingtalk:
      enabled: true
      base-url: https://oapi.dingtalk.com # ★ 可配，信创专有云用
      webhook: ${DT_WEBHOOK}
      secret: ${DT_SECRET}
      at-all: false
      at-mobiles: []
    wecom:
      enabled: false
      base-url: https://qyapi.weixin.qq.com
      mode: robot                         # robot | app
      webhook: ${WECOM_WEBHOOK}
      corp-id:                            # mode=app 时必填
      agent-id:
      secret:
    feishu:
      enabled: false
      base-url: https://open.feishu.cn
      webhook: ${FS_WEBHOOK}
      secret: ${FS_SECRET}
    sms4j:
      enabled: false                      # ★ 默认关，引了 SDK 才开
      config-id: aliyun                   # 对应 sms4j 的 blends 配置名
      max-retries: 1                      # ★ 短信不重试（§9.8）
      receiver-rate-limit:
        enabled: true
        rules:
          - { window: 1m,  limit: 1 }
          - { window: 1h,  limit: 5 }
          - { window: 24h, limit: 10 }
        on-exceed: defer                  # defer | drop
        max-defer: 30m
    log:
      enabled: true                       # ★ 默认开，作为最终兜底
      logger-name: NOTIFYX_FALLBACK

  # ---------- 模板 ----------
  templates:
    engine: simple                        # simple | freemarker | thymeleaf
    default:
      title: "[${env}] ${scene}"
      content: |
        场景：${scene}
        级别：${level}
        业务ID：${bizId}
        时间：${occurredAt}
        实例：${instanceId}
        TraceId：${traceId}
        详情：${params}
    order-fail:
      title: "【订单异常】${bizId}"
      content: |
        订单 ${bizId} 创建失败
        原因：${reason}
        异常：${exception}
        时间：${occurredAt}

  # ---------- 去重 ----------
  dedupe:
    enabled: true
    store: auto                           # auto | memory | redis | jdbc
    default-window: 5m
    max-entries: 100000                   # 内存实现的容量上限，防 OOM

  # ---------- 限流 ----------
  rate-limit:
    enabled: true
    store: auto                           # auto | memory | redis
    rules:
      - scene: ORDER_CREATE_FAIL
        limit: 10
        window: 1m
      - global: true
        limit: 100
        window: 1m                        # ★ 防通知风暴的总闸
        on-exceed: summary                # summary（发汇总摘要）| drop
    receiver-limit:                       # 单接收人总闸（跨场景）
      enabled: true
      limit: 30
      window: 1h

  # ---------- 聚合 ----------
  aggregate:
    enabled: false                        # T0 下仅实例内聚合，建议 T1/T2 开启
    window: 15s
    min-count: 5                          # 少于 5 条不聚合，直接发
    max-samples: 3                        # 摘要里保留几个样本

  # ---------- 重试 ----------
  retry:
    enabled: true
    backoff: exponential                  # fixed | exponential
    base-delay: 2s
    max-delay: 60s
    jitter: 0.5                           # 抖动系数（§9.8）
    max-attempts: 3
    max-window: 30m                       # 累计超过则放弃并落盘

  # ---------- 失败落盘补偿 ----------
  fallback:
    store: local-file                     # local-file | jdbc | mq
    path: ${user.home}/.notifyx/pending
    max-size-mb: 100                      # ★ 磁盘保护（§9.9），超出丢最旧 DEAD
    max-files: 10000
    compensation:
      enabled: true
      scan-interval: 60s
      batch-size: 50
      leader-election: none               # none | shedlock
      retention-days: 7

  # ---------- 审计 ----------
  audit:
    enabled: true
    store: none                           # none | jdbc | admin-report
    batch-size: 100
    flush-interval: 5s
    sample-rate: 1.0                      # 失败记录永远 100%（§12.3）
    retention-days: 30                    # 在线库保留期，超期归档而非删除

  # ---------- 免打扰 ----------
  quiet-hours:
    enabled: false
    from: "22:00"
    to: "09:00"
    timezone: Asia/Shanghai
    defer: true                           # true=延后到工作时间；false=直接丢弃
    critical-bypass: true                 # ★ CRITICAL 直通（§9.2）

  # ---------- 升级策略 ----------
  escalation:
    enabled: false
    rules:
      - scene: ORDER_CREATE_FAIL
        consecutive: 5
        within: 10m
        upgrade-to: [sms4j]
        reset-on-success: true
    reset-scenes:                         # 显式恢复信号（§9.6）
      ORDER_CREATE_RECOVERED: ORDER_CREATE_FAIL

  # ---------- Web 层集成 ----------
  web:
    exception-handler:
      enabled: false                      # ★ 默认关，避免噪声（§8.2）
      scene: UNHANDLED_WEB_EXCEPTION
      level: ERROR
      exclude-exceptions:
        - org.springframework.web.bind.MethodArgumentNotValidException
      include-request-info: true

  # ---------- SpEL ----------
  spel:
    allow-method-invocation: false        # ★ 安全默认（§8.3）
    allowed-methods: []                   # 白名单，如 "java.lang.String#length"

  # ---------- 脱敏 ----------
  mask:
    enabled: true
    before-audit: true                    # 落库前脱敏
    before-send: false                    # ★ 发第三方前默认不脱敏（否则短信内容不可读）
    fields: [phone, email, idCard, bankCard]
    custom-rules: []

  # ---------- 一键全局静默 ----------
  global-mute:
    enabled: false                        # ★ 运维半夜救命的按钮，可由 Admin 运行时改
    mute-critical: false                  # 默认 CRITICAL 也静默（§9.2 ①），设 false 可保留

  # ---------- 可观测 ----------
  metrics:
    enabled: true
    prefix: notifyx
  actuator:
    endpoint:
      enabled: true
      recent-audit-limit: 20
  tracing:
    enabled: true                         # 从 MDC / Micrometer Tracing 取 traceId
    mdc-keys: [traceId, spanId]

  # ---------- 静态门面 ----------
  static-holder:
    enabled: false                        # ★ 默认关（§6.7），老系统静态工具类才开

  # ---------- 启动诊断 ----------
  diagnostics:
    banner: true                          # 启动打印能力表格
    fail-fast: false                      # ★ 必须 false，缺中间件不得启动失败（承诺 P1）
```

### 14.2 配置项优先级与合并规则

多个配置来源同时存在时，优先级从高到低：

```
① 代码显式设置（NotifyEvent 字段，如 event.silentWindow）
② Admin 下发的场景配置（configVersion 高者优先）
③ application-{profile}.yml
④ application.yml
⑤ NotifyxProperties 的字段默认值
```

**合并规则（关键，容易做错）**：

| 字段类型 | 合并方式 |
| --- | --- |
| 标量（level、silent-window） | 高优先级**覆盖** |
| 列表（channels、receivers） | 高优先级**整体替换**，不追加 |
| Map（scenes、rules） | **按 key 深度合并**：Admin 有的 scene 覆盖 YAML 的，Admin 没有的保留 YAML 的 |

**为什么列表不做追加**：追加语义会让「我想只用钉钉」变成「钉钉 + 邮件」，与用户直觉相反，且难以排查。整体替换 + 文档明确说明，是更可控的选择。

**为什么 Map 要深度合并**：Admin 通常只管理核心场景，一些临时调试场景写在 YAML 里。整体替换会导致 YAML 场景全部失效。**这个合并语义必须在启动诊断里输出**（「场景来源：Admin 21 个，YAML 2 个」），否则用户会困惑配置为什么没生效。

### 14.3 配置元数据（IDE 自动补全）

编写 `additional-spring-configuration-metadata.json`，让 IDEA 里有补全与说明：

```json
{
  "properties": [
    {
      "name": "notifyx.async.rejected-policy",
      "type": "io.github.pkyit.notifyx.core.enums.RejectedPolicy",
      "defaultValue": "drop-lowest",
      "description": "队列满时的拒绝策略。drop-lowest=丢弃优先级最低的任务（推荐，保住 CRITICAL 告警）；drop-newest=丢弃新任务。禁用 caller-runs（会阻塞业务线程）。"
    },
    {
      "name": "notifyx.dedupe.store",
      "type": "java.lang.String",
      "defaultValue": "auto",
      "description": "去重存储。auto=自动探测（有 Redis 用 Redis，否则内存）；memory=强制内存（多实例下会重复发）；redis=强制 Redis（不可用则降级为 memory 并告警）；jdbc=数据库去重（信创场景）。"
    },
    {
      "name": "notifyx.global-mute.enabled",
      "type": "java.lang.Boolean",
      "defaultValue": "false",
      "description": "★ 一键全局静默。开启后所有通知被拦截（含 CRITICAL，除非 mute-critical=false）。仅用于通知风暴止血，请务必设置自动恢复时间。"
    }
  ],
  "hints": [
    {
      "name": "notifyx.scenes.keys",
      "providers": [{ "name": "any" }],
      "values": [{ "value": "ORDER_CREATE_FAIL", "description": "示例场景码，请替换为你的业务场景" }]
    }
  ]
}
```

**这是「做得像官方 starter」的细节分水岭**。用户第一次在 IDEA 里敲 `notifyx.` 就看到带说明的补全列表，对项目的信任度会显著提升。所有配置项都必须有 description，**尤其是有安全或计费影响的项**（`global-mute`、`sms4j.max-retries`、`mask.before-send`）。

### 14.4 自动装配规范（Boot 3 关键细节）

**入口文件**（Boot 3 已废弃 `spring.factories`，这一点必须做对，否则 IDE 提示和懒加载都会出问题）：

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

内容：

```
io.github.pkyit.notifyx.autoconfigure.NotifyxAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.NotifyxActuatorAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.NotifyxWebAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.NotifyxAdminSyncAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.store.NotifyxRedisStoreAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.store.NotifyxJdbcStoreAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.channel.EmailChannelAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.channel.WebhookChannelAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.channel.DingTalkChannelAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.channel.WecomChannelAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.channel.FeishuChannelAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.channel.Sms4jChannelAutoConfiguration
io.github.pkyit.notifyx.autoconfigure.channel.LogChannelAutoConfiguration
```

**主配置类**：

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "notifyx", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(NotifyxProperties.class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE + 100)
public class NotifyxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean                        // ★ 用户自定义即无缝替换
    public NotifyKit notifyKit(ObjectProvider<NotifyChannel> channels,
                               ObjectProvider<EventFilter> filters,
                               NotifyxProperties props,
                               ObjectProvider<TemplateEngine> templateEngine,
                               ObjectProvider<DedupeStore> dedupeStore,
                               ObjectProvider<RateLimiter> rateLimiter,
                               ObjectProvider<FailureStore> failureStore,
                               ObjectProvider<AuditRecorder> auditRecorder,
                               ObjectProvider<MeterRegistry> meterRegistry) {
        return NotifyKitBuilder.create()
                .channels(channels.orderedStream().toList())
                .filters(filters.orderedStream().toList())
                .properties(props)
                .templateEngine(templateEngine.getIfAvailable(SimpleTemplateEngine::new))
                .dedupeStore(dedupeStore.getIfAvailable(MemoryDedupeStore::new))
                // ... 每个 SPI 都有 no-op 兜底（承诺 P1）
                .build();
    }
}
```

**渠道自动配置的一律模式**：

```java
@AutoConfiguration(after = NotifyxAutoConfiguration.class)
@ConditionalOnClass(name = "jakarta.mail.Transport")              // ★ 用 name 而非 Class，避免类加载
@ConditionalOnProperty(prefix = "notifyx.channels.email", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(name = "notifyxEmailChannel")
public class EmailChannelAutoConfiguration {
    @Bean(name = "notifyxEmailChannel")
    public NotifyChannel emailChannel(NotifyxProperties props, NotifyxHttpClient http) {
        return new EmailChannel(props.getChannels().getEmail(), http);
    }
}
```

**六条装配规范**：

1. **`@ConditionalOnClass` 用 `name` 属性而非 `value`**：`value = Transport.class` 会在类不存在时抛 `NoClassDefFoundError`，`name = "jakarta.mail.Transport"` 是字符串引用，安全。
2. **顺序明确**：`@AutoConfigureOrder` + `after`/`before` 声明（core > store > channel > actuator > admin-sync），避免 Bean 创建期竞态。
3. **`@ConditionalOnMissingBean` 全覆盖**：用户自己定义 `NotifyKit` / `TemplateEngine` / `DedupeStore` 即可无缝替换。MyBatis-Plus 那种「我写了就用我的，我不写就用你的」体验全靠这条。
4. **`fail-fast` 必须为 false**：任何 SPI 缺失都走 no-op 兜底，**绝不因缺配置/缺依赖抛异常导致启动失败**（承诺 P1）。唯一例外：`notifyx.diagnostics.fail-fast=true` 时用户显式要求严格模式。
5. **配置校验用 `@Validated` + `InitializingBean`**：非法配置（如 `queue-capacity <= 0`）在启动时打 **WARN 并修正为默认值**，而不是抛异常。理由同上——通知组件不该有让应用起不来的权力。
6. **启动诊断表**：`NotifyxDiagnostics` 在 `ApplicationReadyEvent` 时打印：

```
┌─────────────────────────────────────────────────────────────────┐
│ NotifyX 1.0.0  ·  mode=local  ·  capabilityTier=T1              │
├─────────────────────────────────────────────────────────────────┤
│ 渠道（3 启用 / 6 已注册）                                        │
│   ✅ email      smtp.qq.com:465 (SSL)                           │
│   ✅ dingtalk   oapi.dingtalk.com                               │
│   ✅ log        NOTIFYX_FALLBACK（兜底）                        │
│   ⚪ wecom      未启用（enabled=false）                          │
│   ⚪ feishu     未启用（enabled=false）                          │
│   ⚪ sms4j      未启用（classpath 未检测到 sms4j）               │
├─────────────────────────────────────────────────────────────────┤
│ 场景：23 个（Admin 21 + YAML 2）  模板：8 个  引擎：simple       │
├─────────────────────────────────────────────────────────────────┤
│ SPI 装配                                                         │
│   去重     redis（分布式，跨实例有效）                            │
│   限流     redis                                                 │
│   落盘     local-file  ~/.notifyx/pending                        │
│   审计     jdbc  → nx_audit                                      │
│   聚合     ⚠ 未启用（建议 T1 环境开启 notifyx.aggregate）        │
├─────────────────────────────────────────────────────────────────┤
│ ⚠ 降级与告警                                                     │
│   · 免打扰未启用，夜间 ERROR 告警会正常发送                       │
│   · 全局熔断已启用，阈值 5 次 / 30s                              │
│   · Actuator 端点：/actuator/notifyx                            │
└─────────────────────────────────────────────────────────────────┘
```

**这张表在排查问题时比文档管用十倍**。它一次性回答了「渠道为什么没发出去」「去重是不是降级了」「配置从哪来的」这三个最高频的问题。

---

## 第 15 章 可观测性

### 15.1 指标清单（Micrometer，前缀 `notifyx.`）

| 指标 | 类型 | 标签 | 用途与告警建议 |
| --- | --- | --- | --- |
| `notifyx.event.received` | counter | `scene`, `level`, `source`(L0-L4) | 事件入口总量。`source` 标签能看出接入方式分布 |
| `notifyx.send.total` | counter | `channel`, `scene`, `status` | ★ 核心指标。`status` ∈ success/fail_retryable/fail_non_retryable/discard/dedup/rate_limited/suppressed/circuit_open |
| `notifyx.send.duration` | timer | `channel`, `scene` | 渠道耗时。P95 > 3s 应告警 |
| `notifyx.queue.size` | gauge | — | 队列水位。**持续 > 80% capacity 是即将丢事件的信号** |
| `notifyx.queue.discarded` | counter | `reason`(queue-full/replaced), `level` | ★ 丢弃计数。**> 0 就应告警**，因为丢的是告警 |
| `notifyx.retry.attempts` | counter | `channel`, `attempt` | 重试分布。`attempt=3` 占比高说明渠道不稳定 |
| `notifyx.fallback.pending` | gauge | `store` | 待补偿数。持续增长说明渠道长期不可用 |
| `notifyx.fallback.dead` | gauge | — | 死信数。**> 0 应告警**，需人工介入 |
| `notifyx.fallback.overflow` | counter | — | 落盘目录溢出丢弃数（§9.9）。**> 0 是严重信号** |
| `notifyx.channel.circuit` | gauge | `channel`, `state` | 熔断状态（0=closed/1=half_open/2=open） |
| `notifyx.dedupe.size` | gauge | `store` | 去重缓存条目数，防内存泄漏 |
| `notifyx.dedupe.degraded` | gauge | — | ★ 是否降级为内存（1=降级）。Redis 故障的可见信号 |
| `notifyx.ratelimit.rejected` | counter | `scope`(scene/global), `scene` | 限流拦截量 |
| `notifyx.aggregate.merged` | counter | `scene` | 聚合压缩掉的事件数 |
| `notifyx.escalation.triggered` | counter | `scene`, `to` | 升级触发次数 |
| `notifyx.config.sync` | timer | `result`(success/fail/not_modified) | Admin 配置同步耗时 |
| `notifyx.config.version` | gauge | — | 当前配置版本号。**多实例版本不一致说明同步有问题** |
| `notifyx.sms.deferred` | counter | — | 短信号级限流延后数 |

**标签基数控制（重要）**：`scene` 标签的基数 = 场景数量（通常 < 100），可控。但**绝不能把 `receiver`、`bizId`、`eventId` 做成标签**——那会导致 Prometheus 时序爆炸（一个 10 万用户的应用会产生 10 万条时序）。Code Review 必查项。

**建议的 Prometheus 告警规则**（写进文档，反向闭环）：

```yaml
groups:
  - name: notifyx
    rules:
      - alert: NotifyxDiscardingEvents
        expr: increase(notifyx_queue_discarded_total[5m]) > 0
        for: 1m
        annotations:
          summary: "NotifyX 正在丢弃通知事件（队列满）"
      - alert: NotifyxChannelCircuitOpen
        expr: notifyx_channel_circuit{state="open"} == 2
        for: 2m
        annotations:
          summary: "NotifyX 渠道 {{ $labels.channel }} 已熔断"
      - alert: NotifyxDeadLetters
        expr: notifyx_fallback_dead > 0
        for: 5m
        annotations:
          summary: "NotifyX 存在 {{ $value }} 条死信通知，需人工介入"
      - alert: NotifyxDedupeDegraded
        expr: notifyx_dedupe_degraded == 1
        for: 5m
        annotations:
          summary: "NotifyX 去重已降级为内存模式，多实例下可能重复发送"
```

### 15.2 Health Indicator

```java
public class NotifyxHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // 默认只报「装配状态」，不做真实探活（避免产生流量与费用）
        // notifyx.actuator.health.probe-enabled=true 时才调用 channel.health()
    }
}
```

| 状态 | 判定 |
| --- | --- |
| `UP` | 至少一个渠道启用且未熔断 |
| `DEGRADED` | 部分渠道熔断 / 去重降级为内存 / Admin 同步失败但在用缓存 |
| `DOWN` | 所有渠道不可用，或全局静默开启 |
| `OUT_OF_SERVICE` | `notifyx.enabled=false` |

**默认 `probe-enabled=false` 的理由**：健康探测会被 K8s 每 10 秒调一次。如果每次都真发一条测试邮件/短信，一天就是 8640 条垃圾消息 + 真实费用。**必须默认关闭**，文档里说明「需要真实探活时开启，并配置合理的探测频率」。

**`DEGRADED` 状态的价值**：K8s 的 readiness probe 只看 UP/DOWN，`DEGRADED` 映射为 UP（不影响流量）但会在 `/actuator/health` 里显示详情。这符合「通知降级不应该导致业务实例被摘除」的原则。

### 15.3 Trace 贯通

```java
// 从 MDC / Micrometer Tracing 取，无依赖则跳过（不做硬依赖）
String traceId = mdcAdapter.get("traceId");
if (traceId == null && tracingProvider != null) {
    traceId = tracingProvider.currentTraceId();     // Micrometer Tracing，optional
}
```

- `traceId` / `spanId` 塞进 `NotifyEvent.tags` 与渲染后的通知内容。
- **通知内容里带 traceId 是排查效率的关键**：运维看到告警，复制 traceId 就能在 SkyWalking/Zipkin 里定位到完整调用链。这个功能的价值远超其 20 行实现成本。
- 没有 tracing 依赖时字段为 `null`，模板里渲染为空字符串（不是 `null` 字面量——`SimpleTemplateEngine` 必须处理 null 为 `""`）。

### 15.4 日志规范

| 场景 | 级别 | 内容 |
| --- | --- | --- |
| 启动诊断 | INFO | 能力表格（§14.4） |
| 能力降级 | **WARN**（只打一次，用 `AtomicBoolean` 防刷屏） | `"去重存储降级为内存：Redis 不可用（{}）。多实例部署下可能重复发送"` |
| 事件被拦截 | DEBUG | `"事件被去重拦截 scene={} dedupeKey={}"` |
| 事件被丢弃 | **WARN** | `"队列满，丢弃事件 scene={} level={} dedupeKey={}"` ← 丢了告警必须可见 |
| 渠道发送失败 | WARN（可重试）/ ERROR（不可重试） | `"渠道 {} 发送失败（第 {} 次）：{}"` |
| 渠道熔断 | **ERROR** | `"渠道 {} 连续 {} 次失败，熔断 {}s，流量切换至 {}"` |
| 落盘 | WARN | `"事件落盘待补偿 eventId={} path={}"` |
| 死信 | **ERROR** | `"事件进入死信，需人工介入 eventId={} scene={} reason={}"` |

**三条硬约束**：

1. **只用 SLF4J，禁止 `System.out`**（基础库往 stdout 写字节会污染容器日志）。
2. **不打印密钥**：任何含 `secret`/`password`/`accessKey`/`token` 的配置对象，`toString()` 必须打码（用统一的 `SafeToString` 工具）。
3. **payload 4KB 截断**：不打印完整堆栈外的超大 payload，避免日志爆炸。

---

## 第 16 章 安全与合规

### 16.1 模板注入防护

| 引擎 | 风险 | 对策 |
| --- | --- | --- |
| 内置 `${}` | 极低（只做字符串替换，无表达式求值） | 无需特殊处理，但**必须限制替换深度**（防 `${a}` → `${b}` → `${a}` 无限递归），最大 3 层 |
| FreeMarker | 高（`<#assign ex="freemarker.template.utility.Execute"?new()>${ex("rm -rf /")}` 可执行任意命令） | ★ **必须设置 `TemplateClassResolver.SAFER_RESOLVER`**，禁用 `?new`、`?api`；`setAPIBuiltinEnabled(false)` |
| Thymeleaf | 中（SpEL 表达式） | 禁用 `__${}__` 预处理，限制表达式对象 |
| SpEL（注解 `when`/`bizId`） | 高 | 默认 `SimpleEvaluationContext`（只读属性、**无方法调用**、无类型引用）+ 白名单（§8.3） |

**文档必须明确警告**：

> ⚠️ **不要把用户输入直接当模板内容。** 模板应来自配置或 Admin（可信来源），`params` 是数据（不可信）。若业务确实需要用户自定义模板，必须使用 `simple` 引擎（无表达式能力），且对模板内容做长度与字符集限制。

**FreeMarker 的 `SAFER_RESOLVER` 是最容易被漏掉的一条**——它不设置时，任何能写模板的人都能拿到 RCE。而 NotifyX 的 Admin 允许运维在网页上编辑模板，如果 Admin 被攻破，这就是从「改通知文案」到「拿下业务服务器」的完整攻击链。必须在 Code Review 清单里固化。

### 16.2 敏感数据脱敏

```java
public interface Masker {
    String maskPhone(String v);     // 13888888888 → 138****8888
    String maskEmail(String v);     // admin@example.com → a***@example.com
    String maskIdCard(String v);    // 110101199001011234 → 110***********1234
    String maskBankCard(String v);  // 6222021234567890123 → 6222***********0123
    Map<String, Object> maskParams(Map<String, Object> params);   // 按 key 名递归脱敏
    String maskText(String text);   // 正则兜底，对渲染后内容脱敏
}
```

**两个脱敏时机的取舍**（配置项 `mask.before-audit` / `mask.before-send`）：

| 时机 | 默认 | 理由 |
| --- | --- | --- |
| **落库前**（`before-audit`） | ✅ **true** | 审计库是长期留存的高价值目标，泄露影响面大。且审计只需要「发给了谁」的可辨识性，不需要完整号码 |
| **发第三方前**（`before-send`） | ❌ **false** | ⚠️ **必须默认关闭**：短信/邮件的接收人地址如果脱敏，消息根本发不出去；内容里的手机号脱敏会让通知失去意义 |

**`maskText` 正则兜底的必要性**：`params` 里可能有一个 `remark` 字段，值是自由文本，里面含身份证号。按 key 名脱敏抓不到它，正则兜底能覆盖。**但正则兜底默认关闭**（`mask.text-regex.enabled=false`），因为它有误伤风险（把订单号当身份证），且每次发送都跑正则有性能开销。开启时文档要说明代价。

**脱敏与「可追溯」的平衡**：审计表存脱敏后的接收人，但排查时需要知道「到底发给了谁」。解决方式：存 `receiver` 脱敏值 + `receiver_hash`（`sha256(receiver + salt)`）。既能按 hash 精确查询某个接收人的记录，又不暴露明文。salt 存在 Admin 侧加密配置里。

### 16.3 密钥管理

| 层面 | 方案 |
| --- | --- |
| **配置文件中的密钥** | 支持 Jasypt `${ENC(...)}`；国密 SM4 作 optional 模块（`notifyx-crypto-sm`） |
| **DB 中的密钥** | `nx_channel_config.config_json` **整体加密存储**，不是明文。加密密钥来自环境变量或 KMS，**不入库** |
| **配置快照输出** | 一律打码：`{"password": "******", "webhook": "https://oapi.dingtalk.com/robot/send?access_token=abcd****wxyz"}` |
| **日志** | `SafeToString` 工具统一拦截，含敏感 key 名的字段打码 |
| **Actuator 端点** | `/actuator/notifyx` 输出的配置快照必须脱敏；端点本身建议配 `management.endpoint.notifyx.access=read-only` 且不暴露到公网 |
| **Admin 前端** | 密钥字段显示为 `******`，编辑时「不修改则保持原值」（提交空值 ≠ 清空） |

**打码算法**：保留前 4 + 后 4，中间用 `****`。长度 ≤ 8 时全部打码。**不做「保留后 4 位」以外的更多暴露**——运维能通过前后 4 位确认「是不是配错了 key」，这已足够。

### 16.4 出站限速（防骚扰与防误操作）

三级出站闸门，**每一级都是独立配置**：

```
① 全局限流    notifyx.rate-limit.rules[global].limit     防通知风暴（配置错误）
② 场景限流    notifyx.rate-limit.rules[scene].limit      防单场景刷屏
③ 接收人限流  notifyx.rate-limit.receiver-limit.limit    防单人被骚扰（跨场景累计）
④ 号级限流    notifyx.channels.sms4j.receiver-rate-limit 运营商硬频控（§10.6）
```

第 ③ 级是本文档强调的重点：一个用户可能同时是 5 个场景的接收人，每个场景各自限流都通过了，结果这个人一小时收到 50 条消息。**跨场景的接收人总闸是必需的**，默认 30 条/小时。

**为什么这不只是防误操作，也是合规要求**：《通信短信息服务管理规定》要求未经同意不得发送商业短信，超频发送会被运营商封禁签名——影响的是整个应用的所有短信能力，不只是这一条。

### 16.5 审计留痕与合规

**留痕内容**：谁（哪个 `scene` / 哪段代码 / 哪个 `appId`）在什么时候（`created_at`/`sent_at`）通过什么渠道（`channel`）给谁（`receiver`）发了什么（渲染内容摘要）结果如何（`status`/`error_msg`）。

**等保三级相关要求对照**：

| 要求 | NotifyX 的落地 |
| --- | --- |
| 日志留存 ≥ 6 个月 | 在线库 30 天 + 超期归档到对象存储（§12.3），归档脚本随包提供 |
| 日志防篡改 | 审计表**只 INSERT 不 UPDATE**（`status` 变更用新增记录而非更新）；DB 账号最小权限（业务侧账号只有 INSERT + SELECT） |
| 操作可追溯 | Admin 的所有配置变更记录操作审计（操作者、时间、变更前后 JSON diff） |
| 敏感信息保护 | 接收人脱敏存储 + `receiver_hash`（§16.2） |

**「审计表只 INSERT 不 UPDATE」的理由**：允许 UPDATE 就意味着允许篡改历史。重试时不改原记录，而是新增一条 `attempts=2` 的记录，通过 `event_id` 关联。这样能完整还原「第一次超时、第二次成功」的过程，且历史不可篡改。代价是行数增加（约 1.3 倍），可接受。

### 16.6 HTTP 客户端抽象（信创与安全的双重要求）

```java
package io.github.pkyit.notifyx.core.http;

/**
 * 内部 HTTP 门面。★ 不绑死任何 HTTP 客户端实现。
 *
 * @since 1.0
 */
public interface NotifyxHttpClient {
    HttpResponse post(HttpPayload payload) throws IOException;
    HttpResponse get(String url, Map<String, String> headers) throws IOException;
}
```

| 实现 | 依赖 | 何时用 |
| --- | --- | --- |
| `JdkHttpClient` | **零依赖**（JDK 11+ 自带） | ★ **默认** |
| `OkHttpClient` | okhttp（optional） | 用户已有 OkHttp 连接池时 |
| `RestTemplateClient` | spring-web（optional） | 需要走用户统一的 `RestTemplate`（含统一代理/证书配置） |

**为什么不用 WebClient**：WebClient 强依赖 Reactor Netty，而 Netty 在某些信创内核上有已知问题（epoll 相关），且会把响应式编程模型引入一个本质同步的组件。**默认 JDK HttpClient 是零依赖 + 信创友好的最优解**。

**统一的超时与代理配置**（安全要求）：

```yaml
notifyx:
  http:
    client: jdk                    # jdk | okhttp | rest-template
    connect-timeout: 3s            # ★ 必须有默认超时，绝不无限等待
    read-timeout: 5s
    follow-redirects: false        # ★ 默认不跟随重定向（防 SSRF）
    max-response-size-kb: 64       # ★ 响应体上限，防内存打爆
    proxy:
      host:                        # 内网环境需要代理出网时配置
      port:
    ssl:
      trust-all: false             # ★ 默认 false，设 true 时启动打 ERROR 警告
      custom-truststore:           # 信创环境自签证书场景
```

**SSRF 防护**：`follow-redirects=false` + webhook URL 白名单（`notifyx.channels.webhook.allowed-hosts`）。理由：Admin 允许配置任意 webhook URL，如果 Admin 被攻破，攻击者可以让 NotifyX 向 `http://169.254.169.254/`（云元数据服务）或内网地址发请求，造成 SSRF。**默认关闭重定向 + 可选的 URL 白名单是必要的纵深防御。**

**`trust-all=true` 的处理**：信创内网自签证书场景确实需要它，但不能默认开。实现上：允许配置，但启动诊断里打一条 **ERROR 级**警告（`"⚠ notifyx.http.ssl.trust-all=true 已启用，TLS 证书校验被禁用，存在中间人攻击风险。生产环境请配置 custom-truststore"`）。

### 16.7 权限边界声明

**NotifyX 不做业务鉴权**，只做三件事：发送、留痕、自身接口的访问控制。

| NotifyX 负责 | NotifyX 不负责 |
| --- | --- |
| Admin 接口的 HMAC 签名鉴权 | 业务系统的用户登录与权限 |
| Admin 内部的角色/租户数据隔离 | 「这个用户能不能看到这条订单」 |
| 审计留痕 | 业务操作的审计（那是业务系统的事） |
| 出站限速防骚扰 | 用户是否同意接收通知（订阅管理是业务侧职责） |

**边界划清才能保持小**。每接一个「顺便也做一下」的需求，项目就离失控近一步。

---

## 第 17 章 工程规范与质量门禁

### 17.1 技术栈基线

| 项 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17**（编译目标 17） | 实测矩阵：Temurin / 华为毕昇 / 腾讯 Kona / 阿里 Dragonwell / 龙芯 Loongson |
| Spring Boot | **3.5.16** | property 化，见 ADR-009 |
| Maven | 3.9+ | 用 `maven-enforcer-plugin` 强制 |
| MyBatis-Plus | 3.5.x | 仅 store/admin 模块 |
| sms4j | 3.3.5 | optional |
| JUnit 5 + AssertJ + Mockito | 最新稳定 | 单测 |
| WireMock | 3.x | 渠道契约测试 |
| Testcontainers | 最新稳定 | profile 激活，不进默认构建 |
| ArchUnit | 1.x | 依赖纪律断言（§5.3） |

### 17.2 编码约定

| 约定 | 要求 | 理由 |
| --- | --- | --- |
| **不用 Lombok** | 全项目禁用 | ADR-011 |
| **不可变优先** | `record` / `List.of()` / `Collections.unmodifiable*` | 并发安全，无意外修改 |
| **`params` 深拷贝** | 构造期防御性拷贝 | 防外部篡改导致指纹漂移 |
| **异常体系** | 统一 `NotifyException`（非受检），渠道异常包装为 `ChannelException` 子类并**保留 cause** | §6.6，异常分类决定重试正确性 |
| **日志** | 只用 SLF4J；禁 `System.out`；不打密钥；payload 4KB 截断 | §15.4 |
| **API 标注** | 公共 SPI 标 `@Since("x.y")`；实验性标 `@Incubating` | 破坏性变更走 major 版本 |
| **Javadoc** | 所有 public 类型与方法必须有 Javadoc，含 `@since`、`@param`、`@throws` | javadoc.jar 发布要求；开源项目的门面 |
| **null 处理** | public API 参数用 `Objects.requireNonNull(..., "参数名")`；返回值明确是否可为 null（Javadoc 标注） | 快速失败优于隐蔽 NPE |
| **魔法值** | 一律提为常量或配置项 | 错误码分类表（§10.3）尤其如此 |
| **线程安全标注** | 每个 public 类的 Javadoc 首行标注 `@implNote 线程安全` 或 `非线程安全` | 并发组件必须有明确契约 |

### 17.3 质量门禁（CI 不过即失败）

```
mvn verify -P quality-gate
├── Spotless (palantir-java-format)      格式不符 → 失败（可 --apply 自动修）
├── Checkstyle                           规则集：无 star import、方法 ≤ 80 行、圈复杂度 ≤ 15
├── SpotBugs + findsecbugs 插件          ★ findsecbugs 必开（查 SSRF、注入、硬编码密钥）
├── ArchUnit                             §5.3 三条铁律
├── JaCoCo                               行覆盖率 ≥ 80%，core 模块 ≥ 90%
├── maven-enforcer                       bannedDependencies（禁 fastjson 1.x、禁 log4j 1.x）
│                                        + dependencyConvergence（版本收敛）
├── dependency:tree 断言脚本             starter 的 compile classpath 洁净度（§5.3 铁律 1）
└── OWASP Dependency-Check               CVSS ≥ 7 的已知漏洞 → 失败
```

**覆盖率门禁的分层设计**：全局 ≥ 80%，但 `notifyx-core` ≥ 90%。理由：core 是指纹算法、限流算法、模板引擎所在地，这些代码的 bug 会以「去重静默失效」的形式表现，极难排查。**测试投入应该按「bug 的隐蔽程度」分配，而不是按代码行数平均分配。**

### 17.4 CI 矩阵

```yaml
# .github/workflows/ci.yml 核心矩阵
strategy:
  matrix:
    java:    [17, 21]                    # 21 做前向兼容验证
    boot:    [3.5.16]
    include:
      - java: 17
        boot: 4.0.x                      # ★ boot4 profile：只编译 + 装配验证
        profile: boot4
      - java: 17
        jdk:   bisheng                   # 华为毕昇
        profile: domestic-jdk
      - java: 17
        jdk:   kona                      # 腾讯 Kona
        profile: domestic-jdk
      - java: 17
        db:    dm8                       # 达梦（Testcontainers 或 CI 服务）
        profile: domestic-db
      - java: 17
        db:    kingbase
        profile: domestic-db
```

**`boot4` profile 的验证范围**（不要求全量通过）：
1. `mvn compile` 通过（API 断裂点可见）；
2. `ApplicationContextRunner` 验证 `NotifyxAutoConfiguration` 能正常装配；
3. `AutoConfiguration.imports` 文件格式仍被识别；
4. `NotifyxJson` 门面的 Jackson 序列化路径可用。

这四条覆盖了 90% 的 Boot 4 迁移风险，成本却只是一条 CI job。

**国产库 profile 的最小验证集**：建表（DDL 执行）+ 一条 INSERT + 一条带分页的 SELECT + 一次乐观锁 UPDATE。四步跑通即算通过——不追求全量回归（那需要真实环境），但要证明「DDL 语法和方言适配是对的」。

### 17.5 GraalVM Native Image 的预留

core 零 Spring（ADR-003）已为 native image 打下基础，但还需要：

| 需求 | 措施 |
| --- | --- |
| `ServiceLoader` 反射 | 提供 `reflect-config.json`（`native-image` reachability metadata），列出所有 SPI 实现类 |
| 渠道 SDK 反射 | 各 `channel-*` 模块自带 metadata，用户引入即生效 |
| 序列化 | `NotifyEvent` 需注册到 `serialization-config.json` |
| 动态代理 | **不使用 JDK 动态代理**（AOP 由 Spring 处理，native 场景下 Spring AOT 会生成静态代理） |

**P0 不做，但代码上不做阻碍它的事**：不用 `Class.forName` 动态加载、不用运行时字节码生成。这样 P5+ 阶段补 metadata 就能支持，而不是重写。

---

## 第 18 章 测试策略

### 18.1 测试金字塔与模块分工

| 层级 | 覆盖内容 | 工具 | 占比 |
| --- | --- | --- | --- |
| **单元测试** | core 的算法（指纹、限流、模板、脱敏）、各 Filter 的判定逻辑 | JUnit 5 + AssertJ | 70% |
| **组件测试** | 责任链管线的端到端流转（用 mock channel）、AOP 切面行为、自动装配 | `ApplicationContextRunner` + Mockito | 20% |
| **契约测试** | 各渠道与外部 API 的报文格式与错误码分类 | **WireMock**（固化真实响应体为夹具） | 8% |
| **集成测试** | Redis / MQ / 国产库的真实交互 | Testcontainers（profile 激活） | 2% |

### 18.2 必须覆盖的高危用例（这些是「不测就一定会踩」的）

| # | 用例 | 验证点 | 对应章节 |
| --- | --- | --- | --- |
| T1 | **指纹稳定性** | 同样 params 但 `HashMap` 插入顺序不同 → 指纹必须相同 | §9.1 |
| T2 | **指纹跨进程一致** | 序列化为 JSON 再反序列化 → 指纹不变 | §9.1 |
| T3 | **堆栈不进指纹** | 同一异常在不同 lambda 序号下 → 指纹相同 | §9.1 |
| T4 | **去重槽位释放** | 发送失败后 → 下一次同指纹事件必须能触发 | §7.4 |
| T5 | **漏斗顺序** | 限流触发时，去重窗口不被污染 | §9.2 |
| T6 | **CRITICAL 旁路矩阵** | 逐个节点验证 §9.2 表格的每一格 | §9.2 |
| T7 | **Appender 防自递归** | NotifyX 自身日志 ERROR → 不产生新事件 | §8.1 |
| T8 | **AOP 不外抛异常** | 切面内 mock 抛 `RuntimeException` → 业务方法正常返回/原样抛原异常 | §8.3 |
| T9 | **SpEL 求值失败降级** | `when="#r.nonExistField.x"` → 放行 + warn，不抛异常 | §8.3 |
| T10 | **`-parameters` 缺失的检测** | 参数名退化为 `arg0` → 打出明确 warn | §8.3 |
| T11 | **队列满保 CRITICAL** | 灌满 4096 个 INFO，再投 1 个 CRITICAL → CRITICAL 保留，INFO 被丢 | ADR-008 |
| T12 | **CallerRuns 禁用** | 队列满时业务线程耗时 < 10ms | §9.11 |
| T13 | **零依赖启动** | classpath 只有 core+spring+starter → 上下文启动成功，能力表全为 memory | 承诺 P1 |
| T14 | **Redis 故障降级** | Testcontainers 停掉 Redis → 发送仍成功 + 降级 warn 只打一次 | §9.4 |
| T15 | **落盘容量保护** | 写满 `max-files` → 丢最旧 DEAD + `fallback.overflow` 指标 +1 | §9.9 |
| T16 | **优雅关闭不丢事件** | 队列有 100 个任务时关闭 → 全部发出或全部落盘，无静默丢失 | §9.10 |
| T17 | **短信不重试** | sms4j mock 超时 → 只尝试 1 次 | §9.8 |
| T18 | **渠道错误码分类** | 每个渠道的每个已知错误码 → 映射到正确的 `Status` | §10.3 |
| T19 | **FreeMarker RCE 防护** | 模板含 `?new()` → 抛异常，不执行命令 | §16.1 |
| T20 | **SSRF 防护** | webhook URL 指向 `169.254.169.254` → 被白名单拦截（开启时） | §16.6 |
| T21 | **乐观锁冲突** | 两实例同时补偿同一落盘记录 → 只有一个成功 | §9.9 |
| T22 | **配置深度合并** | Admin 有 scene A，YAML 有 scene B → 两者都生效 | §14.2 |
| T23 | **MQ 报文向后兼容** | 高版本报文（多一个字段）→ 低版本 consumer 正常处理 | §6.4 |
| T24 | **多接收人 fan-out 审计** | 3 个 receiver × 2 个 channel → 6 条审计记录 | §6.3 |

**T13 是本项目最重要的一个测试**：它是承诺 P1 的机器验证。任何 PR 破坏了「零依赖可启动」，CI 必须立刻失败。建议把它命名为 `ZeroDependencyStartupTest` 并放在 `notifyx-spring-boot3-starter` 的测试目录里。

### 18.3 契约测试的组织方式

```
notifyx-channel-dingtalk/src/test/
├── resources/wiremock/
│   ├── mappings/
│   │   ├── success.json                 # {"errcode":0,"errmsg":"ok"}
│   │   ├── sign-error.json              # {"errcode":310000,"errmsg":"sign not match"}
│   │   ├── rate-limit.json              # {"errcode":130101,"errmsg":"send too fast"}
│   │   └── group-dismissed.json         # {"errcode":400013}
│   └── __files/
└── java/DingTalkChannelContractTest.java
```

每个夹具都标注**采集日期与平台文档链接**。IM 平台 API 改版时（风险 R7），只需重新采集响应体、更新夹具，测试失败即发现变更。

### 18.4 sample 工程的定位

`notifyx-sample` 不是演示代码堆，而是**可执行的验收标准**：

```
notifyx-sample/
├── pom.xml                              # 只引 starter + email + webhook + dingtalk
├── src/main/resources/application.yml   # 完整但精简的配置，每行有注释
└── src/main/java/
    ├── SampleApplication.java
    ├── L0LogAlarmDemo.java              # 日志 marker 触发
    ├── L2AnnotationDemo.java            # @AlarmProtect / @NotifyOn
    ├── L4FacadeDemo.java                # notifyKit 一行调用
    ├── ZeroDependencyProfile.java       # ★ profile=zero：不配任何中间件，验证承诺 P1
    └── StormSimulationDemo.java         # ★ 模拟通知风暴，验证限流+聚合+熔断
```

**`StormSimulationDemo` 的价值**：它用一个接口在 1 秒内产生 10000 条同 scene 事件，让用户在自己的环境里亲眼看到「限流生效、聚合压成一条摘要、渠道没被打挂、业务接口 RT 没变化」。**防风暴能力靠文档说服不了人，靠跑一次就能。**

---

## 第 19 章 发布与版本策略

### 19.1 Maven Central 发布链路（P0 第一件事）

> **多数开源项目不是死于设计，是死于最后一步发不出去。** 因此发布链路必须在 P0 第一周就跑通，哪怕发的是一个只有 `notifyx-core` 空壳的 `0.1.0-SNAPSHOT`。

**前置准备清单**（每一项都可能卡几天）：

| # | 事项 | 说明 |
| --- | --- | --- |
| 1 | **groupId 权限** | `io.github.pkyit` 已具备（需求方确认）。需在 Central Portal 验证 namespace 归属（GitHub 账号关联） |
| 2 | **GPG 密钥** | 生成 + 上传到公钥服务器（`keyserver.ubuntu.com`）。⚠️ CI 里需要私钥，用 GitHub Secrets，**绝不入库** |
| 3 | **POM 必填元数据** | `name` / `description` / `url` / `scm` / `issueManagement` / `licenses` / `developers` —— 缺任何一项 Central 都会拒绝 |
| 4 | **javadoc.jar + sources.jar** | `maven-javadoc-plugin` + `maven-source-plugin`。⚠️ Javadoc 语法错误会导致构建失败，这是最常见的卡点 |
| 5 | **插件** | `central-publishing-maven-plugin`（新版，替代已废弃的 `nexus-staging-maven-plugin`） |
| 6 | **SNAPSHOT 仓库** | 先发 SNAPSHOT 验证全链路，再发 release |

**parent pom 的发布配置骨架**：

```xml
<plugin>
    <groupId>org.sonatype.central</groupId>
    <artifactId>central-publishing-maven-plugin</artifactId>
    <version>${central-publishing.version}</version>
    <extensions>true</extensions>
    <configuration>
        <publishingServerId>central</publishingServerId>
        <autoPublish>false</autoPublish>       <!-- ★ 手动确认后再发布，防误发 -->
        <waitUntil>published</waitUntil>
    </configuration>
</plugin>
```

`autoPublish=false` 是刻意的：Central 的 release **发布后不可撤回、不可覆盖**。留一个人工确认环节，避免把带 bug 的版本永久推给用户。

### 19.2 alias artifact 发布（ADR-001 的落地）

```xml
<!-- notifyx-spring-boot3-starter/pom.xml -->
<artifactId>notifyx-spring-boot3-starter</artifactId>
<dependencies>
    <dependency>
        <groupId>io.github.pkyit</groupId>
        <artifactId>notifyx-spring-boot-starter</artifactId>
        <version>${project.version}</version>
    </dependency>
</dependencies>
<!-- 不含代码，只是一个指向主坐标的传递依赖壳 -->
```

**为什么用「依赖壳」而不是「同一 jar 双坐标」**：
1. 实现简单，不需要 hack `maven-install-plugin`。
2. IDE 的依赖树里能清楚看到 `-boot3-` → 主坐标的关系，用户自然理解。
3. 未来废弃 alias 时，只需停止发布该模块，主坐标不受影响。

代价：用户引 alias 会多一层传递依赖。**可接受**，且 README 明确引导用户直接用主坐标。

### 19.3 语义化版本策略

| 版本 | 变更类型 | 示例 |
| --- | --- | --- |
| **major**（`2.0.0`） | 破坏性变更：删除/重命名 public API、SPI 签名变更、配置项语义变更 | 移除 `@Incubating` 标注的接口 |
| **minor**（`1.1.0`） | 新增功能：新渠道、新 SPI（带默认实现）、新配置项（有默认值） | 新增 `notifyx-channel-slack` |
| **patch**（`1.0.1`） | Bug 修复、依赖升级、文档 | 修复去重槽位未释放 |
| **milestone** | `1.0.0-M1` / `1.0.0-RC1` | P0~P5 阶段产出 |

**版本号与 Boot 的关系**（关键决策）：**NotifyX 的版本号不跟随 Spring Boot**。`1.x.y` 表示 NotifyX 自身的 API 稳定性；支持的 Boot 版本范围在 POM 的 `spring-boot.version` property 与 README 兼容矩阵里声明。

理由：如果 NotifyX 版本跟 Boot 绑定，用户升 Boot 就要升 NotifyX，两个不相关的变更被耦合在一起，排查问题时无法区分是谁引入的。

**兼容矩阵（README 必备）**：

| NotifyX | Boot 3.2 | Boot 3.3 | Boot 3.4 | Boot 3.5.16 | Boot 4.0 | JDK 17 | JDK 21 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1.0.x | ✅ | ✅ | ✅ | ✅ **（基线）** | 🧪 编译验证 | ✅ | ✅ |
| 1.1.x | ✅ | ✅ | ✅ | ✅ | ⚠️ 计划中 | ✅ | ✅ |

CI 跑 Boot 3.2 / 3.3 / 3.4 / 3.5.16 四套构建（讨论稿要求），确保「最低支持 3.2」不是空话。

### 19.4 文档交付物清单

| 文档 | 位置 | 必要性 |
| --- | --- | --- |
| **README**（中英双语 quickstart） | 仓库根 | ★★★ 门面，决定用户是否留下 |
| **配置字典** | `docs/configuration.md` | ★★★ 每个字段的含义/默认值/是否必填/影响 |
| **SPI 扩展指南** | `docs/spi-guide.md` | ★★★ 「如何 30 分钟写一个新渠道」，生态繁荣的关键 |
| **多实例部署选型表** | `docs/deployment.md` | ★★★ §11.5 的内容，最容易踩的坑 |
| **信创适配说明** | `docs/domestic.md` | ★★ 含 §12.5 的诚实声明 |
| **FAQ** | `docs/faq.md` | ★★ 按 issue 积累 |
| **升级指南** | `docs/upgrade.md` | ★★ 每个 minor 版本的 breaking change |
| **CHANGELOG.md** | 仓库根 | ★★★ Keep a Changelog 格式 |
| **架构设计书** | `docs/design.md` | ★ 本文档 |
| **贡献指南** | `CONTRIBUTING.md` | ★★ 含本地构建、测试、PR 规范 |

**README 首屏必须包含的四件事**（决定 star 转化率）：
1. 一句话说清「这是什么、解决什么」（§1.1 的四个「无法」）；
2. **10 分钟快速开始**：3 行 Maven + 5 行 YAML + 1 行 Java；
3. 与 Prometheus 的关系（避免「这不是重复造轮子吗」的第一反应）；
4. Boot 3.5.16 EOL 的说明（ADR-009 第 5 条）。

---

## 第 20 章 非功能性需求（NFR）量化指标

> 没有量化指标的 NFR 是许愿。以下是可测试、可验收的目标值。

### 20.1 性能

| 指标 | 目标值 | 测量方式 | 备注 |
| --- | --- | --- | --- |
| **业务线程占用时长**（`notifyKit.send()` 调用返回耗时） | **P99 < 1ms** | JMH 基准测试 | ★ 最重要的指标。含事件构造、指纹计算、入队 |
| 指纹计算耗时 | P99 < 50μs | JMH | sha256 + 排序，params ≤ 20 个字段 |
| 端到端投递延迟（入队 → 渠道发出） | P95 < 200ms（不含渠道 RT） | 指标 `send.duration` | 队列无积压时 |
| 单实例吞吐 | ≥ 5000 事件/秒（入队） | JMH | 渠道发送受外部限制，不计入 |
| 队列满载时的业务线程耗时 | **P99 < 5ms** | JMH | 含 `drop-lowest` 的 O(n) 扫描（ADR-008） |
| 内存占用（空载） | < 8MB 堆 | JOL + 实际测量 | T0 梯度 |
| 内存占用（去重缓存 10 万条） | < 60MB | 实测 | Caffeine，受 `max-entries` 约束 |

### 20.2 可用性

| 指标 | 目标值 |
| --- | --- |
| **通知链路故障对业务的影响** | **零**（业务线程不阻塞、不抛异常） |
| 应用启动成功率（缺任意中间件） | **100%**（承诺 P1，T13 用例验证） |
| 启动额外耗时 | < 300ms（T0 梯度） |
| 事件不丢失率 | ≥ 99.9%（失败必落盘，除磁盘满与 JVM 崩溃） |
| at-least-once 保证 | 是（配合幂等键） |
| exactly-once | **不保证**（§2.3 非目标） |

### 20.3 可维护性

| 指标 | 目标值 |
| --- | --- |
| 新增一个 HTTP 类渠道的代码量 | **< 80 行**（继承 `AbstractHttpChannel`） |
| core 模块单测覆盖率 | ≥ 90% |
| 全局单测覆盖率 | ≥ 80% |
| 圈复杂度（单方法） | ≤ 15 |
| starter 的 compile 依赖数 | ≤ 25 个 artifact |
| 配置项数量 | ≤ 120 个（**刻意设上限**，防配置膨胀） |

**配置项上限是一条真实的架构约束**：配置项每增加一个，用户的心智负担和项目的测试矩阵都会增长。设一个上限（120）能强迫在设计新配置时问一句「这个能不能用默认值消化掉」。

### 20.4 兼容性

| 维度 | 目标 |
| --- | --- |
| Spring Boot | 3.2 / 3.3 / 3.4 / 3.5.16 全通过；4.0 编译期验证 |
| JDK | 17（基线）/ 21（前向）；国产 JDK（毕昇/Kona/Dragonwell/Loongson）冒烟通过 |
| 数据库 | MySQL 8 / 达梦 DM8 / 人大金仓 KingbaseES V8 / openGauss 冒烟通过 |
| 应用服务器 | 内嵌 Tomcat（默认）；Servlet 通用 API，理论上兼容 TongWeb / BES / Apusic（P5 实测） |
| 序列化 | Jackson 2.x（默认）；Jackson 3 通过 `NotifyxJson` 门面隔离（ADR-009） |

---

## 第 21 章 路线图与里程碑

### 21.1 阶段规划

| 阶段 | 周期 | 内容 | 产出 | 验收标准 |
| --- | --- | --- | --- | --- |
| **P0** | 4 周 | core + spring + autoconfigure + starter + BOM；门面 `NotifyKit`；责任链管线（路由/去重/限流骨架）；`@AlarmProtect`/`@NotifyOn` + AOP；Logback Appender；**email + webhook + log 三渠道**；异步优先级队列；CI + 发布脚本 | **0.1.0** | ① 能发到 Maven Central；② T13 零依赖启动测试通过；③ sample 三种接入方式可跑通 |
| **P1** | 3 周 | 钉钉/企微/飞书；去重/限流/免打扰/聚合/升级/熔断全量；失败落盘补偿；优雅关闭；actuator + metrics + health；启动诊断表 | **0.2.0** | ① `StormSimulationDemo` 验证防风暴有效；② T1–T24 高危用例全部覆盖；③ 生产可用 |
| **P2** | 3 周 | sms4j 适配 + 号级限流；MP 审计落库（mysql/dm/kingbase/opengauss DDL）；Redis store（分布式去重/限流）；FreeMarker 引擎 | **0.3.0** | ① 国产库 CI profile 通过；② T1（有 Redis）梯度全能力验证 |
| **P3** | 4 周 | MQ 三总线（kafka/rabbitmq/rocketmq）；`notifyx-consumer` 独立服务；幂等键去重；报文 schemaVersion 兼容 | **0.4.0** | ① T2 梯度验证；② 多实例重复发送问题在 MQ 模式下根治 |
| **P4** | 5 周 | `notifyx-admin` 控制台（Vue3 + Element Plus）；配置 pull 同步（含长轮询）；HMAC 客户端鉴权；接入中心；在线试发；一键全局静默 | **0.5.0** | ① 无 Admin 时 YAML 兜底验证；② Admin 挂掉时发送不受影响 |
| **P5** | 4 周 | 信创验证（毕昇/Kona/DM/金仓/TongWeb）；国密 SM4 optional 模块；多租户；Boot 4 profile 完整化；GraalVM metadata | **1.0.0-RC → 1.0.0 GA** | ① 兼容矩阵全部打勾；② 至少一个真实生产项目跑满 30 天 |

**总计约 23 周（约 5.5 个月）到 1.0 GA**。

### 21.2 P0 的可独立提交序列

每一步都能独立提交、独立验证，避免「大爆炸式合并」：

```
1. 仓库骨架：BOM + 5 个空模块 + parent pom + Spotless/Checkstyle/SpotBugs + CI workflow
   └─ 验收：mvn verify 通过，CI 绿
2. ★ 发布链路：GPG + central-publishing-plugin + POM 元数据 + javadoc/source jar
   └─ 验收：notifyx-core 0.1.0-SNAPSHOT 成功推到 Central
3. core 模型：NotifyEvent / Level / NotifyContext / SendResult / 异常体系 / 指纹算法
   └─ 验收：T1–T3 指纹测试通过
4. core SPI：NotifyChannel / EventFilter / TemplateEngine / DedupeStore / RateLimiter / FailureStore / AuditRecorder / Masker
   └─ 验收：全部有 no-op 实现，单测覆盖
5. 责任链管线 + 异步优先级队列
   └─ 验收：T11、T12、T16 通过
6. notifyx-spring：AOP 切面 + SpEL 求值器（含缓存与降级）+ Spring Event 桥接 + MDC
   └─ 验收：T8、T9、T10 通过
7. autoconfigure：AutoConfiguration.imports + 条件装配 + 配置元数据 + 启动诊断
   └─ 验收：T13 零依赖启动通过 ★
8. channel-email + channel-webhook + channel-log
   └─ 验收：WireMock 契约测试 + sample 真实发信
9. Logback Appender
   └─ 验收：T7 防自递归通过
10. notifyx-sample + README + 配置字典
    └─ 验收：新用户按 README 10 分钟跑通
11. 发布 0.1.0
```

**第 2 步放在这么前面是刻意的**（讨论稿已指出，本文档强调）：发布链路涉及 GPG 密钥、Central 账号权限、POM 元数据校验、javadoc 语法，每一项都可能卡住几天。等到功能都写完再发现发不出去，是开源项目最常见的悲剧。

### 21.3 P4 并行开发的说明

讨论稿建议「并行开 admin 的前端仓库」。本文档调整为：**Admin 后端与前端同仓库（`notifyx-admin` + `notifyx-admin/ui`），但可在 P1 之后提前启动**。

理由：
1. **同仓库**保证 API 契约与前端调用同步演进，避免跨仓库版本错配。
2. **提前启动**（而非等到 P4）是因为控制台是「从工具库变成产品」的分水岭，且 Admin 的 API 契约会反向影响 SDK 的配置模型设计——晚做会导致 SDK 侧返工。

建议时点：**P2 期间启动 Admin 的场景配置 + 发送记录两个模块**（此时 SDK 的配置模型已稳定），其余功能按 P4 计划。

---

## 第 22 章 风险登记册

| ID | 风险 | 概率 | 影响 | 综合 | 缓解措施 | 应急预案 |
| --- | --- | --- | --- | --- | --- | --- |
| **R1** | **Boot 3.5.x 已 EOL**，用户升 Boot 4 后 starter 失效 | **高**（已发生） | 高 | 🔴 严重 | ADR-009 五条：BOM property 化、`boot4` CI profile、主坐标不带版本号、`NotifyxJson` 门面隔离 Jackson、README 首屏声明 | Boot 4 破坏性变更集中在 autoconfigure 层，SDK 的 core/channel 不受影响；发 `2.0.0` 适配版 |
| **R2** | **多实例重复发送** | **高** | 高（短信重复计费） | 🔴 严重 | §11.5 选型表写进 README；短信渠道在内存去重 + 多实例下打启动 WARN | 引导用户切 Redis 去重或 MQ 模式；提供 `@NotifySingleInstance`（ShedLock）作最后手段 |
| **R3** | **通知风暴**（配置错误导致刷屏） | 中 | 高（渠道账号被封、真实费用） | 🔴 严重 | 四级出站闸门（§16.4）+ 一键全局静默 + 全局限流发汇总摘要 | 运维通过 Admin 一键静默；极端情况 `notifyx.enabled=false` 重启 |
| **R4** | **sms4j 传递依赖冲突**（fastjson 等） | 中 | 中 | 🟡 关注 | `<exclusions>` 显式排除 + `maven-enforcer` 的 `bannedDependencies` + CI 依赖树断言 | 渠道模块支持 HTTP 直连模式（自己实现厂商签名）作为退路 |
| **R5** | **过度设计**（SPI 太多，为扩展而扩展） | 中 | 中 | 🟡 关注 | ADR-010 准入守则：有第二个真实实现才抽象；`ReceiverResolver`/`CircuitBreaker` 已诚实标 `@Incubating` | 1.0 前清理未达标的 SPI，降级为策略枚举 |
| **R6** | **Admin 范围蔓延**（变成消息中台/工作流引擎） | 中 | 高（项目失焦） | 🟡 关注 | §2.3 非目标清单 + §13.2 MVP/后续分栏 + 每个新功能必须回答「不做会怎样」 | 守住「配置 + 记录 + 大盘」三件事 |
| **R7** | **IM 平台 API 改版**（钉钉/企微/飞书） | **高**（必然发生） | 中 | 🟡 关注 | WireMock 契约测试固化响应体（§18.3）；错误码分类表提为常量；域名可配 | 发 patch 版本；受影响期间自动走 `fallback-chain` 到 email/log |
| **R8** | **Maven Central 发布链路卡住** | 中 | 高（项目无法交付） | 🟡 关注 | §21.2 第 2 步提前到 P0 第一周；先发 SNAPSHOT 验证全链路 | GPG/权限问题排查手册写进 CONTRIBUTING |
| **R9** | **国产库适配「假支持」** | 中 | 中 | 🟡 关注 | CI 国产库 profile 冒烟（建表+CRUD+分页+乐观锁）；§12.4 方言雷区对照表；§12.5 诚实声明 | 提供各库 DDL 独立脚本；用户反馈问题快速出 patch |
| **R10** | **落盘把磁盘写满拖死业务** | 低 | **高**（违反承诺 P2） | 🟡 关注 | §9.9 容量上限（100MB/10000 文件）+ 溢出丢最旧 DEAD + `fallback.overflow` 指标 | 指标告警；极端情况 `fallback.store=none` 关闭落盘 |
| **R11** | **Logback Appender 自递归风暴** | 低 | **高**（秒级产生数万事件 + 真实短信费用） | 🟡 关注 | §8.1 双重防护：logger name 过滤 + `ThreadLocal` 重入保护；T7 用例 | 全局静默按钮 |
| **R12** | **SpEL 注入 / FreeMarker RCE** | 低 | **高**（安全事件） | 🟡 关注 | §16.1：`SimpleEvaluationContext` 默认无方法调用；FreeMarker `SAFER_RESOLVER`；findsecbugs 进 CI | 发安全公告 + patch 版本；提供 `notifyx.spel.enabled=false` 全局关闭 |
| **R13** | **单人维护的可持续性**（开源项目常见病） | **高** | 中 | 🟡 关注 | 文档完备（降低答疑成本）；SPI 设计便于社区贡献渠道；`CONTRIBUTING.md` | 明确项目维护状态；必要时归档并推荐替代方案 |
| **R14** | **审计表膨胀** | 中 | 中 | 🟡 关注 | §12.3 五种治理手段（保留期/采样/批量写/分区/归档） | 提供归档脚本；`audit.store=none` 降级 |

### 22.1 风险优先级说明

**R1、R2、R3 是三个必须在 1.0 前有明确答案的风险**：

- **R1** 是外部既成事实，唯一能做的是提前隔离。ADR-009 的五条措施如果 P0 不做，等 Boot 4 用户来提 issue 就晚了。
- **R2** 会直接造成用户金钱损失（短信重复计费），这是最容易让用户对项目失去信任的失效模式。
- **R3** 是通知类系统的固有风险——**再好的限流也挡不住配置错误**，所以「一键全局静默」这个功能不是可选项，是必需品。

**R13 需要诚实面对**：单人维护的开源项目，最大的风险不是技术，是维护者的时间与热情。对策不是「承诺永久维护」，而是**把项目做成别人容易接手的形态**：文档完备、SPI 清晰、测试充分、架构决策有据可查（本文档的 ADR 与复评触发条件正是为此）。

---

## 第 23 章 评审修订清单

> 本章逐条列出本文档对讨论稿 v0.3 的修正与补充。每条给出「原设计 / 问题 / 修正 / 影响面」，便于评审时逐条确认。

| # | 原设计（讨论稿 v0.3） | 问题 | 本文档的修正 | 影响面 |
| --- | --- | --- | --- | --- |
| **1** | 异步队列用 `discard_oldest` 拒绝策略 | `LinkedBlockingQueue` 下「最旧」= 最早入队 = 往往是**第一个发现的 CRITICAL 告警**，会被精确丢掉，反而保住后涌入的重复低价值事件 | 改用 `PriorityBlockingQueue`（Level 降序 + 时间升序）+ 自定义 `drop-lowest` 策略，丢弃优先级最低者并计数、落盘（**ADR-008**） | `notifyx-spring` 一个类 + 配置项 `rejected-policy` 取值变更 |
| **2** | CRITICAL 只说明「直通免打扰与限流」 | **未定义去重环节的旁路策略**。完全旁路 → 持续崩溃每秒发一条短信，账号被运营商封；完全走去重 → 故障恶化 2 小时只收到 1 条 | 补充完整的 **CRITICAL 旁路矩阵**（8 个漏斗节点 × 4 个级别），去重环节采用「窗口减半且不超过 60s」的折中（**§9.2**） | core 的 Filter 实现 + 配置项 |
| **3** | `DedupeStore` 只有 `tryAcquire` | 发送失败后不释放槽位 → 「第一条失败被去重挡住，后续同类事件全部静默」，**故障期间告警彻底消失** | 增加 `release(dedupeKey)`，发送最终失败（含落盘）后必须释放（**§7.4**、用例 T4） | core SPI 签名 + Redis/内存两个实现 |
| **4** | Redis Lua 用 `math.random()` 作 ZSET member | ① 非确定性命令影响主从复制与 AOF 重放；② 同毫秒碰撞会导致 `ZADD` 覆盖、计数偏小、限流失效 | 改用 `nowMs .. ':' .. currentCount`，确定且唯一（**§9.4**） | `store-redis` 的 Lua 脚本 |
| **5** | `NotifyEvent` 的 `onSuccess`/`onError` 未考虑 MQ 模式 | 回调是 lambda，捕获业务对象，**不可序列化**，MQ 模式下会直接抛异常 | 标 `transient` + JSON 跳过 + `mode=mq` 时记 warn 并正常发送 + 报文加 `schemaVersion` 做版本兼容（**§6.4**） | core 模型 + bus 序列化 |
| **6** | `NotifyContext` 未定义接收人粒度 | 多接收人 × 多渠道时的审计、重试、限流粒度都不明确 | 确定**管线层 fan-out**：N receiver × M channel → N×M 个 Context，各自独立发送/重试/审计；支持 `supportsBatch()` 走批量（**§6.3**） | core 管线 + 所有渠道 + 审计表 |
| **7** | 渲染前置但未处理格式差异 | 钉钉要 Markdown、邮件要 HTML、短信要纯文本，单一渲染结果无法满足 | 引入 `RenderFormat` 枚举（PLAIN/MARKDOWN/HTML）+ `NotifyChannel#preferredFormat()`，管线按目标格式渲染（**ADR-004**） | core SPI + 各渠道 |
| **8** | `nx_scene_config` 唯一键只有 `scene` | Admin 是多应用共享的，不同应用的同名场景配置必然不同，会互相覆盖 | 唯一键改为 `(app_id, scene)`；SDK 按自己的 `appId` pull 配置（**§12.2**） | DDL + Admin 查询 + pull 接口 |
| **9** | 审计表列名用 `level` | ⚠️ **`level` 是人大金仓的保留字**，且金仓大小写敏感，会导致 SQL 报错 | 建议改名 `event_level`（`nx_scene_config` 同理）；比「处处加双引号」更可维护（**§12.4**） | DDL + MP 实体 + Admin SQL |
| **10** | 落盘目录无容量上限 | 通知持续失败会把磁盘写满，**把业务系统拖死**——违反承诺 P2 的最隐蔽路径 | 增加 `max-size-mb`（100MB）+ `max-files`（10000），溢出丢最旧 DEAD 记录 + 暴露 `fallback.overflow` 指标（**§9.9**、风险 R10） | core FailureStore + 指标 |
| **11** | 未提供 `log` 渠道，兜底链不完整 | 所有外部渠道都挂时，通知内容会凭空消失，事后无法追溯 | 新增 `notifyx-channel-log`（30 行，结构化日志输出），**优先级提到 P0**，作为所有 fallback-chain 的最终节点（**§10.1**、§9.7） | 新模块 |

### 23.1 补充设计（非修正，讨论稿未涉及）

| # | 补充内容 | 位置 | 价值 |
| --- | --- | --- | --- |
| S1 | **能力梯度 T0–T3 的显式建模与探测方式** | §4.3 | 把「自动降级」从口号变成可实现的探测规则 |
| S2 | **数据流/控制流/观测流三流分离** | §4.5 | 承诺 P2 的架构落地依据 |
| S3 | **完整时序图（含 AOP 异常 rethrow 的关键步骤）** | §4.4 | 明确「切面绝不吞异常」 |
| S4 | **ArchUnit 断言代码示例** | §5.3 | 三条铁律从文档约定变成 CI 强制 |
| S5 | **`alarm(scene, throwable, context...)` 的 context 摊平规则** | §8.5 | 决定 L4 的易用性；明确**不自动反射 POJO**（隐私与性能风险） |
| S6 | **SpEL `-parameters` 缺失的检测与告警** | §8.3 | 否则 `#cmd` 静默失效，极难排查 |
| S7 | **指纹算法的 8 条实现细节**（分隔符、null、时间归一化、堆栈排除等） | §9.1 | 「稳定可复现」的具体含义 |
| S8 | **渠道错误码分类表**（6 个渠道 × 3 类） | §10.3 | 渠道实现质量的第一考核点 |
| S9 | **webhook 签名规范 + 接收方验签示例** | §10.4 | 防重放、防时序攻击；接收方示例是渠道价值的一部分 |
| S10 | **升级策略的「恢复信号」双路径**（静默恢复 / 显式恢复场景） | §9.6 | 否则计数永不重置，升级会误触发 |
| S11 | **聚合功能的能力梯度依赖**（T0 仅实例内、T1 Redis、T2 consumer 侧） | §9.5 | 诚实说明单实例部署下的局限 |
| S12 | **Admin REST API 完整契约**（客户端侧 + 管理台侧 + 签名算法 + 长轮询） | §13.3 | 讨论稿只有功能清单，无接口定义 |
| S13 | **配置项优先级与合并规则**（列表整体替换 vs Map 深度合并） | §14.2 | 最容易做错、最难排查的部分 |
| S14 | **配置元数据 JSON 示例** | §14.3 | 「做得像官方 starter」的细节分水岭 |
| S15 | **启动诊断表的完整样例** | §14.4 | 排查问题时比文档管用十倍 |
| S16 | **指标标签基数控制约束**（禁用 receiver/bizId/eventId 作标签） | §15.1 | 否则 Prometheus 时序爆炸 |
| S17 | **建议的 Prometheus 告警规则** | §15.1 | 反向闭环：让运维体系监控 NotifyX 自身 |
| S18 | **审计表只 INSERT 不 UPDATE** | §16.5 | 等保的防篡改要求 + 完整还原重试过程 |
| S19 | **脱敏与可追溯的平衡**（`receiver` 脱敏 + `receiver_hash`） | §16.2 | 否则脱敏后无法按接收人排查 |
| S20 | **SSRF 防护**（禁重定向 + webhook URL 白名单） | §16.6 | Admin 被攻破时的纵深防御 |
| S21 | **`trust-all=true` 的 ERROR 级启动警告** | §16.6 | 信创自签证书场景需要它，但不能静默启用 |
| S22 | **24 条高危测试用例清单** | §18.2 | 「不测就一定会踩」的具体化 |
| S23 | **`StormSimulationDemo`** | §18.4 | 防风暴能力靠跑一次证明，靠文档说服不了人 |
| S24 | **NFR 量化指标**（业务线程 P99 < 1ms 等） | §20 | 没有量化指标的 NFR 是许愿 |
| S25 | **配置项数量上限（120）** | §20.3 | 强迫设计时问「能否用默认值消化」 |
| S26 | **版本号不跟随 Spring Boot** | §19.3 | 解耦两个不相关的变更，便于排查 |
| S27 | **风险登记册（14 条，含概率/影响/缓解/应急）** | §22 | 讨论稿是 7 条列表，本文档补充量化与应急预案 |
| S28 | **GraalVM Native 预留约束** | §17.5 | P0 不做，但代码上不做阻碍它的事 |
| S29 | **Admin 启动时点提前到 P2** | §21.3 | Admin 的 API 契约会反向影响 SDK 配置模型，晚做导致返工 |

### 23.2 需要评审确认的开放问题

以下问题本文档给出了倾向性建议，但需要需求方最终拍板：

| # | 问题 | 本文档建议 | 备选 |
| --- | --- | --- | --- |
| Q1 | 审计表列名 `level` 是否改为 `event_level`？ | ✅ 改（规避金仓保留字） | 保留 + 处处加双引号 |
| Q2 | `ReceiverResolver` 与 `CircuitBreaker` 只有一个实现，是否降级为策略枚举？ | 保留 SPI 但标 `@Incubating` | 降级为枚举，1.0 后再抽象 |
| Q3 | `log` 渠道是否默认启用？ | ✅ 默认启用（兜底价值高，开销近零） | 默认关闭，用户显式开 |
| Q4 | Admin 是否与 SDK 同仓库？ | ✅ 同仓库（API 契约同步演进） | 独立仓库（讨论稿倾向） |
| Q5 | `notifyx-consumer` 是否需要独立的 Docker Compose 一键部署包？ | ✅ 需要（降低 MQ 模式试用门槛） | 只提供 jar |
| Q6 | 是否提供 Boot 2.x（JDK 8）兼容分支？ | ❌ 不提供（维护成本翻倍，Boot 2 已 EOL） | 提供 `0.x` 分支给存量用户 |
| Q7 | `global-mute` 是否应该对 CRITICAL 也生效？ | ✅ 生效（一键止血必须绝对有效），但提供 `mute-critical=false` 选项 | CRITICAL 永不静默 |

---

## 第 24 章 附录

### 附录 A：核心接口速查

```java
// ---------- 用户只需要认识这一个接口 ----------
public interface NotifyKit {
    void send(NotifyEvent event);
    void send(String scene, Map<String, ?> params);
    void send(String scene, String receiver, Map<String, ?> params);
    void alarm(Throwable t);
    void alarm(String scene, Throwable t, Object... context);
    void sendAsync(NotifyEvent event);
}

// ---------- 扩展渠道需要实现这个 ----------
public interface NotifyChannel extends Ordered {
    String name();
    default boolean isEnabled(NotifyContext ctx) { return true; }
    SendResult send(NotifyContext ctx) throws ChannelException;
    default RenderFormat preferredFormat() { return RenderFormat.PLAIN; }
    default int maxRetries() { return 3; }
    default Duration retryBackoff() { return Duration.ofSeconds(2); }
    default Set<Class<? extends Throwable>> nonRetryable() { return Set.of(); }
    default boolean supportsBatch() { return false; }
    default HealthStatus health() { return HealthStatus.UNKNOWN; }
    default void close() { }
}

// ---------- 扩展漏斗需要实现这个 ----------
public interface EventFilter extends Ordered {
    String name();
    FilterVerdict doFilter(FilterContext ctx);
    default boolean supports(NotifyEvent event) { return true; }
}

// ---------- 可选 SPI ----------
TemplateEngine · DedupeStore · RateLimiter · FailureStore
AuditRecorder · ReceiverResolver · Masker · NotifyBus · CircuitBreaker
```

### 附录 B：最小可用配置（10 分钟接入）

```xml
<!-- 1. pom.xml -->
<dependency>
    <groupId>io.github.pkyit</groupId>
    <artifactId>notifyx-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>io.github.pkyit</groupId>
    <artifactId>notifyx-channel-email</artifactId>
    <version>1.0.0</version>
</dependency>
```

```yaml
# 2. application.yml（5 行）
notifyx:
  scenes:
    ORDER_CREATE_FAIL:
      channels: [email]
      receivers: [ops@example.com]
  channels:
    email:
      enabled: true
      host: smtp.qq.com
      port: 465
      ssl: true
      username: noreply@example.com
      password: ${SMTP_PASS}
      from: noreply@example.com
```

```java
// 3. 业务代码（1 行）
@Service
public class OrderService {
    private final NotifyKit notifyKit;
    public OrderService(NotifyKit notifyKit) { this.notifyKit = notifyKit; }

    public Order create(OrderCmd cmd) {
        try {
            return doCreate(cmd);
        } catch (Exception e) {
            notifyKit.alarm("ORDER_CREATE_FAIL", e, Map.of("orderId", cmd.getOrderNo()));
            throw e;   // ★ 异常照常抛出，NotifyX 只做旁路观察
        }
    }
}
```

**完成。** 没有 Redis、没有 MQ、没有数据库、没有 Admin —— 这就是承诺 P1 的用户可见形态。

### 附录 C：Code Review 检查清单

每次 PR 必查（按失效后果的严重程度排序）：

**🔴 会导致生产事故的**

- [ ] 切面/Filter/Appender 内部异常是否被吞掉（不得外抛到业务线程）？
- [ ] `@AlarmProtect` 是否原样 rethrow 了业务异常？
- [ ] 是否新增了阻塞业务线程的调用（同步 IO、`CallerRunsPolicy`、无超时 HTTP）？
- [ ] 队列/落盘/去重缓存是否有容量上限？
- [ ] Appender 是否有自递归防护（logger name 过滤 + 重入保护）？
- [ ] 渠道异常是否正确分类为可重试/不可重试？
- [ ] 是否往 `System.out` 写了东西？

**🟡 会导致架构腐化的**

- [ ] `core` 是否引入了 Spring / MP / DB / 渠道 SDK 依赖？（ArchUnit 应已拦截）
- [ ] `starter` 的 compile classpath 是否被污染？
- [ ] 渠道模块之间是否产生了依赖？
- [ ] 新增 SPI 是否满足 ADR-010 准入守则（有第二个实现）？
- [ ] 新增配置项是否必要？能否用默认值消化？（总数上限 120）

**🟢 会导致维护困难的**

- [ ] 指标标签是否引入了高基数字段（`receiver`/`bizId`/`eventId`）？
- [ ] 密钥/敏感字段是否在日志或 `toString()` 里打码？
- [ ] 新 public API 是否有 Javadoc + `@Since`？
- [ ] 是否有魔法值未提为常量？
- [ ] 是否新增了高危路径但没有对应测试用例（对照 §18.2）？
- [ ] FreeMarker/SpEL 的注入防护是否仍然生效？

### 附录 D：参考资料

| 类型 | 内容 |
| --- | --- |
| 原始需求 | `Java代码异常告警与通知的松...-20261021211622.pdf`（与 AI 的三轮讨论，v0.1 → v0.3） |
| Spring Boot 3.5.16 | 2026-06-25 发布，3.5.x 系列最后一个 OSS 版本；OSS 支持已于 2026-06-30 结束，商业支持至 2032-06。来源：spring.io 官方博客、endoflife.date |
| Spring Boot 4.0 | 2025-11 GA，基于 Spring Framework 7。⚠️ 具体破坏性变更清单（Starter 模块化、Jackson 3、JSpecify、虚拟线程默认化）来自社区技术文章，**实现前需以官方 Upgrading 文档逐条核对** |
| sms4j | `org.dromara.sms4j:sms4j-spring-boot-starter:3.3.5`，Apache-2.0，覆盖 20+ 短信厂商，已适配 Boot 3.x / Jakarta。来源：sms4j.com、mvnrepository |
| MyBatis-Plus | `mybatis-plus-spring-boot3-starter` 3.5.x（具体小版本实现时以官方最新发布为准） |
| 对标产品 | XXL-JOB（SDK + Admin + 执行器形态）、Nacos（配置中心 + pull 同步 + 控制台） |

### 附录 E：文档维护约定

| 项 | 约定 |
| --- | --- |
| 本文档定位 | **架构基线**。实现与文档冲突时，先改文档再改代码，或明确记录偏离原因 |
| ADR 变更 | 新增决策追加 ADR-0xx，**不修改已有 ADR**；推翻旧决策时新写一条并在旧条目加 `Superseded by ADR-0yy` |
| 复评触发 | 每条 ADR 的「复评触发条件」命中时，必须重新评审并更新文档 |
| 版本 | 文档版本独立于代码版本。重大架构调整升 minor，破坏性调整升 major |
| 开放问题 | §23.2 的 Q1–Q7 拍板后，结论写入对应章节并从开放问题清单移除 |

---

**文档结束**

| | |
| --- | --- |
| 文档版本 | v1.0（架构评审版） |
| 编制日期 | 2026-10-21 |
| 基线讨论稿 | v0.3（设计冻结候选） |
| 修订项 | 11 处修正（第 23 章）+ 29 项补充设计 + 7 个开放问题 |
| 下一步 | 评审 §23.2 的 Q1–Q7 → 冻结架构 → 按 §21.2 的 11 步序列启动 P0 |
