package com.ainovel.module.admin;

import com.ainovel.common.annotation.AdminLogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守门测试：{@code @AdminLogRecord} 必须标在「真正的写操作」上，且写入数据库时能取到目标与详情。
 *
 * <p>需要该测试的原因：注解的作用对象是紧跟其后那个方法，中间插入一个新方法会把注解
 * 转移到新方法上。曾出现过一次：在反馈模块新增「待处理数」接口时插入到注解与
 * {@code handleFeedback} 之间，于是
 *
 * <ul>
 *   <li>{@code GET /admin/feedback/pending-count}（只读，被前端按 30 秒轮询）开始向审计日志
 *       写入记录，每 30 秒一条，导致日志表被大量无效记录填充；</li>
 *   <li>真正的敏感操作 {@code PUT /admin/feedback/{id}/handle}（发奖励、回复用户）反而没有审计；</li>
 *   <li>该接口没有参数，{@code targetIdIndex} 默认取第 0 个参数，因此 {@code target_id} 全为 NULL。</li>
 * </ul>
 *
 * <p>三个断言正好覆盖这三种表现，缺一不可：位置错误、取不到目标、详情表达式引用了不存在的参数。
 */
class AdminLogRecordUsageTest {

    /** detail 里的 SpEL 参数引用，如 {@code #p1} */
    private static final Pattern PARAM_REF = Pattern.compile("#p(\\d+)");

    @Test
    @DisplayName("操作日志注解：必须是写操作、能取到目标 ID、详情表达式引用的参数必须存在")
    void adminLogRecordMustBeUsableAndOnWriteOperations() throws Exception {
        List<String> problems = new ArrayList<>();
        int scanned = 0;

        for (Class<?> controller : restControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                AdminLogRecord record = AnnotationUtils.findAnnotation(method, AdminLogRecord.class);
                if (record == null) {
                    continue;
                }
                scanned++;
                String where = controller.getSimpleName() + "#" + method.getName();

                // ① 必须落在写操作上：标到 GET（或没有映射方法）上，说明注解位置错误
                RequestMapping mapping = AnnotationUtils.findAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    problems.add(where + "：标了操作日志，但方法不是任何请求映射（注解很可能被顶错位置）");
                } else if (!isWriteOperation(mapping)) {
                    problems.add(where + "：标了操作日志，但映射是 "
                            + Arrays.toString(mapping.method())
                            + "（只读接口不该写审计日志，注解多半被顶到了下一个方法上）");
                }

                // ② targetIdIndex >= 0 时，该位置的参数必须是 Long，否则 target_id 永远为 NULL
                int index = record.targetIdIndex();
                if (index >= 0) {
                    Class<?>[] types = method.getParameterTypes();
                    if (index >= types.length) {
                        problems.add(where + "：targetIdIndex=" + index + " 超出参数个数 "
                                + types.length + "，target_id 会永远是 NULL");
                    } else if (types[index] != Long.class && types[index] != long.class) {
                        problems.add(where + "：targetIdIndex=" + index + " 指向的参数是 "
                                + types[index].getSimpleName()
                                + "（不是 Long），target_id 会永远是 NULL；没有单一目标时请显式写 targetIdIndex = -1");
                    }
                }

                // ③ detail 表达式中的 #pN 必须落在参数范围内：越界的表达式只会在运行期被吞掉（logger.warn）
                Matcher matcher = PARAM_REF.matcher(record.detail());
                Set<Integer> reported = new HashSet<>();
                while (matcher.find()) {
                    int ref = Integer.parseInt(matcher.group(1));
                    if (ref >= method.getParameterCount() && reported.add(ref)) {
                        problems.add(where + "：detail 引用了 #p" + ref + "，但方法只有 "
                                + method.getParameterCount() + " 个参数，详情会永远是空");
                    }
                }
            }
        }

        assertTrue(scanned > 0, "一个标了 @AdminLogRecord 的方法都没扫到，说明扫描逻辑失效了（测试本身有问题）");
        assertTrue(problems.isEmpty(), () -> "操作日志注解用法有问题：\n  - " + String.join("\n  - ", problems));
    }

    /** 只读接口被挂上审计注解（本项目出现过该问题）；这里按「写操作白名单」判定 */
    private boolean isWriteOperation(RequestMapping mapping) {
        RequestMethod[] methods = mapping.method();
        if (methods.length == 0) {
            // 没写 method 的 @RequestMapping 无法判断意图，按不安全处理
            return false;
        }
        return Arrays.stream(methods).noneMatch(m ->
                m == RequestMethod.GET || m == RequestMethod.HEAD || m == RequestMethod.OPTIONS);
    }

    private List<Class<?>> restControllers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.ainovel")) {
            classes.add(Class.forName(definition.getBeanClassName()));
        }
        return classes;
    }
}
