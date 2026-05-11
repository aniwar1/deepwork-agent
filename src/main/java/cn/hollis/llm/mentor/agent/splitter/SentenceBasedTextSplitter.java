package cn.hollis.llm.mentor.agent.splitter;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public class SentenceBasedTextSplitter extends TextSplitter {

    private final int chunkSize;
    private final int overlap;
    private final int minSentenceLen;

    private static final Pattern SENTENCE_END = Pattern.compile("[。！？.!?;；\n]+");

    public SentenceBasedTextSplitter(int chunkSize, int overlap) {
        this(chunkSize, overlap, 5);
    }

    public SentenceBasedTextSplitter(int chunkSize, int overlap, int minSentenceLen) {
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
        this.minSentenceLen = minSentenceLen;
    }

    @Override
    protected List<String> splitText(String text) {
        if (StringUtils.isBlank(text)) {
            return Collections.emptyList();
        }

        List<String> sentences = splitIntoSentences(text);
        List<String> allChunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (StringUtils.isBlank(sentence)) {
                continue;
            }
            String trimmed = sentence.trim();
            if (trimmed.length() < minSentenceLen) {
                continue;
            }

            if (currentChunk.length() + trimmed.length() + 1 <= chunkSize) {
                if (currentChunk.length() > 0) {
                    currentChunk.append(" ");
                }
                currentChunk.append(trimmed);
            } else {
                if (currentChunk.length() > 0) {
                    allChunks.add(currentChunk.toString());

                    // overlap：取当前块末尾 overlap 个字符，保留完整句子
                    String overlapText = extractLastSentences(currentChunk.toString(), overlap);
                    currentChunk.setLength(0);
                    if (!overlapText.isEmpty()) {
                        currentChunk.append(overlapText);
                        if (currentChunk.length() > 0 && !currentChunk.toString().endsWith(" ")
                                && !currentChunk.toString().endsWith("。")
                                && !currentChunk.toString().endsWith(".")
                                && !currentChunk.toString().endsWith("!")
                                && !currentChunk.toString().endsWith("?")) {
                            currentChunk.append(" ");
                        }
                    }
                }

                // 如果单个句子就超长，按字符滑动（兜底）
                if (trimmed.length() > chunkSize) {
                    if (currentChunk.length() > 0) {
                        allChunks.add(currentChunk.toString());
                        currentChunk.setLength(0);
                    }
                    int pos = 0;
                    while (pos < trimmed.length()) {
                        int end = Math.min(pos + chunkSize, trimmed.length());
                        int cut = end;
                        if (end < trimmed.length()) {
                            cut = findLastSentenceEnd(trimmed, pos, end);
                        }
                        allChunks.add(trimmed.substring(pos, cut));
                        pos = cut;
                    }
                    continue;
                }

                currentChunk.append(trimmed);
            }
        }

        if (currentChunk.length() > 0) {
            allChunks.add(currentChunk.toString());
        }

        return allChunks;
    }

    private List<String> splitIntoSentences(String text) {
        String[] parts = SENTENCE_END.split(text);
        List<String> sentences = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (!part.isEmpty()) {
                sentences.add(part);
            }
        }
        return sentences;
    }

    private String extractLastSentences(String text, int maxChars) {
        if (text == null || text.isEmpty() || maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        String tail = text.substring(text.length() - maxChars);
        int boundary = findLastSentenceEnd(tail, 0, tail.length());
        return tail.substring(Math.max(0, boundary));
    }

    private int findLastSentenceEnd(String text, int start, int end) {
        int lastSep = end;
        for (int i = end - 1; i >= start; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        return end;
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
