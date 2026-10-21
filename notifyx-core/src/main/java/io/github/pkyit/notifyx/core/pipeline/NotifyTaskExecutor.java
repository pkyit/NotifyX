package io.github.pkyit.notifyx.core.pipeline;

import io.github.pkyit.notifyx.core.model.Level;

/**
 * 告警投递执行器：把「按级别排队」的职责从门面里抽出来。
 *
 * <p>实现必须保证：拒绝策略不得把任务回退到调用线程执行（违背承诺 P2），
 * 队列满时应丢弃优先级最低的任务并计数（设计书 ADR-008）。</p>
 *
 * <p>@implNote 实现类必须线程安全。</p>
 *
 * @since 1.0
 */
@FunctionalInterface
public interface NotifyTaskExecutor {

    /**
     * 提交一个投递任务。
     *
     * @param level 任务级别，决定排队优先级
     * @param taskKey 任务标识（eventId），用于日志与去重诊断
     * @param task 任务
     */
    void execute(Level level, String taskKey, Runnable task);

    /**
     * 同步执行器：用于单测与 {@code notifyx.async.enabled=false}。
     *
     * @return 执行器
     */
    static NotifyTaskExecutor direct() {
        return (level, taskKey, task) -> task.run();
    }
}