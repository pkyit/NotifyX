# iter-03 · 质量门禁与 CI（设计书 §17.3 / §17.4）

- **日期**：2026-10-22
- **分支**：`build/quality-gates` → `master`
- **类型**：build / test（P0 第 1 步的工程化补齐）
- **关联**：ISSUE-003（关闭）、ISSUE-013（新增）、D-010（新增）

## 1. 背景

§17.3 要求「CI 不过即失败」，但仓库此前 `mvn verify` 只跑单测：没有格式检查、没有静态规则、
没有覆盖率门禁、没有 CI 配置。质量门禁是 P0 第 1 步的一部分，也是在后续迭代（L3/MDC、存储层）
之前必须先立起来的护栏。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 无任何质量门禁 / CI | `Test-Path .github` → False；pom 无 Spotless/Checkstyle/JaCoCo | 本次一次补齐四件套 |
| 2 | **checkstyle 14.3.0 在 JDK 17 下直接崩** | `UnsupportedClassVersionError: class file version 65.0`（14.x 用 Java 21 目标编译） | 固定 10.26.1（Java 11+，JDK 17 可运行） |
| 3 | Spotless 首次接入时 70/70 文件「需要修改」 | 多数文件缺文件末尾换行、CRLF 行尾、import 顺序 | `spotless:apply` 只做 import/空白修正，未做整体重排（diff +242/-115，机械变更） |
| 4 | 加 `.gitattributes` 后 Spotless 又失败 | 工作区仍是 CRLF，Spotless 按 `eol=lf` 期望 LF | 统一把 100 个文本文件规范为 LF；`.gitattributes` 固化 `* text=auto eol=lf` |
| 5 | 覆盖率基线低：core 52.9%、autoconfigure 41.7% | `jacoco.csv` 实测 | 先补 7 个高价值测试类把 core 提到 **67.7%**；门禁按基线设 ratchet，设计目标 80%/90% 记为 ISSUE-013 |

## 3. 改动点

**`pom.xml`（parent）**：

- Spotless 3.10.4：`removeUnusedImports` + `importOrder(java,javax,org,com,io.github.pkyit)`
  + `trimTrailingWhitespace` + `endWithNewline`，`verify` 阶段 `check`。
  **刻意不做整体格式化**：palantir/google-java-format 会产生 6000+ 行的机械 diff，让后续 review 失去意义。
- Checkstyle 插件 3.6.0 + checkstyle **10.26.1**，规则集 `config/checkstyle/checkstyle.xml`：
  星号/未使用/重复导入、空语句、`equals/hashCode` 配对、`switch` 缺 default、
  字符串 `==`、可简化的布尔表达式、`fall through`、必须带大括号等；当前 0 violation。
- JaCoCo 0.8.12：`prepare-agent` + `report` + `check`；`jacoco.minimum` 默认 0.40，
  core 覆盖为 0.65，logback 为 0.80。

**新增文件**：

- `config/checkstyle/checkstyle.xml`（规则集，带注释说明「为什么不做全量 Javadoc 检查」）。
- `.gitattributes`（`* text=auto eol=lf`，杜绝跨平台行尾 churn）。
- `.github/workflows/ci.yml`（JDK 17/21 矩阵、`mvn -B -ntp verify`、覆盖率 artifact、`fail-fast: false`）。

**测试新增（7 个类 / 30 个用例，core 54 个）**：

| 测试类 | 覆盖 |
| --- | --- |
| `CircuitBreakerTest` | 阈值打开、拒绝、半开单探针、成功关闭、失败退避翻倍、上限 |
| `MemoryRateLimiterTest` | 窗口内拒绝、key 隔离、滑窗恢复、非正 permits、evictExpired |
| `InMemoryFailureStoreTest` | 容量淘汰、到期排序、乐观锁版本、nextAttempt、limit |
| `DefaultMaskerTest` | 手机/邮箱/身份证/银行卡、按 key 脱敏、文本兜底、DurationParser |
| `RetryPolicyTest` | 指数退避、maxDelay、抖动区间、渠道覆盖 |
| `UlidTest` | 26 位 Base32、1 万次唯一、时间前缀有序 |
| `FilterBehaviorTest` | 全局静默（含 CRITICAL 旁路）、免打扰（DEFER/REJECT/时段外/from==to） |

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS
→ 11 模块：43 + 30 = 73 tests，0 failures
→ Spotless: 0 violations（全模块）
→ Checkstyle: 0 violations（全模块）
→ JaCoCo: core 67.7%（门禁 65%）、logback 85.2%（门禁 80%）、autoconfigure 41.7%（门禁 40%）
```

**未验证**：GitHub Actions 在 Gitee 托管下不会触发；已在该 workflow 顶部注明，
并说明 Gitee Go 可直接执行同一条 `mvn -B -ntp verify`。

## 5. 回滚

删除 parent POM 的 Spotless/Checkstyle/JaCoCo 插件与 properties、`config/checkstyle/`、
`.github/workflows/ci.yml`、`.gitattributes` 即可恢复；测试可以保留。

## 6. 下一步

- iter-04：Spring Event 桥接（L3）+ MDC。
- ISSUE-013：先把 core 覆盖率推到 80%+，最终 90%（设计书 §17.3）。
- ISSUE-010：webhook/email 契约测试（补 §21.2 第 8 步验收）。
