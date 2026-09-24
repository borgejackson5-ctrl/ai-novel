"""块粒度实验用的切块实现。

**这是 `ChapterChunker`（Java，生产代码）的逐行复刻**，不是「类似做法」——
块粒度这件事必须在生产代码的规则下量，否则量出来的数字跟线上没关系。

为什么不用 Java 跑实验：多粒度对比要跑 4 组，每组都要改常量 → 重新打包 → 重启 →
回填（embedding 调用）。Python 这边可以直接跑完四组。代价是这份复刻必须**先被证明一致**：

- 当时拿 4 个真实章（共 43 块）让 Java 探针 dump 结果、逐块比 SHA-256，全一致；
- 之后由 `fixtures/chunker-golden.*` 长期钉住：Java 侧是 `ChapterChunkerGoldenTest`，
  这边是 `--golden-check`（两边盯同一份清单，谁改了切块逻辑都会红）。
  **改了切块逻辑就要 `--golden` 重新生成锚点，并重跑一次块粒度实验。**
"""

from __future__ import annotations

import hashlib
import io
import re

# 与 ChapterChunker 的常量一致（默认值；实验时按粒度参数化）
DEFAULT_CHUNK_CHARS = 400
DEFAULT_OVERLAP_CHARS = 60
MIN_CHUNK_CHARS = 30

# 句末标点（中英文）与换行 —— 与 Java 侧同一个字符串
SENTENCE_ENDS = "。！？!?…\n"


def _last_sentence_end(text: str, frm: int, end: int) -> int:
    """在 (frm, end) 里找最后一个句末标点，切在它后面；找不到（超长句）就按字数硬切"""
    for i in range(end - 1, frm, -1):
        if text[i] in SENTENCE_ENDS:
            return i + 1
    return end


def _align_to_sentence_start(text: str, pos: int) -> int:
    """从 pos 起找到下一个句子的开头（跳过句末标点后的空白）"""
    for i in range(pos, len(text)):
        if text[i] in SENTENCE_ENDS:
            nxt = i + 1
            while nxt < len(text) and text[nxt].isspace():
                nxt += 1
            return nxt
    return pos


def strip(text: str) -> str:
    """对应 Java 的 String#strip（Unicode 空白），不是 Python 默认的 str.strip 语义差异点。

    Java 的 strip() 按 Character.isWhitespace 判定；Python 的 str.strip() 更宽
    （会把 \\x1c 之类也算空白）。小说正文里不会出现这些，所以直接用 Python 的，
    但这里显式写出来，提醒差异存在。
    """
    return text.strip()


def split(content: str, chunk_chars: int = DEFAULT_CHUNK_CHARS,
          overlap_chars: int = DEFAULT_OVERLAP_CHARS,
          with_offsets: bool = False):
    """把一章正文切成块。

    with_offsets=True 时每块附带它在**原文（strip 之后）**里的字符区间 [start, end)，
    实验用它判断「检索回来的块有没有覆盖到那句话」—— 这个判断跨粒度可比，
    而「是不是同一个块号」不可比（粒度不同块号自然不同）。
    """
    if not content or not content.strip():
        return []
    text = content.strip()
    chunks = []
    frm = 0
    seq = 0
    while frm < len(text):
        end = min(len(text), frm + chunk_chars)
        if end < len(text):
            end = _last_sentence_end(text, frm, end)
        raw = text[frm:end]
        piece = raw.strip()
        if len(piece) < MIN_CHUNK_CHARS and chunks:
            last = chunks.pop()
            merged = last[1] + piece
            # 并进前一块时区间要一起并（否则偏移对不上）
            chunks.append((last[0], merged, last[2], last[2] + len(merged)))
            if end >= len(text):
                break
        elif piece:
            # 前导空白会改变 offset，所以要按 strip 后重新定位
            lead = len(raw) - len(raw.lstrip())
            start = frm + lead
            chunks.append((seq, piece, start, start + len(piece)))
            seq += 1
        if end >= len(text):
            break
        frm = max(frm + 1, _align_to_sentence_start(text, max(end - overlap_chars, frm + 1)))
    if with_offsets:
        return chunks
    return [(c[0], c[1]) for c in chunks]


