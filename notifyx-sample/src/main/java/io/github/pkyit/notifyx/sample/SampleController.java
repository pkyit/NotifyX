package io.github.pkyit.notifyx.sample;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.pkyit.notifyx.core.api.NotifyKit;

/**
 * 示例接口：一行门面调用、异常告警、通知风暴与 L0 日志接入。
 *
 * <p>@implNote 线程安全。</p>
 *
 * @since 1.0
 */
@RestController
public class SampleController {

    /**
     * 模拟存量业务系统的 logger。刻意不叫 {@code io.github.pkyit.notifyx.*}：
     * Appender 会过滤这个前缀（防自递归），示例要演示的是「业务方自己的日志」。
     */
    private static final Logger legacyLog = LoggerFactory.getLogger("com.example.legacy.OrderService");

    private static final Marker ALARM = MarkerFactory.getMarker("ALARM");

    private final NotifyKit notifyKit;

    public SampleController(NotifyKit notifyKit) {
        this.notifyKit = notifyKit;
    }

    /**
     * 触发一条业务通知。
     *
     * @param orderNo 订单号
     * @return 结果
     */
    @GetMapping("/demo/notify")
    public Map<String, Object> notify(@RequestParam(defaultValue = "SO20261021001") String orderNo) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", orderNo);
        params.put("reason", "库存不足");
        notifyKit.send("ORDER_CREATE_FAIL", params);
        return Map.of("sent", true, "scene", "ORDER_CREATE_FAIL");
    }

    /**
     * 触发一次异常告警。
     *
     * @param orderNo 订单号
     * @return 结果
     */
    @GetMapping("/demo/alarm")
    public Map<String, Object> alarm(@RequestParam(defaultValue = "SO20261021002") String orderNo) {
        try {
            throw new IllegalStateException("模拟下游支付渠道超时");
        } catch (IllegalStateException ex) {
            notifyKit.alarm("ORDER_CREATE_FAIL", ex, Map.of("orderId", orderNo));
        }
        return Map.of("sent", true, "scene", "ORDER_CREATE_FAIL");
    }

    /**
     * L0 演示：只打一条带 ALARM marker 的 ERROR 日志，由 NotifyxAppender 转成事件。
     *
     * @param orderNo 订单号
     * @return 结果
     */
    @GetMapping("/demo/log-error")
    public Map<String, Object> logError(@RequestParam(defaultValue = "SO20261022003") String orderNo) {
        legacyLog.error(ALARM, "orderId={} 支付回调超时（L0 日志接入演示，业务代码未调用 NotifyX）", orderNo);
        return Map.of("logged", true, "scene", "LOG_ERROR", "orderNo", orderNo);
    }

    /**
     * 通知风暴模拟：连续产生大量同场景事件，验证限流与去重。
     *
     * @param count 事件数量
     * @return 结果
     */
    @GetMapping("/demo/storm")
    public Map<String, Object> storm(@RequestParam(defaultValue = "1000") int count) {
        long started = System.nanoTime();
        for (int i = 0; i < count; i++) {
            notifyKit.send("ORDER_CREATE_FAIL", Map.of("orderId", "SO" + i, "reason", "库存不足"));
        }
        long costMs = (System.nanoTime() - started) / 1_000_000;
        return Map.of("submitted", count, "costMs", costMs);
    }
}
