package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Class 7: the live counterpart to {@code tool-bench}. Real agent runs, one per
 * query per backend, with the framework's loop instrumented so each run reports
 * how many model calls it took, how many searches the model made, how many tokens
 * the provider counted, and whether it ever reached every tool the task needs.
 * <p>
 * The instrument is a {@link ToolCallingManager} that delegates to the real one and
 * counts on the way past. Every round of the framework's loop passes through it.
 */
@Component
public class ToolLiveBenchCommand implements AgentCommand {

    private static final String SYSTEM = SpringAgentCommand.SYSTEM;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_GENERATED_TOKENS = 4096;

    /**
     * The seven columns of the matrix. The first four run the framework as it comes,
     * where the only instruction to search is the one sentence the advisor appends
     * for itself. The last three repeat the search backends with our own insistent
     * rules added to the system prompt.
     */
    private static final List<Column> COLUMNS = List.of(
            new Column("off", "off", false),
            new Column("regex", "regex", false),
            new Column("lucene", "lucene", false),
            new Column("vector", "vector", false),
            new Column("regex+rules", "regex", true),
            new Column("lucene+rules", "lucene", true),
            new Column("vector+rules", "vector", true));

    /** One column of the matrix: which index ranks the catalog, and how hard we push. */
    private record Column(String label, String index, boolean insistent) {
    }

    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectProvider<EmbeddingModel> embeddingModels;

    public ToolLiveBenchCommand(ObjectProvider<ChatModel> chatModels,
                                ObjectProvider<EmbeddingModel> embeddingModels) {
        this.chatModels = chatModels;
        this.embeddingModels = embeddingModels;
    }

    @Override
    public String name() {
        return "tool-live-bench";
    }

    @Override
    public int order() {
        return 74;
    }

    @Override
    public String summary() {
        return "run real agent tasks through each backend, counting rounds, searches and tokens";
    }

    @Override
    public String prompt() {
        return "runs, then definitions per search, then optional column names "
                    + "(blank for 1 run, " + DEFAULT_TOP_K + " definitions, every column)";
    }

    @Override
    public void run(List<String> args, CommandContext context) throws Exception {
        int runs = args.isEmpty() || args.get(0).isBlank() ? 1 : Integer.parseInt(args.get(0));
        int topK = args.size() > 1 && args.get(1).matches("\\d+")
                ? Integer.parseInt(args.get(1)) : DEFAULT_TOP_K;
        int firstLabel = args.size() > 1 && args.get(1).matches("\\d+") ? 2 : 1;
        List<String> wanted = args.size() > firstLabel ? args.subList(firstLabel, args.size()) : List.of();
        List<Column> columns = wanted.isEmpty() ? COLUMNS
                : COLUMNS.stream().filter(column -> wanted.contains(column.label())).toList();
        if (columns.isEmpty()) {
            System.out.println("No column matched " + wanted + "; the names are "
                    + COLUMNS.stream().map(Column::label).toList());
            return;
        }
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            System.out.println("No chat model available; is Ollama running?");
            return;
        }
        List<Query> queries = loadQueries();
        String role = context.role();
        List<OperationTool> tools = context.tools(role);
        AuthSession auth = context.login();

        System.out.println("catalog  : " + tools.size() + " tools");
        System.out.println("runs     : " + runs + " per query per column, "
                + (runs * queries.size() * columns.size()) + " agent runs in total");
        System.out.println("model    : " + context.modelName());
        System.out.println("top-k    : " + topK + " definitions returned per search");
        System.out.println();
        System.out.printf("%-14s %-40s %6s %8s %9s %10s %-34s %s%n",
                "backend", "query", "rounds", "searches", "tokens", "needed", "tools actually called", "answer");

        for (Column column : columns) {
            String backend = column.label();
            for (Query query : queries) {
                for (int attempt = 1; attempt <= runs; attempt++) {
                    final int run = attempt;
                    Counting counter = new Counting(DefaultToolCallingManager.builder()
                            .maxTotalToolCalls(12)
                            .build());
                    RunBudget budget = new RunBudget(8, 12);
                    List<ToolCallback> callbacks =
                            context.callbacks(tools, auth, null, budget);
                    var spec = ChatClient.create(model).prompt()
                            .system(column.insistent() ? SYSTEM + SpringAgentCommand.SEARCH_RULES : SYSTEM)
                            .user(query.text())
                            .tools(callbacks.toArray())
                            // A ceiling on generation. Without it one degenerate reply can
                            // repeat itself for hours and hold the model's only slot, which
                            // stalls every run behind it.
                            .options(OllamaChatOptions.builder()
                                    .model(context.modelName())
                                    .numPredict(MAX_GENERATED_TOKENS))
                            .advisors(advisorParams -> advisorParams.param(ChatMemory.CONVERSATION_ID,
                                    backend + "-" + query.text().hashCode() + "-" + run));
                    ToolIndex index = indexFor(column.index());
                    spec = index == null
                            ? spec.advisors(ToolCallingAdvisor.builder()
                                    .toolCallingManager(counter).build())
                            : spec.advisors(ToolSearchToolCallingAdvisor.builder()
                                    .toolIndex(index).maxResults(topK)
                                    .toolCallingManager(counter).build());

                    long tokens;
                    String outcome;
                    try {
                        ChatResponse response = spec.call().chatResponse();
                        Usage usage = response == null ? null : response.getMetadata().getUsage();
                        tokens = counter.tokens
                                + (usage == null ? 0 : usage.getPromptTokens() + usage.getCompletionTokens());
                        outcome = response == null || response.getResult() == null ? "(empty response)"
                                : response.getResult().getOutput().getText();
                    } catch (Exception failure) {
                        tokens = counter.tokens;
                        outcome = "FAILED: " + failure.getClass().getSimpleName() + " " + failure.getMessage();
                    }
                    long found = query.needs().stream().filter(counter.executed::contains).count();
                    System.out.printf("%-14s %-40s %6d %8d %9d %6d/%-3d %-34s %s%n",
                            backend, trim(query.text(), 40), counter.rounds + 1,
                            counter.searches, tokens, found, query.needs().size(),
                            counter.executed.isEmpty() ? "(no tools called)"
                                    : trim(String.join(",", counter.executed), 34),
                            trim(outcome.replace("\n", " "), 60));
                }
            }
        }
    }

    private ToolIndex indexFor(String backend) {
        return switch (backend) {
            case "regex" -> new RegexToolIndex();
            case "lucene" -> new LuceneToolIndex();
            case "vector" -> {
                EmbeddingModel embeddings = embeddingModels.getIfAvailable();
                yield embeddings == null ? null
                        : new VectorToolIndex(SimpleVectorStore.builder(embeddings).build());
            }
            default -> null;
        };
    }

    /** Delegates every round to the real manager, and counts what goes past. */
    private static final class Counting implements ToolCallingManager {

        private final ToolCallingManager delegate;
        private final Set<String> executed = new LinkedHashSet<>();
        private int rounds;
        private int searches;
        private long tokens;

        private Counting(ToolCallingManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            return delegate.resolveToolDefinitions(options);
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
            rounds++;
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                tokens += usage.getPromptTokens() + usage.getCompletionTokens();
            }
            if (response.getResult() != null) {
                response.getResult().getOutput().getToolCalls().forEach(call -> {
                    if (call.name().equals("toolSearchTool")) {
                        searches++;
                    } else {
                        executed.add(call.name());
                    }
                });
            }
            return delegate.executeToolCalls(prompt, response);
        }
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
