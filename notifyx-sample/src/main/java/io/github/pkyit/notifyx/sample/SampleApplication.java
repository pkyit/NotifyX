package io.github.pkyit.notifyx.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例应用：验证 L2 注解、L4 门面与防风暴能力。
 *
 * <p>@since 1.0</p>
 */
@SpringBootApplication
public class SampleApplication {

    /**
     * 启动入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
