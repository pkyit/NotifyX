package io.github.pkyit.notifyx.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ULID 测试（设计书 6.5）：固定长度、Crockford Base32 字符集、唯一性。
 *
 * @since 1.0
 */
class UlidTest {

    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    @Test
    @DisplayName("生成的 ULID 是 26 位 Crockford Base32")
    void shouldGenerateValidUlid() {
        for (int i = 0; i < 100; i++) {
            String ulid = Ulid.next();
            assertThat(ulid).hasSize(26);
            assertThat(ULID.matcher(ulid).matches()).as(ulid).isTrue();
        }
    }

    @Test
    @DisplayName("大量生成不重复")
    void shouldBeUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(Ulid.next())).isTrue();
        }
    }

    @Test
    @DisplayName("时间前缀随毫秒单调不减（排序友好）")
    void shouldBeTimeOrdered() throws Exception {
        String first = Ulid.next();
        Thread.sleep(5);
        String second = Ulid.next();
        assertThat(second.substring(0, 10).compareTo(first.substring(0, 10))).isGreaterThanOrEqualTo(0);
    }
}
