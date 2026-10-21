package io.github.pkyit.notifyx.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 升级策略测试（设计书 §9.6）：阈值/窗口、持续升级、恢复场景、成功清零。
 *
 * @since 1.0
 */
class EscalationPolicyTest {

    private static final long NOW = 1_000_000L;

    @Test
    @DisplayName("达到连续次数后升级，并持续保持升级态")
    void shouldEscalateAfterThreshold() {
        EscalationPolicy policy = policy(2, 10, List.of("sms", "phone"), true);

        assertThat(policy.record("ORDER_CREATE_FAIL", NOW).escalated()).isFalse();
        assertThat(policy.record("ORDER_CREATE_FAIL", NOW + 100).escalated()).isTrue();
        assertThat(policy.record("ORDER_CREATE_FAIL", NOW + 200).channels()).containsExactly("sms", "phone");
    }

    @Test
    @DisplayName("超过窗口没有新事件则计数自动过期")
    void shouldExpireWhenWindowPassed() {
        EscalationPolicy policy = new EscalationPolicy(List.of(new EscalationPolicy.Rule(
                "ORDER_CREATE_FAIL", 2, Duration.ofMillis(10), List.of("sms"), true)), Map.of());
        assertThat(policy.record("ORDER_CREATE_FAIL", NOW).escalated()).isFalse();
        assertThat(policy.record("ORDER_CREATE_FAIL", NOW + 5).escalated()).isTrue();

        // 间隔超过窗口后重新累计，需要重新达到阈值
        assertThat(policy.record("ORDER_CREATE_FAIL", NOW + 100).escalated()).isFalse();
    }

    @Test
    @DisplayName("恢复场景清零目标场景")
    void shouldResetByRecoveryScene() {
        EscalationPolicy policy = new EscalationPolicy(
                List.of(new EscalationPolicy.Rule("ORDER_CREATE_FAIL", 2, Duration.ofMinutes(10), List.of("sms"), true)),
                Map.of("ORDER_CREATE_RECOVERED", List.of("ORDER_CREATE_FAIL")));
        policy.record("ORDER_CREATE_FAIL", NOW);
        assertThat(policy.record("ORDER_CREATE_FAIL", NOW + 10).escalated()).isTrue();
        assertThat(policy.isRecoveryScene("ORDER_CREATE_RECOVERED")).isTrue();

        assertThat(policy.recover("ORDER_CREATE_RECOVERED")).containsExactly("ORDER_CREATE_FAIL");
        assertThat(policy.record("ORDER_CREATE_FAIL", NOW + 20).escalated()).isFalse();
    }

    @Test
    @DisplayName("reset-on-success=true 时成功投递清零；false 时保持")
    void shouldResetOnSuccessOnlyWhenConfigured() {
        EscalationPolicy resetting = policy(1, 10, List.of("sms"), true);
        assertThat(resetting.record("ORDER_CREATE_FAIL", NOW).escalated()).isTrue();
        resetting.recordSuccess("ORDER_CREATE_FAIL");
        assertThat(resetting.record("ORDER_CREATE_FAIL", NOW + 10).escalated()).isTrue();

        EscalationPolicy sticky = policy(1, 10, List.of("sms"), false);
        assertThat(sticky.record("ORDER_CREATE_FAIL", NOW).escalated()).isTrue();
        sticky.recordSuccess("ORDER_CREATE_FAIL");
        assertThat(sticky.record("ORDER_CREATE_FAIL", NOW + 10).escalated()).isTrue();
    }

    @Test
    @DisplayName("未配置规则的场景不升级；disabled 策略无规则")
    void shouldIgnoreUnconfiguredScenes() {
        EscalationPolicy policy = policy(1, 10, List.of("sms"), true);

        assertThat(policy.record("OTHER_SCENE", NOW).escalated()).isFalse();
        assertThat(policy.ruleCount()).isEqualTo(1);
        assertThat(EscalationPolicy.disabled().ruleCount()).isZero();
    }

    @Test
    @DisplayName("规则参数规范化：阈值至少 1、窗口缺省 10 分钟")
    void shouldNormalizeRule() {
        EscalationPolicy.Rule rule = new EscalationPolicy.Rule("S", 0, null, null, true);
        assertThat(rule.consecutive()).isEqualTo(1);
        assertThat(rule.within()).isEqualTo(Duration.ofMinutes(10));
        assertThat(rule.upgradeTo()).isEmpty();
    }

    private EscalationPolicy policy(int consecutive, int withinMinutes, List<String> upgradeTo,
                                    boolean resetOnSuccess) {
        return new EscalationPolicy(List.of(new EscalationPolicy.Rule(
                "ORDER_CREATE_FAIL", consecutive, Duration.ofMinutes(withinMinutes), upgradeTo, resetOnSuccess)),
                Map.of());
    }
}
