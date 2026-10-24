package com.huxizhen.travel.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 按行切分文档，逐行对齐 Python 的 {@code vector_service.split_document}。
 *
 * <p>订单/票据是**行导向**文档：按固定长度硬切会把"入住 2026-10-02"劈成"入住"，那一段
 * 就再也回答不了任何问题。所以切片由整行拼成，只有在单行本身超长时才按字符窗口切，
 * 并让相邻窗口共享 {@code overlap} 个字符。
 */
public final class DocumentSplitter {

    public static final int CHUNK_TARGET_CHARS = 420;
    public static final int CHUNK_OVERLAP_CHARS = 60;

    private static final String ZERO_WIDTH = "\u200b\u200c\u200d\ufeff";

    private DocumentSplitter() {
    }

    public record Piece(String text, int charStart, int charEnd, int index) {
    }

    private record Line(String text, int start, int end) {
    }

    public static List<Piece> split(String text) {
        return split(text, CHUNK_TARGET_CHARS, CHUNK_OVERLAP_CHARS);
    }

    public static List<Piece> split(String text, int target, int overlap) {
        List<Line> lines = cleanLines(text);
        if (lines.isEmpty()) {
            return List.of();
        }

        List<Piece> pieces = new ArrayList<>();
        List<Line> current = new ArrayList<>();

        for (Line line : lines) {
            if (line.text().length() > target) {
                flush(pieces, current);
                current = new ArrayList<>();
                int stride = Math.max(1, target - overlap);
                for (int offset = 0; offset < line.text().length(); offset += stride) {
                    int end = Math.min(offset + target, line.text().length());
                    String window = line.text().substring(offset, end);
                    if (window.isBlank()) {
                        break;
                    }
                    pieces.add(new Piece(
                            window, line.start() + offset, line.start() + offset + window.length(), pieces.size()));
                    if (offset + target >= line.text().length()) {
                        break;
                    }
                }
                continue;
            }
            if (!current.isEmpty() && currentLength(current) + line.text().length() > target) {
                Line carried = current.get(current.size() - 1);
                if (carried.text().length() > overlap) {
                    carried = null;
                }
                flush(pieces, current);
                // 末尾那行重新出现在下一片开头：被边界劈开的事实（"订单号" 与它的值）
                // 至少还能从相邻两片之一里检索到。
                current = new ArrayList<>();
                if (carried != null) {
                    current.add(carried);
                }
            }
            current.add(line);
        }
        flush(pieces, current);
        return pieces;
    }

    private static void flush(List<Piece> pieces, List<Line> current) {
        if (current.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < current.size(); index++) {
            if (index > 0) {
                body.append('\n');
            }
            body.append(current.get(index).text());
        }
        pieces.add(new Piece(
                body.toString(),
                current.get(0).start(),
                current.get(current.size() - 1).end(),
                pieces.size()));
    }

    private static int currentLength(List<Line> current) {
        int total = 0;
        for (Line line : current) {
            total += line.text().length() + 1;
        }
        return total;
    }

    /** 非空行及其在归一化文本里的偏移；顺带去掉 OCR 常见的零宽字符。 */
    private static List<Line> cleanLines(String text) {
        String normalized = (text == null ? "" : text).replace("\r\n", "\n").replace("\r", "\n");
        for (char character : ZERO_WIDTH.toCharArray()) {
            normalized = normalized.replace(String.valueOf(character), "");
        }
        List<Line> lines = new ArrayList<>();
        int cursor = 0;
        for (String raw : normalized.split("\n", -1)) {
            String line = raw.strip();
            if (!line.isEmpty()) {
                int leading = raw.length() - raw.stripLeading().length();
                int start = cursor + leading;
                lines.add(new Line(line, start, start + line.length()));
            }
            cursor += raw.length() + 1;
        }
        return lines;
    }
}
