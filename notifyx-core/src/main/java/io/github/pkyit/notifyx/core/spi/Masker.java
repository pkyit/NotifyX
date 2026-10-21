package io.github.pkyit.notifyx.core.spi;

import java.util.Map;

/**
 * 脱敏 SPI。
 *
 * <p>@implNote 实现类必须线程安全。</p>
 *
 * @since 1.0
 */
public interface Masker {

    /**
     * 手机号掩码：13888888888 → 138****8888。
     *
     * @param value 原值
     * @return 掩码值
     */
    String maskPhone(String value);

    /**
     * 邮箱掩码：admin@example.com → a***@example.com。
     *
     * @param value 原值
     * @return 掩码值
     */
    String maskEmail(String value);

    /**
     * 身份证掩码：110101199001011234 → 110***********1234。
     *
     * @param value 原值
     * @return 掩码值
     */
    String maskIdCard(String value);

    /**
     * 银行卡掩码：6222021234567890123 → 6222***********0123。
     *
     * @param value 原值
     * @return 掩码值
     */
    String maskBankCard(String value);

    /**
     * 按 key 名递归脱敏 params。
     *
     * @param params 原始变量
     * @return 脱敏后的副本
     */
    Map<String, Object> maskParams(Map<String, Object> params);

    /**
     * 对渲染后文本做正则兜底脱敏。
     *
     * @param text 原文
     * @return 脱敏文本
     */
    String maskText(String text);
}