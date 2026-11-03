package io.github.pkyit.notifyx.spring.spel;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SpEL 求值器测试（设计书 8.3）：{@code -parameters} 缺失时参数名退化为 arg0 的检测与降级。
 *
 * @since 1.0
 */
class SpelEvaluatorTest {

    @Test
    @DisplayName("T10：-parameters 缺失时参数名退化为 arg0，#p0/#a0 仍可求值，具名变量不可用且不抛异常")
    void shouldFallBackToPositionalArgsWithoutParameterNames() throws Exception {
        Method method = String.class.getMethod("indexOf", String.class);
        // JDK 预编译类未开启 -parameters，参数名退化为合成名 argN，模拟业务方漏配 -parameters 的场景
        assertThat(method.getParameters()[0].getName()).isEqualTo("arg0");

        ProceedingJoinPoint joinPoint = joinPoint(method, new Object[]{"hello"});
        SpelEvaluator evaluator = new SpelEvaluator(false);

        assertThat(evaluator.evaluateAfter("#p0", joinPoint, null, null)).isEqualTo("hello");
        assertThat(evaluator.evaluateAfter("#a0", joinPoint, null, null)).isEqualTo("hello");
        assertThat(evaluator.evaluateAfter("#orderNo", joinPoint, null, null)).isNull();
    }

    private static ProceedingJoinPoint joinPoint(Method method, Object[] args) {
        MethodSignature signature = (MethodSignature) Proxy.newProxyInstance(
                SpelEvaluatorTest.class.getClassLoader(),
                new Class<?>[]{MethodSignature.class},
                (proxy, called, callArgs) -> "getMethod".equals(called.getName())
                        ? method
                        : defaultValue(called.getReturnType()));
        return (ProceedingJoinPoint) Proxy.newProxyInstance(
                SpelEvaluatorTest.class.getClassLoader(),
                new Class<?>[]{ProceedingJoinPoint.class},
                (proxy, called, callArgs) -> {
                    switch (called.getName()) {
                        case "getArgs":
                            return args;
                        case "getSignature":
                            return signature;
                        default:
                            return defaultValue(called.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        return null;
    }
}
