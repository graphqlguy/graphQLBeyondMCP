package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.agent.AgentRunner;
import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Class 4: the whole loop with every control attached. The login happens once,
 * before any tool exists; the gate and the budget are handed to every callback,
 * so all three apply to the catalog by construction.
 */
@Component
public class RunAgentCommand implements AgentCommand {

    private final ObjectProvider<ChatModel> chatModels;

    public RunAgentCommand(ObjectProvider<ChatModel> chatModels) {
        this.chatModels = chatModels;
    }

    @Override
    public String name() {
        return "agent";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public String summary() {
        return "run the agent loop: login, approval gate, and run budget";
    }

    @Override
    public String prompt() {
        return "task, e.g. add The Matrix to my To watch this weekend watchlist";
    }

    @Override
    public void run(List<String> args, CommandContext context) {
        String task = String.join(" ", args);
        ChatModel chatModel = chatModels.getIfAvailable();
        if (task.isBlank() || chatModel == null) {
            System.out.println(task.isBlank()
                    ? "agent needs a task, e.g.: agent add movie 1 to my watchlist"
                    : "No chat model available; is Ollama running?");
            return;
        }
        String role = context.role();
        AuthSession auth = context.login();
        RunBudget budget = context.budget();
        ApprovalGate gate = new ApprovalGate(context.console());
        List<ToolCallback> tools = context.callbacks(context.tools(role), auth, gate, budget);

        System.out.println("role    : " + role + " (" + tools.size() + " tools)");
        System.out.println("user    : " + (context.username().isBlank()
                ? "(anonymous)" : context.username()));
        System.out.println("task    : " + task);
        System.out.println();

        // One runner for the whole exchange: a model that answers with a question
        // gets an answer back, and the budget keeps counting across the turns.
        AgentRunner runner = new AgentRunner(chatModel, context.modelName());
        String turn = task;
        while (!turn.isBlank()) {
            runner.run(turn, tools, budget);
            System.out.println();
            System.out.print("reply, or blank to return to the menu: ");
            turn = context.console().hasNextLine() ? context.console().nextLine().strip() : "";
            System.out.println();
        }
    }
}
