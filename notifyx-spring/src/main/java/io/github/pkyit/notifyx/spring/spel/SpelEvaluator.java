package io.github.pkyit.notifyx.spring.spel;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * SpEL 求值器：表达式缓存 + 失败降级 + {@code -parameters} 缺失检测。
 *
 * <p>默认使用 {@link SimpleEvaluationContext}（只读属性、无方法调用、无类型引用），
 * 防止 SpEL 注入；只有显式开启 {@code allow-method-invocation} 时才切换到标准上下文。</p>
 *
 * <p>@implNote 线程安全：表达式缓存为 {@link ConcurrentHashMap}。</p>
 *
 * @since 1.0
 */
public final class SpelEvaluator {

    private static final Logger log = LoggerFactory.getLogger(SpelEvaluator.class);

    private final ExpressionParser parser = new SpelExpressionParser();
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();
    private final boolean allowMethodInvocation;
    private final AtomicBoolean missingParametersWarned = new AtomicBoolean();

    /**
     * 构造求值器。
     *
     * @param allowMethodInvocation 是否允许方法调用（默认 false，安全优先）
     */
    public SpelEvaluator(boolean allowMethodInvocation) {
        this.allowMethodInvocation = allowMethodInvocation;
    }

    /**
     * 在方法执行前求值（{@code #r}、{@code #e} 不可用）。
     *
     * @param expression 表达式
     * @param joinPoint 切点
     * @return 结果；表达式为空或求值失败时返回 null
     */
    public Object evaluateBefore(String expression, ProceedingJoinPoint joinPoint) {
        return evaluate(expression, variables(joinPoint, null, null));
    }

    /**
     * 在方法执行后求值。
     *
     * @param expression 表达式
     * @param joinPoint 切点
     * @param result 返回值
     * @param error 异常
     * @return 结果；表达式为空或求值失败时返回 null
     */
    public Object evaluateAfter(String expression, ProceedingJoinPoint joinPoint, Object result, Throwable error) {
        return evaluate(expression, variables(joinPoint, result, error));
    }

    private Object evaluate(String expression, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            Expression parsed = cache.computeIfAbsent(expression, parser::parseExpression);
            if (allowMethodInvocation) {
                org.springframework.expression.spel.support.StandardEvaluationContext context =
                        new org.springframework.expression.spel.support.StandardEvaluationContext();
                variables.forEach(context::setVariable);
                return parsed.getValue(context);
            }
            SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            variables.forEach(context::setVariable);
            return parsed.getValue(context);
        } catch (RuntimeException ex) {
            // 铁律 2：SpEL 求值失败一律降级，绝不外抛
            log.warn("SpEL evaluation failed, expression=[{}], reason={}", expression, ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> variables(ProceedingJoinPoint joinPoint, Object result, Throwable error) {
        Map<String, Object> variables = new LinkedHashMap<>();
        Object[] args = joinPoint.getArgs();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        boolean missingNames = false;
        for (int i = 0; i < args.length; i++) {
            variables.put("p" + i, args[i]);
            variables.put("a" + i, args[i]);
            if (i < parameters.length) {
                String name = parameters[i].getName();
                if (name.startsWith("arg")) {
                    missingNames = true;
                } else {
                    variables.put(name, args[i]);
                }
            }
        }
        if (missingNames && missingParametersWarned.compareAndSet(false, true)) {
            log.warn("@NotifyOn/@AlarmProtect 的具名参数无法解析，请确认编译时开启 -parameters；当前可用 #p0/#a0 替代");
        }
        variables.put("r", result);
        variables.put("e", error);
        variables.put("method", method.getName());
        return variables;
    }
}