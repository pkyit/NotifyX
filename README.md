# NotifyX

**业务语义层告警通知平台** —— 在业务代码里埋语义点，用一行代码 / 一个注解，把「业务发生了什么」可靠地送到人手里。

监控体系盯的是「进程还活着吗」，NotifyX 盯的是「业务还正常吗」：订单创建失败、支付回调超时、对账不平、账号开通通知。

> 架构基线见 [docs/design.md](docs/design.md)（v1.0 评审版）。实现与文档冲突时，以文档章节号为准。

## 为什么不是又一个日志框架

| 常见做法 | 问题 |
| --- | --- |
| `mailService.send(...)` 散落在业务里 | 换渠道要改代码发版 |
| 直接调钉钉 webhook | 死循环异常 3 分钟发 2 万条，账号被封 |
| 出问题查「谁给谁发过什么」 | 没有审计，答不上来 |
| 通知风暴时止血 | 只能重启或改代码 |

NotifyX 把「发送」收口成一条固定责任链：**场景路由 → 免打扰 → 去重 → 限流 → 投递 → 重试 → 熔断 → 审计**，业务只声明 `scene`。

## 快速开始

**1. 引依赖**

```xml
<dependency>
    <groupId>io.github.pkyit</groupId>
    <artifactId>notifyx-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.github.pkyit</groupId>
    <artifactId>notifyx-channel-log</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

> 坐标说明（ADR-001）：主坐标是 `notifyx-spring-boot-starter`。
> `notifyx-spring-boot3-starter` 作为 alias 保留，POM 里带 `<relocation>`，
> 老项目不用改也能拿到迁移提示。新项目请直接用主坐标。

**2. 配 3 行 YAML**

```yaml
notifyx:
  scenes:
    ORDER_CREATE_FAIL:
      level: ERROR
      channels: [log]
      receivers: [ops@example.com]
      silent-window: 5m
```

**3. 写一行代码**

```java
@Service
public class OrderService {

    private final NotifyKit notifyKit;

    public OrderService(NotifyKit notifyKit) {
        this.notifyKit = notifyKit;
    }

    public Order create(OrderCmd cmd) {
        try {
            return doCreate(cmd);
        } catch (Exception ex) {
            notifyKit.alarm("ORDER_CREATE_FAIL", ex, Map.of("orderId", cmd.getOrderNo()));
            throw ex;   // ★ 异常照常抛出，NotifyX 只做旁路观察
        }
    }
}
```

没有 Redis、没有 MQ、没有数据库、没有 Admin —— 应用照常启动，通知照常发出。

## 核心概念

| 术语 | 含义 |
| --- | --- |
| `scene` | 场景码，全局唯一锚点，模板/通道/接收人/静默期都挂在它上面，`^[A-Z][A-Z0-9_]{0,63}$` |
| `NotifyEvent` | 一次业务语义发生，不可变、可序列化 |
| `NotifyChannel` | 渠道 SPI：email / webhook / log / 自定义 |
| `EventFilter` | 责任链节点，顺序固定，用户可插 900+ |
| `dedupeKey` | 指纹，`sha256(scene ‖ bizId ‖ md5(sorted(params)))`，跨实例可复现 |

## 架构

```
业务代码 ──► NotifyKit（门面）
                │
                ▼
          有界优先级队列（Level 降序，drop-lowest）
                │
                ▼
  ① 全局静默 → ② 场景路由 → ③ 免打扰 → ④ 去重
                → ⑤ 场景限流 → ⑥ 全局限流 → 用户 Filter(900+)
                │
                ▼
        渲染（按渠道格式）→ fan-out（receiver × channel）
                │
                ▼
     渠道发送（指数退避 + 抖动 + 熔断）→ 审计 → 失败落盘
