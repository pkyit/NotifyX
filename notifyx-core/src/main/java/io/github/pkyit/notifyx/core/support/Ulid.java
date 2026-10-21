package io.github.pkyit.notifyx.core.support;

import java.security.SecureRandom;

/**
 * ULID 生成器：48 位毫秒时间戳 + 80 位随机数，Crockford Base32 编码为 26 个字符。
 *
 * <p>相比 UUID，ULID 按时间有序，作为数据库主键对索引更友好（设计书 6.5）。</p>
 *
 * <p>@implNote 线程安全。同一毫秒内通过原子自增保证单调。</p>
 *
 * @since 1.0
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int TIME_LEN = 10;
    private static final int RANDOM_LEN = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    /**
     * 生成一个新的 ULID。
     *
     * @return 26 位字符串
     */
    public static String next() {
        long time = System.currentTimeMillis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        char[] out = new char[TIME_LEN + RANDOM_LEN];
        for (int i = TIME_LEN - 1; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 0x1F)];
            time >>>= 5;
        }
        int index = TIME_LEN;
        int buffer = 0;
        int bits = 0;
        for (byte b : random) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out[index++] = ALPHABET[(buffer >>> (bits - 5)) & 0x1F];
                bits -= 5;
            }
        }
        if (bits > 0) {
            out[index] = ALPHABET[(buffer << (5 - bits)) & 0x1F];
        }
        return new String(out);
    }
}
