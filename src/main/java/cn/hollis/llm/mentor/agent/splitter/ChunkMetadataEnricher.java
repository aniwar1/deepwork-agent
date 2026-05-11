package cn.hollis.llm.mentor.agent.splitter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;

import java.util.List;

@Slf4j
public class ChunkMetadataEnricher {

    private static final int PREV_SUMMARY_MAX_CHARS = 60;
    private static final int NEXT_SUMMARY_MAX_CHARS = 60;

    private ChunkMetadataEnricher() {}

    public static void enrich(List<Document> chunks, String fileId) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put("chunkId", i);
            chunk.getMetadata().put("totalChunks", total);
            chunk.getMetadata().put("fileid", fileId);

            // 前驱摘要：前一个 chunk 的末尾，作为语义桥接
            if (i > 0) {
                Document prev = chunks.get(i - 1);
                String prevSummary = extractSummary(prev.getText(), PREV_SUMMARY_MAX_CHARS);
                chunk.getMetadata().put("prev_summary", prevSummary);
            } else {
                chunk.getMetadata().put("prev_summary", "");
            }

            // 后继摘要：后一个 chunk 的开头，作为悬念提示
            if (i < total - 1) {
                Document next = chunks.get(i + 1);
                String nextSummary = extractSummary(next.getText(), NEXT_SUMMARY_MAX_CHARS);
                chunk.getMetadata().put("next_summary", nextSummary);
            } else {
                chunk.getMetadata().put("next_summary", "");
            }
        }

        log.info("Chunk 元数据丰富化完成: fileId={}, totalChunks={}", fileId, total);
    }

    private static String extractSummary(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        String cut = trimmed.substring(0, maxChars);
        int lastSep = Math.max(
                cut.lastIndexOf('。'),
                Math.max(cut.lastIndexOf('，'), Math.max(cut.lastIndexOf(' '), cut.lastIndexOf('.')))
        );
        if (lastSep > maxChars / 2) {
            return trimmed.substring(0, lastSep + 1);
        }
        return cut + "...";
    }

    public static String buildChunkWithContext(Document chunk) {
        String prevSummary = (String) chunk.getMetadata().getOrDefault("prev_summary", "");
        String nextSummary = (String) chunk.getMetadata().getOrDefault("next_summary", "");
        String text = chunk.getText();

        StringBuilder sb = new StringBuilder();
        if (prevSummary != null && !prevSummary.isEmpty()) {
            sb.append("【前文】").append(prevSummary).append("\n");
        }
        sb.append(text);
        if (nextSummary != null && !nextSummary.isEmpty()) {
            sb.append("\n【续】").append(nextSummary);
        }
        return sb.toString();
    }
}
