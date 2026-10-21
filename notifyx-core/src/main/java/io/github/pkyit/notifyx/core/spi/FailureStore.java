package io.github.pkyit.notifyx.core.spi;

import java.util.List;

/**
 * 失败落盘补偿 SPI。
 *
 * <p>@implNote 实现类必须线程安全；{@link #updateStatus} 使用版本号做乐观锁。</p>
 *
 * @since 1.0
 */
public interface FailureStore {

    /**
     * 保存一条失败记录。
     *
     * @param record 记录
     */
    void save(FailedRecord record);

    /**
     * 加载待补偿记录。
     *
     * @param limit 上限
     * @return 记录列表，按 nextRetryAt 升序
     */
    List<FailedRecord> loadPending(int limit);

    /**
     * 更新状态（乐观锁）。
     *
     * @param recordId 记录 ID
     * @param expectedVersion 期望版本
     * @param status 新状态
     * @param attempts 尝试次数
     * @return 是否更新成功
     */
    boolean updateStatus(String recordId, int expectedVersion, RecordStatus status, int attempts);

    /**
     * 待补偿数量。
     *
     * @return 数量
     */
    long pendingCount();
}