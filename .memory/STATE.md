# NotifyX 状态快照

> 更新时间：2026-10-22（iter-05 完成：优雅关闭 + Health + 启动诊断）
> 交付范围：设计书 **P0/P1 的 SDK 部分**。**不是** 23 周后的 1.0 GA。
> 构建基线：`mvn -B clean verify` 通过（Spotless/Checkstyle/JaCoCo 全绿），
> 11 个模块，106 个测试；`mvn -Prelease -Dgpg.skip=true verify` 通过，sources/javadoc 产物齐全。

## 1. 一句话现状

责任链管线、三渠道、L0/L2/L3 接入、Trace、自动装配、发布链路、质量门禁、
优雅关闭、actuator health、启动诊断表都已就绪。**P0 只剩 ADR-001 alias artifact**；
P1 的聚合、升级、失败落盘补偿在排队。

## 2. 已实现（按设计书章节）

| 设计书 | 能力 | 位置 | 验证 |
| --- | --- | --- | --- |
| §6 核心模型 | `NotifyEvent` / `Level` / `SendResult` / 异常体系 / ULID | `notifyx-core` | `NotifyEventTest`、`FingerprintsTest` |
| §6.7 门面 | `NotifyKit` 6 方法 + `DefaultNotifyKit` | core | `NotifyPipelineTest` |
| §9.1 指纹 | `sha256(scene‖bizId‖md5(sorted params))` | core `Fingerprints` | `FingerprintsTest`（T1–T3） |
| §9.2 漏斗 | 全局静默 → 场景路由 → 免打扰 → 去重 → 场景限流 → 全局限流 → 用户 Filter(900+) | core `pipeline/filter` | `NotifyPipelineTest` |
| §9.7 熔断 | 按渠道失败阈值 / 打开时长 / 最长打开 | core `CircuitBreaker` | `NotifyPipelineTest` |
| §9.8 重试 | 指数退避 + 抖动 + 不可重试异常 | core `RetryPolicy` | 部分 |
| §9.9 失败落盘 | `FailureStore` SPI + `InMemoryFailureStore`（有界内存） | core | 间接 |
| §9.11 队列 | 优先级队列、软上限 drop-lowest、绝不 CallerRuns | `notifyx-spring` `PriorityNotifyExecutor` | 部分 |
| §8.3 L2 | `@AlarmProtect` / `@NotifyOn` + SpEL（默认禁方法调用） | `notifyx-spring` | 缺 AOP 测试 |
| §8.1 L0 | Logback Appender：异步有界、噪声过滤、bizId 提取、双重防自递归、Spring 自动绑定 | `notifyx-logback` | `NotifyxAppenderTest` 等 20 个 + E2E |
| §10.1 渠道 | log / email / webhook（HMAC + 时间戳 + 主机白名单） | `notifyx-channel-*` | 缺契约测试 |
| §14 配置 | `NotifyxProperties` 全量属性 + 模块内渠道自动装配 + 启动诊断表 | autoconfigure / channel 模块 | `ZeroDependencyStartupTest`（T13）、`NotifyxDiagnosticsTest` |
| §9.10 关闭 | `NotifyxLifecycle`（SmartLifecycle）：排空队列 → 关闭渠道 | autoconfigure | `NotifyxLifecycleTest` |
| §15.1 指标 | `NotifyMetrics` + Micrometer 适配 | core / autoconfigure | 无测试 |
| §15.2 健康 | `NotifyxHealthIndicator`：UP/DEGRADED/DOWN/OUT_OF_SERVICE，默认不探活 | autoconfigure | `NotifyxHealthIndicatorTest` + E2E |

## 3. 未实现（按优先级）

### P0 剩余

