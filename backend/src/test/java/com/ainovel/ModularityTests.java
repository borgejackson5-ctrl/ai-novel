package com.ainovel;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 架构守护：模块之间必须是单向依赖（有向无环图）。
 *
 * <p>模块在各自 {@code package-info.java} 中声明为
 * {@link org.springframework.modulith.ApplicationModule.Type#OPEN}，
 * 因此 Modulith 默认校验中的「外部只能访问模块根包」被豁免，本项目只需保证「无环」。
 *
 * <p>需要自行编写环检测的原因：Modulith 的 {@code verify()} 中
 * "no cycles on the application module level" 这条规则随「具名接口」体系一起生效；
 * 模块全部声明为 OPEN 后，该规则在当前配置下不会触发（故意注入一条反向依赖时 verify 仍通过）。
 * 因此这里使用 Modulith 计算好的依赖图自行执行 DFS，判据明确、结果可复现。
 *
 * <p>需要约束的原因：模块之间成环时，任何一处改动都会牵连到对侧，既无法单独理解，
 * 也无法单独测试。这类问题在 code review 中看不出来，只有把整张依赖图算出来才会暴露。
 */
class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(AiNovelApplication.class);

    @Test
    void verifyModuleStructure() {
        // OPEN 模块下这条主要起「模块能被识别、装配没坏」的作用
        MODULES.verify();
    }

    @Test
    void moduleDependenciesFormDirectedAcyclicGraph() {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        MODULES.stream().forEach(m -> {
            Set<String> targets = new LinkedHashSet<>();
            m.getDependencies(MODULES).stream()
                    .map(d -> d.getTargetModule().getName())
                    .sorted()
                    .forEach(targets::add);
            graph.put(m.getName(), targets);
        });

        assertFalse(graph.isEmpty(), "一个模块都没识别到，说明模块检测没生效");

        List<String> cycle = findCycle(graph);
        assertNull(cycle, () -> "模块之间出现循环依赖：" + String.join(" -> ", cycle));
    }

    /** DFS 找第一个环；没找到返回 null。 */
    private static List<String> findCycle(Map<String, Set<String>> graph) {
        Set<String> done = new LinkedHashSet<>();
        for (String start : graph.keySet()) {
            List<String> path = new ArrayList<>();
            if (dfs(start, graph, path, done)) {
                return path;
            }
        }
        return null;
    }

    private static boolean dfs(String node, Map<String, Set<String>> graph,
                               List<String> path, Set<String> done) {
        if (path.contains(node)) {
            path.add(node);          // 收尾，让路径首尾呼应：a -> b -> a
            return true;
        }
        if (done.contains(node)) {
            return false;
        }
        path.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (dfs(next, graph, path, done)) {
                return true;
            }
        }
        path.remove(path.size() - 1);
        done.add(node);
        return false;
    }
}