```

**CRITICAL 旁路矩阵**：直通免打扰与限流、不聚合；去重窗口减半且不超过 60 秒（既不失聪也不刷屏）。

## 模块

| 模块 | 说明 | 依赖纪律 |
| --- | --- | --- |
| `notifyx-bom` | 统一版本 | — |
| `notifyx-core` | 模型 / SPI / 指纹 / 限流 / 模板 / 脱敏 | **仅 SLF4J**，ArchUnit 强制 |
| `notifyx-spring` | AOP 注解、SpEL、优先级队列 | core + spring |
| `notifyx-spring-boot3-autoconfigure` | 自动装配、配置属性、诊断 | 不直接依赖任何渠道 |
| `notifyx-spring-boot-starter` | 业务方唯一依赖（主坐标） | 空聚合 |
| `notifyx-spring-boot3-starter` | 归档 alias，`<relocation>` 指向主坐标 | 依赖壳 |
| `notifyx-channel-log` | 兜底日志渠道 | core |
| `notifyx-channel-email` | Jakarta Mail | core + jakarta.mail |
| `notifyx-channel-webhook` | 通用 HTTP + HMAC 签名 | core |
| `notifyx-logback` | L0 日志即告警（Logback Appender） | core + logback（provided） |
| `notifyx-sample` | 可运行验收示例 | starter + 三渠道 |

## 接入方式

| 层级 | 方式 | 侵入度 |
| --- | --- | --- |
| L0 | Logback Appender（`notifyx-logback`）：日志即告警，业务代码一行不改 | 0 |
| L2 | `@AlarmProtect(scene = "PAY_CALLBACK_FAIL")` / `@NotifyOn(scene = "...", when = "#r.code != 0")` | 一个注解 |
| L3 | `@NotifyxScene` 事件或 `notifyx.events.mappings`：业务只发 Spring 事件 | 一次 publish |
| L4 | `notifyKit.alarm(scene, ex, ctx...)` / `notifyKit.send(scene, params)` | 一行调用 |

### L0 · 日志即告警（存量项目首选）

引 `notifyx-logback`，在 `logback-spring.xml` 里挂一个 appender：

```xml
<appender name="NOTIFYX" class="io.github.pkyit.notifyx.logback.NotifyxAppender">
    <scene>LOG_ERROR</scene>
    <level>ERROR</level>
    <marker>ALARM</marker>                     <!-- 只抓带此 marker 的日志 -->
    <extractBizId>
        <regex>orderId=(\w+)</regex>
        <group>1</group>
    </extractBizId>
    <suppressRegex>Connection reset|Broken pipe</suppressRegex>
    <maxStackTraceLines>30</maxStackTraceLines>
    <queueSize>1024</queueSize>
    <discardingThreshold>0</discardingThreshold>
</appender>
```

业务代码只需要一个 marker：

```java
private static final Marker ALARM = MarkerFactory.getMarker("ALARM");
log.error(ALARM, "orderId={} 支付回调超时", orderNo);
```

Appender 的保证：**异步有界队列**（绝不阻塞日志线程）、队列满即丢弃并计数、
内部异常全部吞掉、**双重防自递归**（NotifyX logger 前缀过滤 + 投递线程重入保护）。
Spring 环境自动把 `NotifyKit` 注入 appender；非 Spring 环境手动 `appender.setNotifyKit(kit)`。

> ⚠️ logback 1.5 的解析器不支持在嵌套元素上写属性，`<extractBizId regex=".." group=".."/>`
> 会被静默忽略（启动时会在 logback status 里告警）。请使用上面的子元素写法，
> 或扁平写法 `<extractBizIdRegex>orderId=(\w+)</extractBizIdRegex>`。


### L2 · 注解 + AOP（主力用法）

`@AlarmProtect` / `@NotifyOn` 一行注解挂在方法上；SpEL 默认禁用方法调用，防止表达式注入。

注解语义：`@AlarmProtect` 只在抛异常时触发，**异常原样抛出**；`@NotifyOn` 在 SpEL 条件为真或抛异常时触发，返回值原样返回。切面内部任何异常都被吞掉并记 warn，绝不影响业务。

### L3 · Spring Event（彻底解耦）

业务代码只发 Spring 事件，连 `NotifyKit` 都不用注入。让桥接层知道场景有两种方式：

**a) 注解事件类（可同时提供结构化参数）**

```java
@NotifyxScene(value = "ACCOUNT_CREATED", level = Level.INFO, receivers = {"ops@example.com"})
public record AccountCreatedEvent(String phone) implements NotifyxEventPayload {
    public Map<String, ?> params() {
        return Map.of("phone", phone);
    }
}

