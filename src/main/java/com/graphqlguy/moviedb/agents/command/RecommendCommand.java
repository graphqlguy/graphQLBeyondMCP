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
 * Class 8: tools plus typed output, in two steps. Asking for both at once makes a
 * small model choose between grounding and shape, and it chooses badly, so this
 * command acts first and formats second.
 */
@Component
public class RecommendCommand implements AgentCommand {

    private final ObjectProvider<ChatModel> chatModels;

    public RecommendCommand(ObjectProvider<ChatModel> chatModels) {
        this.chatModels = chatModels;
    }

    @Override
    public String name() {
        return "recommend";
    }

    @Override
    public int order() {
        return 80;
    }

    @Override
    public String summary() {
        return "tools plus typed output; the answer arrives as List<MovieRecommendation>";
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
            System.out.println(ask.isBlank() ? "recommend needs a request"
                    : "No chat model available; is Ollama running?");
            return;
        }
        RunBudget budget = new RunBudget(8, 6);
        // Reads only, and anonymously: this class is about the shape of an answer,
        // so it leaves identity and the gate to the classes that teach them.
        List<OperationTool> reads = context.tools(null).stream()
                .filter(tool -> !tool.mutation())
                .toList();
        List<ToolCallback> callbacks =
                context.callbacks(reads, AuthSession.anonymous(), null, budget);
        var client = ChatClient.create(model);

        System.out.println("task    : " + ask);
        System.out.println();

        // Step one: ACT. Tools only, no output format in sight, so nothing competes
        // with grounding. The findings come back as plain text.
        String findings = client.prompt()
                .system("You research movies in the Movie Database. Use the tools"
                        + " to find real catalog data answering the request; never"
                        + " invent movies. Report the matching movies with their"
                        + " exact ids, titles, years, and ratings from tool results.")
                .user(ask)
                .tools(callbacks.toArray())
                .options(OllamaChatOptions.builder().model(context.modelName()))
                .call()
                .content();
        if (findings == null) {
            System.out.println("The model returned an empty response;"
                    + " stopping before the format step.");
            return;
        }
        System.out.println("findings (grounded, text): " + findings.replace("\n", " ").strip());
        System.out.println();

        // Step two: FORMAT. Entity only, no tools, so nothing competes with the
        // shape. The model restructures known-good facts.
        List<MovieRecommendation> answer = client.prompt()
                .system("Format the given findings as recommendations."
                        + " Use only movies present in the findings, with"
                        + " their exact data; add a one-sentence reason each."
                        + " If no usable movies appear in the findings, return"
                        + " an empty list; an empty list is a correct answer.")
                .user("Request: " + ask + "\nFindings: " + findings)
                .options(OllamaChatOptions.builder().model(context.modelName()))
                .call()
                .entity(new ParameterizedTypeReference<List<MovieRecommendation>>() {});
        // entity() is nullable: a model that answers with unparseable text yields
        // null here, and an empty list is the honest reading of that.
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
