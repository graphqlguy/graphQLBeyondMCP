package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Class 7: the same client, streaming the answer token by token. The tool calls
 * still happen in the middle of that stream, and the reader sees the answer
 * resume once they are done.
 */
@Component
public class SpringStreamCommand implements AgentCommand {

    private final ObjectProvider<ChatModel> chatModels;

    public SpringStreamCommand(ObjectProvider<ChatModel> chatModels) {
        this.chatModels = chatModels;
    }

    @Override
    public String name() {
        return "spring-stream";
    }

    @Override
    public int order() {
        return 71;
    }

    @Override
    public String summary() {
        return "the same client, streaming the answer token by token";
    }

    @Override
    public String prompt() {
        return "question";
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
        List<ToolCallback> callbacks =
                context.callbacks(context.tools(role), auth, new ApprovalGate(), budget);

        System.out.println("framework : Spring AI (ChatClient, streaming)");
        System.out.println("role      : " + role + " (" + callbacks.size() + " tools)");
        System.out.println("task      : " + task);
        System.out.println();

        ChatClient.create(springModel).prompt()
                .system(SpringAgentCommand.SYSTEM)
                .user(task)
                .tools(callbacks.toArray())
                .options(OllamaChatOptions.builder().model(context.modelName()))
                .stream()
                .content()                 // Flux<String>: the answer, token by token
                .doOnNext(System.out::print)
                .blockLast();
        System.out.println();
        System.out.println("budget: " + budget.summary());
    }
}
