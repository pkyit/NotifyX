# iter-19 · 可复现的真实邮件冒烟测试

- **日期**：2026-10-22
- **分支**：`feature/email-live-smoke` → `master`
- **类型**：test/feat（把一次性验证固化为可重复执行的入口）
- **关联**：iter-18、journal/2026-10-22

## 1. 背景

iter-18 用临时 Java 程序验证了真实 SMTP，但临时程序在 `%TEMP%`，无法复用、无法留档。
用户已确认两封邮件均收到；把这条验证路径固化为仓库内的可选测试，后续换邮箱/换环境可直接复跑。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 真实发送验证不可复现 | 临时 `LiveEmailTest.java` 只在临时目录 | 新增 `EmailLiveSmokeTest`，环境变量驱动 |
| 2 | CI 没有凭据，不能默认发真实邮件 | 公开仓库 + CI 无 Secret | `@EnabledIfEnvironmentVariable(NOTIFYX_SMTP_USER)` + 缺失 `PASS/TO` 时 `assumeTrue` 跳过 |
| 3 | 凭据不能入库 | 公开仓库硬规则 | 只从环境变量读取；类注释写明用法与「会产生真实流量」 |
| 4 | 多收件人需要逐个验证 | 用户给了两个 QQ 邮箱 | `NOTIFYX_SMTP_TO` 逗号分隔，逐个发送并断言 SUCCESS |

## 3. 改动点

新增 `notifyx-channel-email/src/test/.../EmailLiveSmokeTest.java`：

- 默认跳过（无 `NOTIFYX_SMTP_USER` 时整类禁用；缺 `PASS`/`TO` 时用例跳过）；
- 支持 `NOTIFYX_SMTP_HOST/PORT/SSL/USER/PASS/TO`；
- 每个收件人发送一封 `EMAIL_LIVE_SMOKE` 邮件，断言 `SendResult.Status.SUCCESS` 并打印 messageId/耗时。

**用法**（PowerShell，凭据仅存在于当前会话）：

```powershell
$env:NOTIFYX_SMTP_HOST="smtp.163.com"
$env:NOTIFYX_SMTP_PORT="465"
$env:NOTIFYX_SMTP_SSL="true"
$env:NOTIFYX_SMTP_USER="<邮箱>"
$env:NOTIFYX_SMTP_PASS="<授权码>"
$env:NOTIFYX_SMTP_TO="a@example.com,b@example.com"
mvn -B -pl notifyx-channel-email test -Dtest=EmailLiveSmokeTest
```

## 4. 验证

```text
# 默认（无环境变量）：跳过
Tests run: 9, Failures: 0, Errors: 0, Skipped: 1（EmailLiveSmokeTest）

# 带真实凭据：实际发送
LIVE_EMAIL pky1999@qq.com status=SUCCESS messageId=<255041198...> cost=PT3.0028113S
LIVE_EMAIL pkyit@qq.com   status=SUCCESS messageId=<703824017...> cost=PT0.4839243S
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

用户已在两个 QQ 邮箱中确认收到 iter-18 的邮件。

## 5. 回滚

删除 `EmailLiveSmokeTest` 与 README 的用法段即可；生产代码零改动。

## 6. 下一步

- k8s 部署验证（Secret 注入 SMTP 凭据）。
- 排查场景 level 观察项。