applicationEventPublisher.publishEvent(new AccountCreatedEvent(phone));
```

**b) 事件类零依赖 NotifyX，用配置映射**

```yaml
notifyx:
  events:
    mappings:
      com.example.event.AccountCreatedEvent: ACCOUNT_CREATED
```

桥接层把「原始事件类名 + `toString()`」放进 `params.__source` 便于审计追溯；
翻译失败只记 warn，**绝不把异常抛回事件发布方**。`notifyx.events.enabled=false` 可整体关闭。

## 配置（节选）

```yaml
notifyx:
  enabled: true
  async:
    enabled: true
    core-pool-size: 4
    queue-capacity: 4096      # 软上限，满了丢优先级最低的
    shutdown-await: 10s
  dedupe:
    enabled: true
    max-entries: 100000
  aggregate:
    enabled: false          # T0 实例内聚合：窗口内被去重的事件汇总成一条摘要
    window: 10s
    max-entries: 10000
  rate-limit:
    rules:
      - scene: ORDER_CREATE_FAIL
        limit: 10
        window: 1m
      - global: true
        limit: 100
        window: 1m
  quiet-hours:
    enabled: true
    from: "22:00"
    to: "09:00"
    timezone: Asia/Shanghai
    critical-bypass: true
  global-mute:
    enabled: false            # 一键止血，运行时可由 Admin 切换
  events:
    enabled: true
    mappings:                 # L3：事件类 → 场景码
      com.example.event.AccountCreatedEvent: ACCOUNT_CREATED
  channels:
    email:
      enabled: true
      host: smtp.example.com
      port: 465
      ssl: true
```

完整字段见各 `*Properties` 类（IDE 里敲 `notifyx.` 有补全）。

> 模板里的 `${...}` 是 **NotifyX 模板语法**，不是 Spring 占位符：模板配置用不做替换的 Binder 绑定，
> 所以 `${userName}` 不会被环境变量 `USERNAME` 篡改（这是实测踩到的坑，见 `.memory` D-011）。

## 扩展：写一个渠道（目标 < 80 行）

```java
public class DingTalkChannel implements NotifyChannel {

    @Override
    public String name() {
        return "dingtalk";
    }

    @Override
    public RenderFormat preferredFormat() {
        return RenderFormat.MARKDOWN;
    }

