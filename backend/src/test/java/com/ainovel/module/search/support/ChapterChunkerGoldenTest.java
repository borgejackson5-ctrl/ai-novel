package com.ainovel.module.search.support;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 切块结果的锚点测试：把切块输出冻结在一份哈希清单里。
 *
 * <p>需要它的原因：块粒度是拿真实长篇量出来的（`eval/reports/chunk-granularity.md`），
 * 而实验用的那份 Python 实现是 {@link ChapterChunker} 的逐行复刻。
 * 「复刻与生产一致」这一前提只在编写实验时验证过一次，之后任何人修改切块逻辑
 * （哪怕只是把重叠从 60 改成 100），实验结果就会静默失效，没有任何机制会报错。
 *
 * <p>因此这里把输入与输出都固定下来：
 * <ul>
 *   <li>输入是 {@code eval/fixtures/chunker-golden.txt}（自行编写的测试文本，不含任何真实作品内容）；</li>
 *   <li>输出是 {@code eval/fixtures/chunker-golden-hashes.txt}（每块的字符数与 SHA-256）。</li>
 * </ul>
 * Java 侧运行该测试、Python 侧运行 {@code chunk_impl.py --golden-check}，
 * 两边对同一份清单负责。修改切块逻辑后必须重新生成清单，并重跑一次粒度实验，
 * 否则报告中的数字与代码不再对应。
 *
 * <p>清单文件不存在时跳过（eval/ 目录不一定存在于每台构建机器），存在则必须严格一致。
 */
class ChapterChunkerGoldenTest {

    /** 从工作目录向上找 eval/：IDEA 跑测试时工作目录是模块目录，maven 命令行是模块目录，两种都要能命中 */
    private static final List<Path> CANDIDATES = List.of(
            Path.of("eval/fixtures/chunker-golden.txt"),
            Path.of("../eval/fixtures/chunker-golden.txt"));

    @Test
    @DisplayName("切块锚点：同一份输入必须切出同一串块（Java 与 Python 两边都盯着这份清单）")
    void goldenChunks() throws IOException {
        Path input = CANDIDATES.stream().filter(Files::exists).findFirst().orElse(null);
        Assumptions.assumeTrue(input != null,
                "找不到 eval/fixtures/chunker-golden.txt，跳过（eval/ 不在本机构建目录下）");
        Path hashes = input.resolveSibling("chunker-golden-hashes.txt");
        Assumptions.assumeTrue(Files.exists(hashes), "找不到锚点清单，跳过");

        // 归一化换行：锚点文件在 Windows 上可能被 checkout 成 CRLF，而哈希是按 LF 内容计算的。
        // .gitattributes 中已禁止转换（-text），这里再兜一层，避免测试因环境差异而失败
        String content = Files.readString(input, StandardCharsets.UTF_8).replace("\r\n", "\n");
        List<String> lines = Files.readAllLines(hashes, StandardCharsets.UTF_8);

        List<String> expected = lines.stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .toList();
        int expectedCount = Integer.parseInt(expected.get(0).split("=")[1]);
        List<String> rows = expected.subList(1, expected.size());

        List<ChapterChunker.Chunk> chunks = new ChapterChunker().split(content);

        assertEquals(expectedCount, chunks.size(),
                "块数变了：锚点 " + expectedCount + " 块，实际 " + chunks.size()
                        + " 块。切块逻辑改动后必须重新生成锚点并重跑粒度实验");
        for (int i = 0; i < chunks.size(); i++) {
            String[] parts = rows.get(i).split("\t");
            ChapterChunker.Chunk chunk = chunks.get(i);
            assertEquals(Integer.parseInt(parts[1]), chunk.text().length(), "第 " + i + " 块长度变了");
            assertEquals(parts[2], sha16(chunk.text()),
                    "第 " + i + " 块内容变了（长度可能没变，但切点挪了）");
        }
    }

    private static String sha16(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
