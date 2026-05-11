package cn.hollis.llm.mentor.agent.splitter;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Set;

@Slf4j
public class DynamicChunkStrategyFactory {

    private static final Map<String, ChunkStrategy> STRATEGY_MAP = Map.of(
            "pdf",   new ChunkStrategy(800, 100, ChunkType.PARAGRAPH),
            "docx",  new ChunkStrategy(800, 100, ChunkType.PARAGRAPH),
            "doc",   new ChunkStrategy(800, 100, ChunkType.PARAGRAPH),
            "txt",   new ChunkStrategy(300, 50,  ChunkType.SENTENCE),
            "md",    new ChunkStrategy(600, 80,  ChunkType.PARAGRAPH),
            "csv",   new ChunkStrategy(200, 30,  ChunkType.SENTENCE),
            "json",  new ChunkStrategy(400, 60,  ChunkType.SENTENCE)
    );

    private static final ChunkStrategy DEFAULT = new ChunkStrategy(500, 50, ChunkType.PARAGRAPH);

    private DynamicChunkStrategyFactory() {}

    public static ChunkStrategy getStrategy(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return DEFAULT;
        }
        ChunkStrategy strategy = STRATEGY_MAP.get(fileType.toLowerCase().trim());
        if (strategy == null) {
            return DEFAULT;
        }
        log.debug("文件类型 [{}] 选择策略: chunkSize={}, overlap={}, type={}",
                fileType, strategy.chunkSize, strategy.overlap, strategy.type);
        return strategy;
    }

    public static ChunkStrategy getDefault() {
        return DEFAULT;
    }

    public record ChunkStrategy(int chunkSize, int overlap, ChunkType type) {}

    public enum ChunkType {
        PARAGRAPH,
        SENTENCE
    }
}
