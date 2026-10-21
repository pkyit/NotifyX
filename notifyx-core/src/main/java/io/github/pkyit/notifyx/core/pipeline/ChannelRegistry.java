package io.github.pkyit.notifyx.core.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkyit.notifyx.core.spi.NotifyChannel;

/**
 * 渠道注册表：按名称索引并按 {@code order()} 排序。
 *
 * <p>同名渠道以先注册者为准并打一条 warn，避免静默覆盖。</p>
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public final class ChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelRegistry.class);

    private final Map<String, NotifyChannel> byName;
    private final List<NotifyChannel> ordered;

    private ChannelRegistry(Map<String, NotifyChannel> byName, List<NotifyChannel> ordered) {
        this.byName = Collections.unmodifiableMap(byName);
        this.ordered = Collections.unmodifiableList(ordered);
    }

    /**
     * 从渠道列表构建注册表。
     *
     * @param channels 渠道
     * @return 注册表
     */
    public static ChannelRegistry of(List<? extends NotifyChannel> channels) {
        Map<String, NotifyChannel> map = new LinkedHashMap<>();
        for (NotifyChannel channel : channels) {
            NotifyChannel previous = map.putIfAbsent(channel.name(), channel);
            if (previous != null) {
                log.warn("duplicate channel name [{}] ignored: {}", channel.name(), channel.getClass().getName());
            }
        }
        List<NotifyChannel> sorted = new ArrayList<>(map.values());
        sorted.sort(Comparator.comparingInt(NotifyChannel::order));
        return new ChannelRegistry(map, sorted);
    }

    /**
     * 按名查找。
     *
     * @param name 渠道名
     * @return 渠道
     */
    public Optional<NotifyChannel> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * 全部渠道，按 order 升序。
     *
     * @return 不可变列表
     */
    public List<NotifyChannel> all() {
        return ordered;
    }

    /**
     * 渠道名列表。
     *
     * @return 名称列表
     */
    public List<String> names() {
        return new ArrayList<>(byName.keySet());
    }
}