    @Override
    public SendResult send(NotifyContext ctx) throws ChannelException {
        // ctx.renderedTitle() / ctx.renderedContent() 已经渲染好
        // 打包报文 → 签名 → POST → 按错误码分类抛 ChannelAuthException / ChannelTimeoutException ...
    }
}
```

注册成 Bean 即可被发现；`nonRetryable()` 返回的异常类型不会重试，其余按指数退避重试。

## 扩展：插一个 Filter

```java
@Bean
public EventFilter debugOnlyFilter() {
    return new EventFilter() {
        public String name() { return "debug-only"; }
        public int order() { return 900; }          // 900+ 是用户区
        public FilterVerdict doFilter(FilterContext ctx) {
            return ctx.event().tags().containsKey("debug")
                    ? FilterVerdict.pass() : FilterVerdict.reject("not-debug");
        }
    };
}
```

## 当前版本范围（v1.0.0-SNAPSHOT）

已实现（对应设计书 P0/P1 的 SDK 部分）：

- core：`NotifyEvent` / `Level` / `NotifyContext` / `SendResult` / 异常体系 / 指纹算法 / 模板引擎 / 内存去重与限流 / 脱敏 / ULID
- 责任链：全局静默、场景路由、免打扰、去重（含失败释放）、场景限流、全局限流
- 执行层：优先级队列（drop-lowest）、指数退避 + 抖动、按渠道熔断、审计、失败落盘接口
- 聚合：T0 实例内窗口聚合（被去重事件汇总成摘要；多实例需 Redis/MQ 才能跨实例，已诚实标注）
- 接入：`@AlarmProtect` / `@NotifyOn` + SpEL（默认禁用方法调用）、`NotifyKit` 门面
- L3：Spring Event 桥接（`@NotifyxScene` 注解 / 配置映射，`params.__source` 可追溯）
- Trace：MDC 里的 `traceId`/`spanId` 自动进入模板、渠道上下文与审计（Micrometer / SkyWalking 均可）
- 渠道：log（默认开）、email、webhook（HMAC-SHA256 + 时间戳防重放 + 主机白名单）
- L0：`notifyx-logback` Appender（异步有界、噪声过滤、业务主键提取、双重防自递归）
- 可观测性：启动诊断表、`/actuator/health` 的 `notifyx` 组件（UP/DEGRADED/DOWN/OUT_OF_SERVICE）
- 生命周期：`SmartLifecycle` 优雅关闭（先排空队列、再释放渠道），队列满/关闭均有日志与指标
- 自动装配：全量 `@ConditionalOnMissingBean`、零依赖启动测试（T13）、Micrometer 指标

规划中（设计书后续阶段）：Redis 分布式去重/限流、MQ 总线与 consumer、MyBatis-Plus 审计落库、Admin 控制台、IM 渠道（钉钉/企微/飞书）、sms4j 适配。

## 可观测性与生命周期

启动后会打印一张能力表（渠道、SPI 实现、单机降级提示、熔断阈值），排查时比翻文档快：

```text
│ NotifyX dev  ·  mode=local  ·  tier=T0（单机内存）
│ 渠道（1 启用 / 1 已注册）
│   ✅ log  LogChannel
│ SPI 装配
│   去重   MemoryDedupeStore  ⚠ 单机内存，多实例部署下能力受限
```

- `notifyx.diagnostics.banner=false` 关闭诊断表。
- 引入 `spring-boot-starter-actuator` 后自动出现 `notifyx` 健康组件：
  `UP` / `DEGRADED`（内存去重/限流或多渠道部分不可用，K8s readiness 仍为 UP）/
  `DOWN`（全局静默或无渠道）/ `OUT_OF_SERVICE`（`notifyx.enabled=false`）。
- **默认不做真实探活**（`probe-enabled=false`），避免 K8s 每 10 秒的健康检查变成 8640 条垃圾消息；
  需要真实探活时显式开启 `notifyx.actuator.health.probe-enabled=true`。
- 关闭时 `NotifyxLifecycle` 在 `notifyx.async.shutdown-await`（默认 10s）内排空队列，再关闭渠道；
  K8s 的 `terminationGracePeriodSeconds` 必须大于该值（建议 30s）。

## 兼容性

| NotifyX | Boot 3.5（基线） | Boot 4 | JDK 17 | JDK 21 |
| --- | --- | --- | --- | --- |
| 1.0.x（开发中） | ✅ | 🧪 编译验证 | ✅ | ✅ |

NotifyX 的版本号**不跟随** Spring Boot：`1.x` 表示 SDK 自身 API 稳定性，支持的 Boot 范围由 `spring-boot.version` property 决定。

## 构建与测试

```bash
mvn clean verify                        # 编译 + 单测 + 格式/静态规则/覆盖率门禁
mvn -pl notifyx-sample spring-boot:run  # 启动示例（需先 mvn install）
curl "http://localhost:18080/demo/notify"
curl "http://localhost:18080/demo/storm?count=1000"
curl "http://localhost:18080/demo/log-error"   # L0 日志接入演示
```

`verify` 阶段执行三道门禁（§17.3）：

| 门禁 | 插件 | 规则 |
| --- | --- | --- |
| 格式 | Spotless | 未使用 import、import 顺序、行尾空白、文件末尾换行 |
| 静态规则 | Checkstyle | 星号导入、空语句、`equals/hashCode` 不成对、switch 缺 default、漏大括号等 |
| 覆盖率 | JaCoCo | core ≥ 65%、logback ≥ 80%、其余 ≥ 40%（设计目标 80%/90%，见 `.memory/STATE.md`） |

当前基线：**123 个测试**（core 66 / spring 6 / autoconfigure 19 / logback 20 / webhook 12），CI 矩阵 JDK 17/21。

## 发布

发布链路（GPG / Central）只在 `release` profile 激活，日常构建不受影响：

```bash
mvn -Prelease -Dgpg.skip=true clean package   # 本地验证 sources/javadoc 产物
mvn -Prelease deploy                          # 上传 Central，人工确认后发布
```

完整步骤与常见卡点见 [docs/release.md](docs/release.md)。

## License

[Apache License 2.0](LICENSE)