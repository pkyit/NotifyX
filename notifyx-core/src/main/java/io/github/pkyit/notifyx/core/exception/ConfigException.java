package io.github.pkyit.notifyx.core.exception;

/**
 * 配置错误（密钥缺失、格式非法）。
 *
 * <p>@implNote 不可变，线程安全。</p>
 *
 * @since 1.0
 */
public class ConfigException extends NotifyException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}