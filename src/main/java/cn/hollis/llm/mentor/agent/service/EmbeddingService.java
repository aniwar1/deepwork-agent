package cn.hollis.llm.mentor.agent.service;

import cn.hollis.llm.mentor.agent.splitter.ChunkMetadataEnricher;
import cn.hollis.llm.mentor.agent.utils.DynamicPgVectorStoreFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EmbeddingService {
    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private DynamicPgVectorStoreFactory pgVectorStoreFactory;

    @Autowired
    private ChatModel chatModel;

    private PgVectorStore vectorStore;

    private static final int EMBEDDING_BATCH_SIZE = 9;

    // 检索参数
    private static final int EXPAND_QUERY_COUNT = 3;
    private static final int TOP_K_PER_QUERY = 5;
    private static final int CANDIDATE_MULTIPLIER = 2;
    private static final int MERGE_ADJACENT_COUNT = 1;

    @PostConstruct
    public void init() {
        vectorStore = pgVectorStoreFactory.createPgVectorStore("vector_file_info");
    }

    public List<float[]> embed(List<Document> documents) {
        return documents.stream().map(document -> embeddingModel.embed(document.getText())).collect(Collectors.toList());
    }

    public void embedAndStore(List<Document> documents) {
        for (int i = 0; i < documents.size(); i += EMBEDDING_BATCH_SIZE) {
            List<Document> batches = documents.subList(i, Math.min(i + EMBEDDING_BATCH_SIZE, documents.size()));
            vectorStore.doAdd(batches);
        }
    }

    /**
     * RAG 检索 - 根据文件ID和问题检索相关文档
     * 流程：Query压缩 → Query扩展 → 语义检索 → 重排序 → 相邻块合并 → 去重
     */
    public List<String> ragRetrieve(String fileId, String question) {
        log.info("RAG 检索开始: fileId={}, question={}", fileId, question);

        if (StringUtils.isBlank(fileId) || StringUtils.isBlank(question)) {
            log.warn("RAG 检索参数为空: fileId={}, question={}", fileId, question);
            return Collections.singletonList("检索参数不能为空");
        }

        try {
            Query query = Query.builder().text(question).build();

            // 1. Query 压缩重写
            ChatClient chatClient = ChatClient.builder(chatModel).build();
            CompressionQueryTransformer queryTransformer = CompressionQueryTransformer.builder()
                    .chatClientBuilder(chatClient.mutate())
                    .build();

            Query compressed = queryTransformer.transform(query);
            log.info("压缩重写后的Query: {}", compressed.text());

            // 2. Query 扩展（生成多个同义变体）
            QueryExpander queryExpander = MultiQueryExpander.builder()
                    .chatClientBuilder(chatClient.mutate())
                    .numberOfQueries(EXPAND_QUERY_COUNT)
                    .includeOriginal(true)
                    .build();

            List<Query> expandedQueries = queryExpander.expand(compressed);
            log.info("扩展后的Query: {}", expandedQueries);

            // 3. 语义向量检索 - 扩大召回候选集
            FilterExpressionBuilder builder = new FilterExpressionBuilder();
            Filter.Expression filter = builder.eq("fileid", fileId).build();

            Map<String, Document> candidateMap = new LinkedHashMap<>();

            for (Query eq : expandedQueries) {
                List<Document> docs = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(eq.text())
                                .topK(TOP_K_PER_QUERY * CANDIDATE_MULTIPLIER)
                                .filterExpression(filter)
                                .build());

                for (Document doc : docs) {
                    candidateMap.putIfAbsent(doc.getId(), doc);
                }
            }

            List<Document> candidates = new ArrayList<>(candidateMap.values());
            log.info("候选块数量: {}", candidates.size());

            // 4. 按段落顺序重排序 + 相邻块合并
            List<String> mergedResults = reorderAndMerge(candidates);

            log.info("RAG 检索完成: fileId={}, 原始候选={}, 合并后={}", fileId, candidates.size(), mergedResults.size());
            return mergedResults;

        } catch (Exception e) {
            log.error("RAG 检索失败: fileId={}, question={}", fileId, question, e);
            return Collections.singletonList("RAG 检索失败: " + e.getMessage());
        }
    }

    /**
     * 按 chunkId 顺序重排序，然后合并相邻的语义块
     */
    private List<String> reorderAndMerge(List<Document> candidates) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        // 4a. 按 chunkId 顺序重排序
        List<Document> sorted = candidates.stream()
                .sorted(Comparator.comparingInt(doc -> {
                    Object chunkId = doc.getMetadata().get("chunkId");
                    if (chunkId instanceof Number) {
                        return ((Number) chunkId).intValue();
                    }
                    try {
                        return Integer.parseInt(String.valueOf(chunkId));
                    } catch (Exception e) {
                        return Integer.MAX_VALUE;
                    }
                }))
                .collect(Collectors.toList());

        log.debug("重排序后 chunk 顺序: {}",
                sorted.stream()
                        .map(doc -> String.valueOf(doc.getMetadata().get("chunkId")))
                        .collect(Collectors.joining(", ")));

        // 4b. 相邻块合并
        List<String> results = new ArrayList<>();
        StringBuilder mergedText = new StringBuilder();
        int mergedCount = 0;

        for (Document doc : sorted) {
            String text = doc.getText();
            if (text == null || text.isBlank()) {
                continue;
            }

            // 使用带上下文桥接的构建方式
            String enrichedText = ChunkMetadataEnricher.buildChunkWithContext(doc);

            if (mergedText.length() == 0) {
                mergedText.append(enrichedText);
                mergedCount = 1;
            } else {
                // 超过合并阈值就输出当前块，开启新块
                if (mergedCount >= MERGE_ADJACENT_COUNT + 1) {
                    results.add(mergedText.toString());
                    mergedText.setLength(0);
                    mergedText.append(enrichedText);
                    mergedCount = 1;
                } else {
                    // 追加（带分隔符）
                    if (mergedText.length() + enrichedText.length() <= 1500) {
                        mergedText.append("\n---\n").append(enrichedText);
                        mergedCount++;
                    } else {
                        results.add(mergedText.toString());
                        mergedText.setLength(0);
                        mergedText.append(enrichedText);
                        mergedCount = 1;
                    }
                }
            }
        }

        if (mergedText.length() > 0) {
            results.add(mergedText.toString());
        }

        return results;
    }
}
