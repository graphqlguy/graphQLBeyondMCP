package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchTool;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class 7: what tool search costs and what it misses, measured over a file of
 * labelled queries. For each query the benchmark counts the tool-definition tokens
 * each backend would put in the prompt, and checks whether every tool the task
 * needs came back. Cheap retrieval that leaves out one required tool is not cheap:
 * the agent cannot finish the task.
 * <p>
 * No model runs here. What varies between backends is which definitions reach the
 * prompt, so that is what this counts, and counting it makes the numbers repeatable.
 */
@Component
public class ToolBenchCommand implements AgentCommand {

    private static final int DEFAULT_TOP_K = 5;

    private final ObjectProvider<EmbeddingModel> embeddingModels;

    public ToolBenchCommand(ObjectProvider<EmbeddingModel> embeddingModels) {
        this.embeddingModels = embeddingModels;
    }

    @Override
    public String name() {
        return "tool-bench";
    }

    @Override
    public int order() {
        return 73;
    }

    @Override
    public String summary() {
        return "measure what each tool-search backend costs, and what it fails to find";
    }

    @Override
    public String prompt() {
        return "definitions returned per search (blank for " + DEFAULT_TOP_K + ")";
    }

    @Override
    public void run(List<String> args, CommandContext context) throws Exception {
        int topK = args.isEmpty() || args.get(0).isBlank()
                ? DEFAULT_TOP_K : Integer.parseInt(args.get(0));
        List<Query> queries = loadQueries();
        String role = context.role();
        List<OperationTool> tools = context.tools(role);
        Map<String, Integer> costByTool = new LinkedHashMap<>();
        List<ToolReference> references = new ArrayList<>();
        for (OperationTool tool : tools) {
            costByTool.put(tool.name(), tool.tokenCount());
            references.add(ToolReference.builder()
                    .toolName(tool.name()).summary(tool.description()).build());
        }
        int catalogTokens = costByTool.values().stream().mapToInt(Integer::intValue).sum();
        int overhead = searchOverhead();

        System.out.println("catalog  : " + tools.size() + " tools, " + catalogTokens
                + " tokens if every definition is advertised up front");
        System.out.println("overhead : " + overhead + " tokens tool search adds to every request");
        System.out.println("top-k    : " + topK + " definitions returned per search");
        System.out.println();
        System.out.println("One search per query, without an agent or a second attempt. Each cell is the");
        System.out.println("tool-definition tokens that search would put in the request, and how many");
        System.out.println("of the tools the task needs came back in those " + topK + " results.");
        System.out.println();

        Map<String, ToolIndex> indexes = new LinkedHashMap<>();
        indexes.put("regex", new RegexToolIndex());
        indexes.put("lucene", new LuceneToolIndex());
        EmbeddingModel embeddings = embeddingModels.getIfAvailable();
        if (embeddings != null) {
            indexes.put("vector", new VectorToolIndex(
                    SimpleVectorStore.builder(embeddings).build()));
        } else {
            System.out.println("(no embedding model available, so the vector index is skipped)");
        }
        for (Map.Entry<String, ToolIndex> entry : indexes.entrySet()) {
            entry.getValue().indexTools(entry.getKey(), references);
        }

        System.out.printf("%-50s %8s", "query", "off");
        for (String name : indexes.keySet()) {
            System.out.printf(" %16s", name);
        }
        System.out.println();

        Map<String, int[]> totals = new LinkedHashMap<>();
        indexes.keySet().forEach(name -> totals.put(name, new int[] {0, 0, 0}));
        for (Query query : queries) {
            System.out.printf("%-50s %8d", trim(query.text(), 50), catalogTokens);
            for (Map.Entry<String, ToolIndex> entry : indexes.entrySet()) {
                List<ToolReference> hits = entry.getValue()
                        .search(new ToolSearchRequest(entry.getKey(), query.text(), topK, null))
                        .toolReferences();
                Set<String> found = new LinkedHashSet<>();
                int tokens = overhead;
                for (ToolReference hit : hits) {
                    found.add(hit.toolName());
                    tokens += costByTool.getOrDefault(hit.toolName(), 0);
                }
                long needed = query.needs().stream().filter(found::contains).count();
                int[] total = totals.get(entry.getKey());
                total[0] += tokens;
                total[1] += (int) needed;
                total[2] += query.needs().size();
                System.out.printf(" %8d %3d/%-3d", tokens, needed, query.needs().size());
            }
            System.out.println();
        }

        System.out.println();
        System.out.printf("%-50s %8d", "mean tokens, and tools found of tools needed", catalogTokens);
        for (Map.Entry<String, int[]> entry : totals.entrySet()) {
            int[] total = entry.getValue();
            System.out.printf(" %8d %3d/%-3d", total[0] / queries.size(), total[1], total[2]);
        }
        System.out.println();
    }

    /** What the advisor adds to every request before it saves anything. */
    private int searchOverhead() throws Exception {
        Encoding encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        String suffix = new DefaultResourceLoader()
                .getResource("classpath:/DEFAULT_SYSTEM_PROMPT_SUFFIX.md")
                .getContentAsString(StandardCharsets.UTF_8);
        ToolCallback searchTool = MethodToolCallbackProvider.builder()
                .toolObjects(new ToolSearchTool(new RegexToolIndex(), DEFAULT_TOP_K))
                .build().getToolCallbacks()[0];
        ToolDefinition definition = searchTool.getToolDefinition();
        return encoding.countTokens(suffix) + encoding.countTokens(
                definition.name() + " " + definition.description() + " " + definition.inputSchema());
    }

    private List<Query> loadQueries() throws Exception {
        List<Query> queries = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/main/resources/tool-bench-queries.tsv"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t");
            queries.add(new Query(parts[0], List.of(parts).subList(1, parts.length)));
        }
        return queries;
    }

    private static String trim(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "…";
    }

    private record Query(String text, List<String> needs) {
    }
}
