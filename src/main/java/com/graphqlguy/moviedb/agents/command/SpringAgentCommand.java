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

    private final ObjectProvider<ChatModel> chatModels;

    public SpringAgentCommand(ObjectProvider<ChatModel> chatModels) {
        this.chatModels = chatModels;
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
                    .system(SYSTEM)
                    .user(turn)
                    // tools(Object...) replaces toolCallbacks(), deprecated in Spring AI 2.0
                    .tools(callbacks.toArray())
                    .options(OllamaChatOptions.builder().model(context.modelName()))
                    .advisors(MessageChatMemoryAdvisor.builder(memory).build())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId));

            // Tool search, off unless asked for. Spring AI's name for sending tool
            // definitions on demand instead of all of them up front. The catalog is still
            // handed over in full; the advisor decides how much of it the model sees,
            // and when.
            // Exactly one tool advisor may sit in the chain, and it owns the loop, so
            // whichever one this run uses is also where the ceiling has to go.
            if (toolSearch) {
                ToolIndex index = "lucene".equals(indexType) ? new LuceneToolIndex() : new RegexToolIndex();
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
}
