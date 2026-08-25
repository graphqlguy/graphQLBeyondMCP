package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
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
        ApprovalGate gate = new ApprovalGate();
        List<ToolCallback> callbacks = context.callbacks(context.tools(role), auth, gate, budget);

        var spec = ChatClient.create(springModel).prompt()
                .system(SYSTEM)
                .user(task)
                // tools(Object...) replaces toolCallbacks(), deprecated in Spring AI 2.0
                .tools(callbacks.toArray())
                .options(OllamaChatOptions.builder().model(context.modelName()));

        System.out.println("framework : Spring AI (ChatClient, advisor-owned loop)");
        System.out.println("role      : " + role + " (" + callbacks.size() + " tools)");
        System.out.println("task      : " + task);
        System.out.println();

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
    }
}
