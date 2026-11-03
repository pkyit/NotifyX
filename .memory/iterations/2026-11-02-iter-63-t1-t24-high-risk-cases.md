# iter-63 · 覆盖 T1–T24 剩余高危用例

- **日期**：2026-11-02
- **分支**：`master`（纯测试，未单独开分支）
- **类型**：test（设计书 §18.2 高危用例清单补缺）
- **触发**：STATE.md 未实现清单最后一项「覆盖 T1–T24 的剩余高危用例（§18.2）」。

## 1. 问题

§18.2 列了 24 个「不测就一定会踩」的高危用例。经逐个盘点测试文件，发现以下缺漏：
T2、T9、T10、T12、T14、T16、T20、T23、T24 尚未有显式对应用例；
T17/T19/T22 因对应能力不在 P0/P1 SDK 交付范围（见「未纳入」）而 N/A。

## 2. 方案

补齐纯测试，不改生产代码：

| # | 文件 | 用例 | 验证点 |
| --- | --- | --- | --- |
| T2 | `FingerprintsTest` | `shouldSurviveJsonRoundTrip` | JSON 序列化→反序列化后指纹不变（跨进程一致） |
| T9 | `NotifyxAspectTest` | `shouldDegradeWhenSpelFieldMissing` | `#r` 引用不存在字段→放行+不告警 |
| T10 | `SpelEvaluatorTest`（新） | `shouldFallBackToPositionalArgsWithoutParameterNames` | 参数名退化 arg0 时 `#p0`/`#a0` 可用、具名不可用、不抛异常 |
| T12 | `PriorityNotifyExecutorTest` | `shouldNotBlockCallerWhenQueueFull` | 队列满 1000 次提交全部立即返回 <1s、无 CallerRuns |
| T14 | `FallbackStoreTest` | `shouldNotRetryRedisWhileCircuitOpen` | 熔断打开后不再触碰 Redis |
| T16 | `NotifyxLifecycleTest` | `shouldDrainAllQueuedTasksOnShutdown` | 关闭时排空 100 个任务 |
| T20 | `WebhookChannelTest` | `shouldBlockMetadataAddress` | 元数据地址 169.254.169.254 被白名单拦截 |
| T23 | `NotifyEnvelopeCodecTest` | `shouldIgnoreUnknownFields` | 同版本多未知字段被忽略 |
| T24 | `NotifyPipelineTest` | `shouldFanOutAuditForEveryReceiverAndChannel` | 3 接收人 × 2 渠道 = 6 条审计 |

## 3. 验证

```text
mvn -B -ntp -pl notifyx-core -am test -Dtest=FingerprintsTest,NotifyEnvelopeCodecTest,NotifyPipelineTest
  → BUILD SUCCESS（24 用例）
mvn -B -ntp -pl notifyx-spring -am test -Dtest=NotifyxAspectTest,SpelEvaluatorTest,PriorityNotifyExecutorTest -Dsurefire.failIfNoSpecifiedTests=false
  → BUILD SUCCESS（19 用例）
mvn -B -ntp -pl notifyx-store-redis,notifyx-spring-boot3-autoconfigure,notifyx-channel-webhook -am test -Dtest=FallbackStoreTest,NotifyxLifecycleTest,WebhookChannelTest -Dsurefire.failIfNoSpecifiedTests=false
  → BUILD SUCCESS（store-redis 4、autoconfigure 6、webhook 19）
```

## 4. 测试方法说明

- 仓库无 SLF4J 日志捕获测试基础设施，T10/T14 的「warn 只打一次」用**行为等价断言**
  （参数退化后位置变量仍可用 / 熔断打开后 Redis 不再被调用）。
- T10 用 JDK 动态代理（`java.lang.reflect.Proxy`）构造 `ProceedingJoinPoint`/`MethodSignature`，
  避免给 `notifyx-spring` 引入 Mockito 测试依赖。

## 5. 未纳入 / N/A

- T17（短信不重试）：sms4j 适配不在 P0/P1 SDK，仓库无短信渠道模块；
- T19（FreeMarker RCE）：模板引擎为自研 `SimpleTemplateEngine`（core SPI），无 FreeMarker 依赖；
- T22（配置深度合并）：Admin 控制台未在本次交付，无多来源合并路径。
- 以上三项在迭代文档标注 N/A，不强行造测试。

## 6. 文档与门禁

- `.memory/STATE.md`：勾选该项 + 新增第 14 节；更新时间刷新。