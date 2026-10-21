# iter-11 · Email 渠道契约测试（§18.3）

- **日期**：2026-10-22
- **分支**：`test/email-contract` → `master`
- **类型**：test（补 §18.3 最后一块渠道契约）
- **关联**：ISSUE-010（关闭）

## 1. 背景

Webhook 已在 iter-06 有 12 个契约测试，email 仍为零。邮件渠道的特殊风险是
「错误分类错 → 重试策略错」：把 535 认证失败当可重试会无限重试，把超时当不可重试会丢通知。
此外 `Message-ID` 固定为 eventId 是幂等设计（§10.1），必须有断言守住。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | `Transport.send(MimeMessage)` 是静态方法，无法注入假传输 | `EmailChannel.send` 直接调用静态 `Transport.send` | 用 Mockito 静态 mock（Mockito 5 inline 默认），不发真实流量、不需要 SMTP 服务 |
| 2 | 邮件主题在标题为空时应回落到场景码 | 代码 `title == null ? scene : title` | 增加专门用例 |
| 3 | 错误分类依赖 message 文本关键字（535/550/authentication） | `classify()` 字符串匹配 | 分别覆盖 535、550、authentication 关键字、通用超时 |
| 4 | `Message-ID`/`X-Notifyx-Event-Id` 是幂等契约，易被静默改掉 | 代码显式设置 header | 断言两个 header 都包含 eventId |

## 3. 改动点

**新增 `EmailChannelTest`（6 个用例）**：

| 用例 | 覆盖 |
| --- | --- |
| 构造并发送 | 发件人地址/显示名、收件人、主题、正文、`Message-ID`、`X-Notifyx-Event-Id`、SendResult |
| 标题为空 | 主题回落到场景码 |
| 认证失败 | `AuthenticationFailedException` → `ChannelAuthException`（不可重试） |
| 550 拒收 | `MessagingException("550 ...")` → `ChannelProtocolException`（不可重试） |
| 通用连接失败 | `MessagingException("connect timed out")` → `ChannelException`（可重试） |
| message 文本含 authentication | 仍归类为认证失败 |

**POM**：email 模块增加 mockito-core（test）；`jacoco.minimum=0.75`（实测 79%）。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，12 模块，145 tests，0 failures
  core 80 / spring 6 / autoconfigure 21 / email 6 / logback 20 / webhook 12
→ JaCoCo：email 79%（门禁 75%）；其它模块门禁全部通过
→ Spotless 0 / Checkstyle 0
```

## 5. 回滚

删除 `EmailChannelTest` 与 POM 中的 mockito/jacoco 配置即可；生产代码零改动。

## 6. 下一步

- ISSUE-010 关闭（webhook + email 均有契约测试）。
- 下一轮：`NotifyEnvelope` + 自动补偿（ISSUE-015），或覆盖率抬升（core 约 72% → 90%、spring 21.6% → 60%+）。
