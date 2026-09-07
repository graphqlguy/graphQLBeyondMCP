package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import com.graphqlguy.moviedb.agents.structured.MovieRecommendation;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Class 8: the obvious composition, kept so the failure can be reproduced. Tools and
 * the entity terminal are asked for on the same call, which makes grounding and shape
 * compete for one decision. Run this next to {@code recommend} and read the tool-call
 * count in the last line: this version usually answers without calling anything.
 */
@Component
public class RecommendOneCallCommand implements AgentCommand {

    private final ObjectProvider<ChatModel> chatModels;

    public RecommendOneCallCommand(ObjectProvider<ChatModel> chatModels) {
        this.chatModels = chatModels;
    }

    @Override
    public String name() {
        return "recommend-one";
    }

    @Override
    public int order() {
        return 79;
    }

    @Override
    public String summary() {
        return "one call, tools AND format together; the failure this class is about";
    }

    @Override
    public String prompt() {
        return "request, e.g. two dramas worth my evening";
    }

    @Override
    public void run(List<String> args, CommandContext context) {
        String ask = String.join(" ", args);
        ChatModel model = chatModels.getIfAvailable();
        if (ask.isBlank() || model == null) {
            System.out.println(ask.isBlank() ? "recommend-one needs a request"
                    : "No chat model available; is Ollama running?");
            return;
        }
        RunBudget budget = new RunBudget(8, 6);
        List<OperationTool> reads = context.tools(null).stream()
                .filter(tool -> !tool.mutation())
                .toList();
        List<ToolCallback> callbacks =
                context.callbacks(reads, AuthSession.anonymous(), null, budget);

        System.out.println("task    : " + ask);
        System.out.println();

        // One call, both jobs. The system prompt asks for grounding, the entity
        // terminal appends the format instructions, and the model picks one.
        List<MovieRecommendation> answer = ChatClient.create(model).prompt()
                .system("You research movies in the Movie Database. Use the tools to"
                        + " find real catalog data answering the request; never invent"
                        + " movies. Recommend movies with their exact ids, titles,"
                        + " years and ratings from tool results, one sentence each.")
                .user(ask)
                .tools(callbacks.toArray())
                .options(OllamaChatOptions.builder().model(context.modelName()))
                .call()
                .entity(new ParameterizedTypeReference<List<MovieRecommendation>>() {});
        answer = answer == null ? List.of() : answer;

        for (MovieRecommendation rec : answer) {
            System.out.printf("- %s (%d, rated %.1f): %s%n",
                    rec.title(), rec.releaseYear(), rec.rating(), rec.reason());
        }
        System.out.println();
        System.out.println("type   : List<MovieRecommendation>, " + answer.size()
                + " typed entries; every field is a Java value, never text to parse");
        System.out.println("budget : " + budget.summary());
    }
}
