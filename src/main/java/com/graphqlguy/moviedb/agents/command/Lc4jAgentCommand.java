package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.catalog.GraphQlToolCallback;
import com.graphqlguy.moviedb.agents.langchain.LangChainToolBridge;
import com.graphqlguy.moviedb.agents.langchain.MovieConcierge;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Class 5: the same catalog and the same safety layer, with LangChain4j owning
 * the loop. The callbacks underneath are the ones Class 4 built, unchanged.
 */
@Component
public class Lc4jAgentCommand implements AgentCommand {

    @Override
    public String name() {
        return "lc4j-agent";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public String summary() {
        return "the same task through LangChain4j's AiServices";
    }

    @Override
    public String prompt() {
        return "task";
    }

    @Override
    public void run(List<String> args, CommandContext context) {
        String task = String.join(" ", args);
        if (task.isBlank()) {
            System.out.println("lc4j-agent needs a task");
            return;
        }
        String role = context.role();
        AuthSession auth = context.login();
        RunBudget budget = context.budget();
        ApprovalGate gate = new ApprovalGate(context.console());

        var bridged = new LangChainToolBridge().bridge(
                context.tools(role),
                tool -> new GraphQlToolCallback(tool, context.endpoint(), auth, gate, budget));
        var chatModel = OllamaChatModel.builder()
                .baseUrl(context.env().getProperty("spring.ai.ollama.base-url", "http://localhost:11434"))
                .modelName(context.modelName())
                .build();
        MovieConcierge concierge = AiServices.builder(MovieConcierge.class)
                .chatModel(chatModel)
                .tools(bridged)
                // The framework owns this loop, so the model-call ceiling must live in
                // the framework; the tool-call ceiling stays in the callback, where it
                // works whoever is driving.
                .maxToolCallingRoundTrips(
                        context.env().getProperty("agents.budget.max-model-calls", Integer.class, 8))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();

        System.out.println("framework : LangChain4j (AiServices)");
        System.out.println("role      : " + role + " (" + bridged.size() + " tools)");
        System.out.println("user      : " + (context.username().isBlank()
                ? "(anonymous)" : context.username()));
        System.out.println("task      : " + task);
        System.out.println();

        // The framework already keeps the history: MessageWindowChatMemory above is
        // what makes a second call continue the first. Asking for a reply is all
        // this command has to add, and a task the model answers with a question is
        // no longer a dead end.
        String turn = task;
        while (!turn.isBlank()) {
            String answer = concierge.chat(turn);
            System.out.println();
            System.out.println("answer: " + answer);
            System.out.println("budget: " + budget.summary());
            System.out.println();
            System.out.print("reply, or blank to return to the menu: ");
            turn = context.console().hasNextLine() ? context.console().nextLine().strip() : "";
            System.out.println();
        }
    }
}
