package io.github.pkyit.notifyx.demo;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.pkyit.notifyx.autoconfigure.AlertTemplate;
import io.github.pkyit.notifyx.core.AlertLevel;

/**
 * 演示：一个 HTTP 接口触发告警，观察日志通道输出。
 */
@RestController
public class DemoController {

    private final AlertTemplate alertTemplate;

    public DemoController(AlertTemplate alertTemplate) {
        this.alertTemplate = alertTemplate;
    }

    @GetMapping("/demo/alert")
    public Map<String, Object> alert(@RequestParam(defaultValue = "ERROR") String level,
                                     @RequestParam(defaultValue = "手动触发的演示告警") String message) {
        AlertLevel alertLevel = AlertLevel.valueOf(level.toUpperCase());
        alertTemplate.level(alertLevel, message)
                .content("这是通过 /demo/alert 触发的演示事件")
                .tag("env", "demo")
                .attribute("traceId", "demo-trace-001")
                .send();
        return Map.of("sent", true, "level", alertLevel.name(), "message", message);
    }

    @GetMapping("/demo/business-error")
    public Map<String, Object> businessError() {
        // 模拟业务异常：统一在业务 catch 块里上报，不依赖 AOP
        try {
            throw new IllegalStateException("模拟下游支付渠道超时");
        } catch (IllegalStateException ex) {
            alertTemplate.error("支付回调失败")
                    .content(ex.getMessage())
                    .tag("biz", "payment")
                    .attribute("exception", ex.getClass().getName())
                    .send();
        }
        return Map.of("handled", true);
    }
}