def sha16(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def verify(java_dump: str, content: str) -> bool:
    """拿 Java 探针的 dump 逐块比对：块数、长度、首尾片段、整块哈希。"""
    java = []
    for line in open(java_dump, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("CHUNKS="):
            continue
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) >= 5:
            java.append(parts)
    mine = split(content)
    if len(java) != len(mine):
        print(f"  ✗ 块数不同：Java {len(java)}，Python {len(mine)}")
        return False
    bad = 0
    for i, (j, m) in enumerate(zip(java, mine)):
        j_len, j_hash = int(j[1]), j[4]
        if j_len != len(m[1]) or j_hash != sha16(m[1]):
            bad += 1
            if bad <= 3:
                print(f"  ✗ 第 {i} 块不一致：Java len={j_len} hash={j_hash} / "
                      f"Python len={len(m[1])} hash={sha16(m[1])}")
    print(f"  比对 {len(java)} 块，不一致 {bad} 块")
    return bad == 0


GOLDEN_INPUT = "eval/fixtures/chunker-golden.txt"
GOLDEN_HASHES = "eval/fixtures/chunker-golden-hashes.txt"


def read_text(path: str) -> str:
    """读文本并归一化换行。

    锚点文件在 Windows 上可能被 checkout 成 CRLF，而哈希是按 LF 内容算的 ——
    不归一化的话，同一份文件在两台机器上算出两个结果，测试会莫名其妙地红。
    切块逻辑本身不关心 CRLF，所以归一化是对的（也适用于正文：库里的正文是 LF）。
    """
    return io.open(path, encoding="utf-8").read().replace("\r\n", "\n")


def golden_lines(content: str, chunk_chars: int = DEFAULT_CHUNK_CHARS,
                 overlap_chars: int = DEFAULT_OVERLAP_CHARS) -> list[str]:
    """生成锚点文件的内容。Java 侧 ChapterChunkerGoldenTest 读同一份文件做断言。"""
    chunks = split(content, chunk_chars=chunk_chars, overlap_chars=overlap_chars)
    lines = [f"# CHUNK_CHARS={chunk_chars} OVERLAP_CHARS={overlap_chars}",
             f"CHUNKS={len(chunks)}"]
    for seq, text in chunks:
        lines.append(f"{seq}\t{len(text)}\t{sha16(text)}")
    return lines


def _run_cli():
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "--verify":
        content = read_text(sys.argv[2])
        ok = verify(sys.argv[3], content)
        print("VERIFY " + ("PASS" if ok else "FAIL"))
        sys.exit(0 if ok else 1)
    if len(sys.argv) > 1 and sys.argv[1] == "--golden":
        content = read_text(GOLDEN_INPUT)
        lines = golden_lines(content)
        # newline="" 明确写 LF：文本模式在 Windows 上默认会写成 CRLF
        with io.open(GOLDEN_HASHES, "w", encoding="utf-8", newline="") as f:
            f.write("\n".join(lines) + "\n")
        print(f"锚点已写出：{GOLDEN_HASHES}（{len(lines) - 2} 块）")
        return
    if len(sys.argv) > 1 and sys.argv[1] == "--golden-check":
        content = read_text(GOLDEN_INPUT)
        want = read_text(GOLDEN_HASHES).splitlines()
        got = golden_lines(content)
        if want == got:
            print("GOLDEN PASS")
            sys.exit(0)
        print("GOLDEN FAIL")
        for a, b in zip(want, got):
            if a != b:
                print(f"  期望 {a!r} / 实际 {b!r}")
                break
        sys.exit(1)


if __name__ == "__main__":
    _run_cli()