- [x] **Logback Appender（L0，§8.1 / T7）** —— iter-01 完成（含 E2E）
- [x] **发布链路（§19.1）** —— iter-02 完成配置与本地验证（实际 deploy 待 Central 账号 + GPG 私钥）
- [x] **质量门禁（§17.3）** —— iter-03 完成（Spotless / Checkstyle / JaCoCo / CI workflow）
- [x] **Spring Event 桥接（L3，§8.4）+ Trace（§15.3）** —— iter-04 完成（含 E2E）
- [ ] **ADR-001 alias artifact 发布策略**（`notifyx-spring-boot3-starter` relocation）

### P1

- [x] 优雅关闭（§9.10）—— iter-05（SmartLifecycle 排空 + 渠道释放；未排空落盘待 D-003）
- [x] actuator `HealthIndicator`（§15.2）+ 启动诊断表（§14.4）—— iter-05（含 E2E）
- [ ] 聚合（§9.5）、升级（§9.6）
- [ ] 失败落盘补偿（文件 / JDBC 实现）
- [ ] 覆盖 T1–T24 的剩余高危用例（§18.2）

### 明确不在本次交付范围（23 周路线图后续阶段）

Admin 控制台、consumer 服务、MQ 三总线、Redis / MyBatis-Plus store、钉钉 / 企微 / 飞书、
sms4j 适配。

## 4. 刻意偏离（详见 decisions.md）

| # | 偏离 | 原因 |
| --- | --- | --- |
| D-001 | core 用自定义 `NotifyxOrdered`，不用 Spring `Ordered` | 守住 ADR-003「core 零 Spring」，ArchUnit 强制 |
| D-002 | 场景解析在进入 Filter 链前完成，`SceneRoutingFilter` 只裁决「场景是否启用」 | `FilterContext` 需要携带场景配置（模板/渠道/接收人） |
| D-003 | 失败落盘当前只有有界内存 `InMemoryFailureStore` | local-file / JDBC 属 P1 后续，先保证 SPI 与调用点正确 |
| D-004 | 渠道自动配置放各自渠道模块，不放 autoconfigure | 避免 autoconfigure 依赖渠道 SDK（第 5 章依赖纪律） |
| D-005 | 新增 `notifyx-logback` 模块（设计书模块树未列） | P0 明确要求 Logback Appender，但放进 autoconfigure 会污染依赖方向 |
| D-007 | Appender 的 `extractBizId` 用子元素而非属性 | logback 1.5 的 model 解析器不把嵌套元素属性写入对象，属性写法静默失效 |
| D-008 | 内部 logger 前缀硬编码（`io.github.pkyit.notifyx` / `NOTIFYX_`） | 防自递归优先于可配置性；配置化会引入新的自递归风险 |
| D-009 | starter 增加 `NotifyxStarter` 标记类 | Central 强制要求 sources/javadoc，空模块产不出；用最小标记类闭环 |
| D-010 | 质量门禁用轻量格式检查 + Checkstyle 10.x + 覆盖率 ratchet | 全量重排 diff 过大；Checkstyle 14.x 需 JDK 21；覆盖率需逐步抬升 |
| D-011 | 模板配置用 identity PlaceholdersResolver 的 Binder 读取 | Spring 会把 `${userName}` 当环境变量解析成 `admin`，模板占位符必须与框架占位符隔离 |
| D-012 | 优雅关闭 phase 晚于 Web 优雅停机 | 先停新流量再排空通知，关闭窗口丢事件最少；仍早于数据源销毁 |

## 5. 下一步队列

1. **iter-06**：渠道契约测试（webhook HMAC / email），补 ISSUE-010。
2. **P0 收尾**：ADR-001 alias artifact（依赖壳 + relocation 提示）。
3. **P1 继续**：聚合（§9.5）、升级（§9.6）、失败落盘补偿（D-003）。
4. **持续**：ISSUE-013 覆盖率抬升（core 67.7% → 80% → 90%；spring 21.6% → 60%+）。
5. **待交付方**：Central 账号 + GPG 私钥 → 完成 §19.1 实际发布验证。

## 6. 当前分支

`master`（iter-05 合并后）。工作分支命名见 README。
