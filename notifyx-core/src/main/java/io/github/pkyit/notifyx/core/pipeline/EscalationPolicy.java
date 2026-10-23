package io.github.pkyit.notifyx.core.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 升级策略（设计书 §9.6）：同一场景在窗口内连续发生 N 次后，把后续通知升级到更高触达渠道。
 *
 * <p>「未恢复」= 窗口内持续产生事件。恢复信号有两种：</p>
 * <ol>
 *   <li><b>静默恢复</b>：窗口内不再产生事件，计数在下次评估时自然过期；</li>
 *   <li><b>显式恢复</b>：业务发送恢复场景（如 {@code ORDER_CREATE_RECOVERED}），
 *       通过 {@code resetScenes} 关联到目标场景，立即清零。</li>
 * </ol>
 *
 * <p>另支持 {@code resetOnSuccess=true}：事件成功投递后清零，适合「恢复即成功」的场景。</p>
 *
 * <p><b>能力边界</b>：T0 内存计数，多实例下每个实例各自计数，升级触发会比全局视图慢；
 * 跨实例计数属于 T1（Redis）能力。</p>
 *
 * <p>@implNote 线程安全；每个场景一个锁，规则数量很小。</p>
 *
 * @since 1.0
 */
public final class EscalationPolicy {

    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(10);

    private final Map<String, Rule> rules;
    private final Map<String, List<String>> resetScenes;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * 构造策略。
     *
     * @param rules 规则列表
     * @param resetScenes 恢复场景 → 需要清零的目标场景列表
     */
    public EscalationPolicy(List<Rule> rules, Map<String, List<String>> resetScenes) {
        Map<String, Rule> byScene = new LinkedHashMap<>();
        if (rules != null) {
            for (Rule rule : rules) {
                if (rule != null && rule.scene() != null && !rule.scene().isBlank()) {
                    byScene.put(rule.scene(), rule);
                }
            }
        }
        this.rules = Collections.unmodifiableMap(byScene);
        Map<String, List<String>> reset = new LinkedHashMap<>();
        if (resetScenes != null) {
            resetScenes.forEach((scene, targets) -> {
                if (scene != null && targets != null && !targets.isEmpty()) {
                    reset.put(scene, List.copyOf(targets));
                }
            });
        }
        this.resetScenes = Collections.unmodifiableMap(reset);
    }

    /**
     * 空策略：不做任何升级。
     *
     * @return 策略
     */
    public static EscalationPolicy disabled() {
        return new EscalationPolicy(List.of(), Map.of());
    }

    /**
     * 记录一次事件并评估是否需要升级。
     *
     * @param scene 场景码
     * @param now 当前毫秒时间
     * @return 评估结果
     */
    public Outcome record(String scene, long now) {
        Rule rule = rules.get(scene);
        if (rule == null || rule.upgradeTo().isEmpty()) {
            return Outcome.none();
        }
        State state = states.computeIfAbsent(scene, ignored -> new State());
        synchronized (state) {
            if (state.firstAt == 0 || now - state.firstAt > rule.within().toMillis()) {
                state.reset(now);
            }
            state.count++;
            if (!state.escalated && state.count >= rule.consecutive()) {
                state.escalated = true;
            }
            return state.escalated ? new Outcome(true, rule.upgradeTo()) : Outcome.none();
        }
    }

    /**
     * 事件成功投递后的回调；仅 {@code reset-on-success=true} 的规则会清零。
     *
     * @param scene 场景码
     */
    public void recordSuccess(String scene) {
        Rule rule = rules.get(scene);
        if (rule != null && rule.resetOnSuccess()) {
            reset(scene);
        }
    }

    /**
     * 是否为恢复场景。
     *
     * @param scene 场景码
     * @return 是否配置为恢复信号
     */
    public boolean isRecoveryScene(String scene) {
        return scene != null && resetScenes.containsKey(scene);
    }

    /**
     * 处理恢复场景：清零其关联的目标场景。
     *
     * @param recoveryScene 恢复场景码
     * @return 被清零的场景列表
     */
    public List<String> recover(String recoveryScene) {
        List<String> targets = resetScenes.get(recoveryScene);
        if (targets == null) {
            return List.of();
        }
        List<String> reset = new ArrayList<>(targets);
        reset.forEach(this::reset);
        return reset;
    }

    /**
     * 清零指定场景的计数与升级状态。
     *
     * @param scene 场景码
     */
    public void reset(String scene) {
        states.remove(scene);
    }

    /**
     * 规则数量（诊断用）。
     *
     * @return 规则数
     */
    public int ruleCount() {
        return rules.size();
    }

    /**
     * 升级评估结果。
     *
     * @param escalated 是否处于升级态
     * @param channels 升级后的渠道列表
     * @since 1.0
     */
    public record Outcome(boolean escalated, List<String> channels) {

        /**
         * 规范化。
         *
         * @param escalated 是否升级
         * @param channels 渠道
         */
        public Outcome {
            channels = channels == null ? List.of() : List.copyOf(channels);
        }

        /**
         * 不升级。
         *
         * @return 结果
         */
        public static Outcome none() {
            return new Outcome(false, List.of());
        }
    }

    /**
     * 升级规则。
     *
     * @param scene 场景码
     * @param consecutive 连续次数阈值
     * @param within 统计窗口
     * @param upgradeTo 升级后的渠道
     * @param resetOnSuccess 成功投递是否清零
     * @since 1.0
     */
    public record Rule(String scene, int consecutive, Duration within, List<String> upgradeTo,
                       boolean resetOnSuccess) {

        /**
         * 规范化。
         *
         * @param scene 场景码
         * @param consecutive 连续次数
         * @param within 窗口
         * @param upgradeTo 升级渠道
         * @param resetOnSuccess 成功清零
         */
        public Rule {
            consecutive = Math.max(1, consecutive);
            within = within == null || within.isNegative() || within.isZero() ? DEFAULT_WINDOW : within;
            upgradeTo = upgradeTo == null ? List.of() : List.copyOf(upgradeTo);
        }
    }

    /**
     * 场景计数状态。
     *
     * @implNote 字段读写由对象锁保护。
     */
    private static final class State {

        private long firstAt;
        private int count;
        private boolean escalated;

        private void reset(long now) {
            firstAt = now;
            count = 0;
            escalated = false;
        }
    }
}
