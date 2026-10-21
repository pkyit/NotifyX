# iter-01 · L0 接入：Logback Appender（设计书 §8.1 / T7）

- **日期**：2026-10-22
- **分支**：`feat/logback-appender` → `master`
- **类型**：feat（P0 第 9 步）
- **关联**：ISSUE-002（后续）、ISSUE-004、ISSUE-012（新增）、D-005、D-007

## 1. 背景

§21.2 第 9 步要求 Logback Appender，验收标准 T7（通知失败不得通过日志再触发通知）。
这是 P0 里唯一未完成的「接入层」能力，也是打动存量系统的 L0 用法（§8.1）。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | **设计书 8.1 的 `<extractBizId regex=".." group=".."/>` 在 logback 1.5 下静默失效** | Joran status 只有 `Assuming default type [ExtractBizId] for [extractBizId] property`，随后直接 `appender started`；`getExtractBizId().getRegex()` 为 null | 改为子元素写法；同时支持扁平属性；启动时检测到空 regex 写 WARN。见 D-007 |
| 2 | 「NotifyX 自身 logger 前缀过滤」把示例应用自己的日志也过滤了 | E2E：`/demo/log-error` 无任何投递；sample 包名是 `io.github.pkyit.notifyx.sample`，命中前缀 | 示例改用 `com.example.legacy.OrderService` logger；真实业务包名不受影响。记录 ISSUE-012 |
| 3 | Appender 由 logback 在 Spring 上下文刷新前创建，拿不到 `NotifyKit` Bean | 无绑定器时事件只能丢弃（`unbound` 计数） | `NotifyxAppenderBinder` 实现 `SmartInitializingSingleton`，上下文就绪后回填、`DisposableBean` 解绑 |
| 4 | 队列慢/关闭时可能拖住 JVM 关闭 | `stop()` 先 join 有限时间，再 interrupt；drain 循环检查中断标志 | 已实现并有 `shouldDrainOnStop` / 队列满丢弃测试 |
| 5 | `AppenderBase.doAppend` 是 `synchronized`，高并发下会串行化日志线程 | logback 源码 | 继承 `UnsynchronizedAppenderBase`，只保留无锁入队 |
| 6 | 自递归有两层形态：同一线程重入、以及投递线程里再次写日志 | T7 | 双重防线：logger 前缀过滤（`io.github.pkyit.notifyx`、`NOTIFYX_`）+ `ThreadLocal` 重入标记（在 `append` 与 `dispatch` 两处检查） |

## 3. 改动点

**新增模块 `notifyx-logback`**（core + logback-classic provided + spring-boot-autoconfigure provided）：

| 类 | 职责 |
| --- | --- |
| `NotifyxAppender` | 异步有界队列（`ArrayBlockingQueue` + 单守护线程）、级别/marker/噪声过滤、`bizId` 提取、堆栈截断、计数（submitted/dropped/discarded/suppressed/unbound/errors） |
| `NotifyxAppender.ExtractBizId` | 嵌套配置对象（logback 子元素写法） |
| `NotifyxAppenderBinder` | 遍历 LoggerContext（含 AsyncAppender 递归）回填/解绑 `NotifyKit` |
| `NotifyxLogbackAutoConfiguration` | 模块内自动配置，`@ConditionalOnClass(logback)` + `@ConditionalOnBean(NotifyKit)`；`notifyx.logback.enabled=false` 可关 |

**工程接线**：

- 父 POM：`<module>notifyx-logback</module>` + `dependencyManagement`；顺手修掉 starter 依赖条目粘连的格式问题。
- `notifyx-bom`：新增 `notifyx-logback`。
- `notifyx-sample`：依赖 + `logback-spring.xml`（CONSOLE + NOTIFYX）+ `/demo/log-error` 端点 + `LOG_ERROR` 场景与模板。
- `README`：模块表、L0 章节、版本范围；标注 logback 1.5 的属性写法陷阱。

**测试（20 个，全部通过）**：

| 测试类 | 覆盖 |
| --- | --- |
| `NotifyxAppenderTest`（10） | 事件映射/bizId/堆栈、级别过滤、marker、suppressRegex、队列满不阻塞、T7 两种自递归、停止排空、非法 scene、堆栈截断 |
| `NotifyxAppenderXmlTest`（4） | 设计书 XML 契约、扁平写法、属性写法告警、非法正则不炸启动 |
| `NotifyxAppenderBinderTest`（2） | 绑定/解绑、AsyncAppender 嵌套 |
| `NotifyxLogbackAutoConfigurationTest`（4） | 有/无 NotifyKit、开关、imports 文件登记 |

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，11 个模块，43 tests，0 failures
  （core 20 + autoconfigure 3 + logback 20）

E2E（notifyx-sample, mvn spring-boot:run）：
POST /demo/log-error?orderNo=SO-E2E-002
→ 日志：NotifyxAppenderBinder : notifyx: bound 1 logback appender(s) to NotifyKit
→ NOTIFYX_FALLBACK : scene=LOG_ERROR level=ERROR ... content=orderId=SO-E2E-002 ...
→ NOTIFYX_AUDIT    : scene=LOG_ERROR channel=log status=SUCCESS attempts=1
业务代码只写了一行 log.error(ALARM, ...)，未调用 NotifyKit —— L0 成立。
```

## 5. 回滚

删除 `notifyx-logback` 模块及其在父 POM/BOM/sample 的引用即可；其余模块无行为变化。

## 6. 下一步

- iter-02：发布链路（§19.1）。
- ISSUE-012：把「内部 logger 前缀」做成可配置（P1 与诊断表一起做）。
- T7 已在单测层闭环，后续可在 sample 加一个「通知失败」场景做集成验证（滞后）。
