package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Class 7: the same concierge with Spring AI's ChatClient owning the loop. The
 * callbacks need no bridge, because they already implement Spring AI's own
 * ToolCallback interface, which is what Class 2 built them against.
 */
@Component
public class SpringAgentCommand implements AgentCommand {

    protected static final String SYSTEM = """
            You are a concierge for the Movie Database. Use the tools to answer
            and to act; never invent ids, and never claim an action succeeded
            without a tool result proving it. Ids are numeric strings like "2";
            a name is never an id. Before any write, first call the read tools
            to obtain every id the write needs.""";

    /**
     * Added to the system prompt only when tool search is on. The advisor already
     * appends one sentence of its own saying that {@code toolSearchTool} exists, and a
     * small model reads that sentence and still answers "the available tools do not
     * include..." after a single search. These three rules make the search insistent.
     */
    protected static final String SEARCH_RULES = """

            Most of the catalog is hidden until you search for it. When you cannot see
            a tool for what was asked, call toolSearchTool with the words of the
            request. If the tools it returns fit badly, search again with different
            words, one search per missing step. Report that something is impossible
            only after two searches have come back with nothing usable.""";

    private final ObjectProvider<ChatModel> chatModels;
    private final ObjectProvider<EmbeddingModel> embeddingModels;

    public SpringAgentCommand(ObjectProvider<ChatModel> chatModels,
                              ObjectProvider<EmbeddingModel> embeddingModels) {
        this.chatModels = chatModels;
        this.embeddingModels = embeddingModels;
    }

    @Override
    public String name() {
        return "spring-agent";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public String summary() {
        return "the ChatClient with the advisor-owned loop";
    }

    @Override
    public String prompt() {
        return "task";
    }

    @Override
    public void run(List<String> args, CommandContext context) {
        String task = String.join(" ", args);
        ChatModel springModel = chatModels.getIfAvailable();
        if (task.isBlank() || springModel == null) {
            System.out.println(task.isBlank() ? "a task is needed"
                    : "No chat model available; is Ollama running?");
            return;
        }
        String role = context.role();
        AuthSession auth = context.login();
        RunBudget budget = context.budget();
        ApprovalGate gate = new ApprovalGate(context.console());
        List<ToolCallback> callbacks = context.callbacks(context.tools(role), auth, gate, budget);

        boolean toolSearch = context.env().getProperty("agents.tool-search", Boolean.class, false);
        String indexType = context.env().getProperty("agents.tool-search-index", "regex");

        System.out.println("framework  : Spring AI (ChatClient, advisor-owned loop)");
        System.out.println("role       : " + role + " (" + callbacks.size() + " tools)");
        System.out.println("tool search: " + (toolSearch
                ? "tool search on, " + indexType + " index, definitions sent on demand"
                : "off, every definition sent on every call"));
        System.out.println("task       : " + task);
        System.out.println();

        // Memory is an advisor here, which is the framework being consistent with
        // itself: the loop belongs to the advisor chain, so what the loop remembers
        // belongs there too. One conversation id covers the whole exchange.
        // The loop belongs to the advisor, so the model-call ceiling has to belong
        // there too. A ToolCallingManager built with the run's own limits bounds how
        // far the advisor will go: each further model call needs another tool call to
        // justify it, so capping tool calls caps the loop.
        ToolCallingManager boundedManager = DefaultToolCallingManager.builder()
                .maxTotalToolCalls(budget.maxToolCalls())
                .build();

        ChatMemory memory = MessageWindowChatMemory.builder().build();
        String conversationId = "spring-agent-" + System.nanoTime();
        ChatClient client = ChatClient.create(springModel);

        String turn = task;
        while (!turn.isBlank()) {
            var spec = client.prompt()
                    .system(toolSearch ? SYSTEM + SEARCH_RULES : SYSTEM)
                    .user(turn)
                    // tools(Object...) replaces toolCallbacks(), deprecated in Spring AI 2.0
                    .tools(callbacks.toArray())
                    .options(OllamaChatOptions.builder().model(context.modelName()))
                    .advisors(MessageChatMemoryAdvisor.builder(memory).build())
                    .advisors(advisorParams -> advisorParams.param(ChatMemory.CONVERSATION_ID, conversationId));

            // Tool search, off unless asked for. Spring AI's name for sending tool
            // definitions on demand instead of all of them up front. The catalog is still
            // handed over in full; the advisor decides how much of it the model sees,
            // and when.
            // Exactly one tool advisor may sit in the chain, and it owns the loop, so
            // whichever one this run uses is also where the ceiling has to go.
            if (toolSearch) {
                ToolIndex index = indexFor(indexType);
                spec = spec.advisors(ToolSearchToolCallingAdvisor.builder()
                        .toolIndex(index)
                        .maxResults(5)
                        .toolCallingManager(boundedManager)
                        .build());
            } else {
                spec = spec.advisors(ToolCallingAdvisor.builder()
                        .toolCallingManager(boundedManager)
                        .build());
            }

            ChatResponse response = spec.call().chatResponse();
            Generation generation = response == null ? null : response.getResult();
            System.out.println("answer: " + (generation == null
                    ? "(the model returned an empty response)"
                    : generation.getOutput().getText()));
            if (response != null) {
                Usage usage = response.getMetadata().getUsage();
                System.out.println("usage : " + usage.getPromptTokens() + " prompt + "
                        + usage.getCompletionTokens() + " completion tokens"
                        + " (provider-reported, final call)");
            }
            System.out.println("budget: " + budget.summary());
            System.out.println("note  : the advisor owns the loop, so the model-call ceiling"
                    + " is enforced as the tool-call limit the manager carries;"
                    + " our own counter never sees those calls, which is why it reads zero");
            System.out.println();
            System.out.print("reply, or blank to return to the menu: ");
            turn = context.console().hasNextLine() ? context.console().nextLine().strip() : "";
            System.out.println();
        }
    }

    /**
     * The three indexes the advisor can rank with. The vector index needs an embedding
     * model, so an unreachable Ollama makes it unavailable, and this reports that. A
     * silent fall back to regex would leave the run measuring the wrong backend.
     */
    private ToolIndex indexFor(String indexType) {
        return switch (indexType) {
            case "lucene" -> new LuceneToolIndex();
            case "vector" -> {
                EmbeddingModel embeddings = embeddingModels.getIfAvailable();
                if (embeddings == null) {
                    throw new IllegalStateException("agents.tool-search-index is vector, "
                            + "which needs an embedding model; is Ollama running with "
                            + "the model named by spring.ai.ollama.embedding.model?");
                }
                yield new VectorToolIndex(SimpleVectorStore.builder(embeddings).build());
            }
            default -> new RegexToolIndex();
        };
    }
}
