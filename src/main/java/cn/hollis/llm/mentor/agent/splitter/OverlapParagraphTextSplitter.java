package cn.hollis.llm.mentor.agent.splitter;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OverlapParagraphTextSplitter extends TextSplitter {

    private final int chunkSize;
    private final int overlap;

    public OverlapParagraphTextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 不能大于等于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    protected List<String> splitText(String text) {
        if (StringUtils.isBlank(text)) {
            return Collections.emptyList();
        }

        String[] paragraphs = text.split("\\n+");
        List<String> allChunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (StringUtils.isBlank(paragraph)) {
                continue;
            }

            // 短段落：直接尝试追加到当前块
            if (paragraph.length() <= chunkSize) {
                if (currentChunk.length() + paragraph.length() <= chunkSize) {
                    if (currentChunk.length() > 0) {
                        currentChunk.append("\n");
                    }
                    currentChunk.append(paragraph);
                } else {
                    // 当前块满了，先 flush
                    if (currentChunk.length() > 0) {
                        allChunks.add(currentChunk.toString().trim());
                        currentChunk.setLength(0);
                    }
                    // 直接添加段落（它本身小于 chunkSize）
                    allChunks.add(paragraph.trim());
                }
                continue;
            }

            // 长段落：按句子边界切分，避免在句子中间截断
            String[] sentences = paragraph.split("[。！？.!?\n]+");
            for (String sentence : sentences) {
                if (StringUtils.isBlank(sentence)) {
                    continue;
                }
                String trimmed = sentence.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                int sentenceLen = trimmed.length();

                // 句子本身超长，做字符级滑动（兜底）
                if (sentenceLen > chunkSize) {
                    if (currentChunk.length() > 0) {
                        allChunks.add(currentChunk.toString().trim());
                        currentChunk.setLength(0);
                    }
                    // 按 chunkSize 滑动，overlap 取末尾完整词/句
                    int pos = 0;
                    while (pos < sentenceLen) {
                        int end = Math.min(pos + chunkSize, sentenceLen);
                        int cut = end;
                        if (end < sentenceLen) {
                            cut = findWordBoundary(trimmed, pos, end);
                        }
                        allChunks.add(trimmed.substring(pos, cut));
                        pos = cut - Math.min(overlap, cut - pos);
                    }
                    continue;
                }

                // 普通句子：尝试追加
                if (currentChunk.length() + sentenceLen + 1 <= chunkSize) {
                    if (currentChunk.length() > 0) {
                        currentChunk.append(" ");
                    }
                    currentChunk.append(trimmed);
                } else {
                    // 当前块满了，flush
                    if (currentChunk.length() > 0) {
                        allChunks.add(currentChunk.toString().trim());
                    }

                    // overlap：取上一块末尾的完整句子作为桥接
                    if (overlap > 0 && !allChunks.isEmpty()) {
                        String lastChunk = allChunks.get(allChunks.size() - 1);
                        String overlapText = extractTailSentences(lastChunk, overlap);
                        currentChunk.setLength(0);
                        if (!overlapText.isEmpty()) {
                            currentChunk.append(overlapText).append(" ");
                        }
                    } else {
                        currentChunk.setLength(0);
                    }

                    // 如果单个句子仍然超过 chunkSize（边界情况），截断它
                    if (sentenceLen <= chunkSize) {
                        currentChunk.append(trimmed);
                    } else {
                        currentChunk.append(trimmed, 0, findWordBoundary(trimmed, 0, chunkSize));
                    }
                }
            }
        }

        if (currentChunk.length() > 0) {
            allChunks.add(currentChunk.toString().trim());
        }

        return allChunks;
    }

    private int findWordBoundary(String text, int start, int end) {
        if (end >= text.length()) {
            return text.length();
        }
        for (int i = end - 1; i >= start; i--) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == ',' || c == '，' || c == '、') {
                return i + 1;
            }
        }
        return end;
    }

    private String extractTailSentences(String text, int maxChars) {
        if (text == null || text.isEmpty() || maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        String tail = text.substring(text.length() - maxChars);
        int boundary = findSentenceBoundary(tail);
        return tail.substring(Math.max(0, boundary));
    }

    private int findSentenceBoundary(String text) {
        int lastSep = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                lastSep = i;
            }
        }
        return lastSep + 1;
    }

    @Override
    public List<Document> apply(List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return Collections.emptyList();
        }

        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            List<String> chunks = splitText(doc.getText());
            for (String chunk : chunks) {
                result.add(new Document(chunk));
            }
        }
        return result;
    }
}
