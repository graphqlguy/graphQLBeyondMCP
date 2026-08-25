package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.catalog.GraphQlToolCallback;
import com.graphqlguy.moviedb.agents.langgraph.ConciergeState;
import com.graphqlguy.moviedb.agents.langgraph.LangGraphConcierge;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Class 6: the loop as an explicit state machine. The gate moves up into the
 * graph, so the callbacks are built without one: the interrupt does the asking.
 */
@Component
public class GraphAgentCommand implements AgentCommand {

    private static final String SYSTEM = """
            You are a concierge for the Movie Database. Use the tools to answer
            and to act; never invent ids, and never claim an action succeeded
            without a tool result proving it. Ids are numeric strings like "2";
            a name is never an id. Before any write, you MUST first call the
            read tools to obtain every id the write needs.""";

    @Override
    public String name() {
        return "graph-agent";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public String summary() {
        return "the loop as a LangGraph4j state machine, gated by an interrupt";
    }

    @Override
    public String prompt() {
        return "task";
    }

    @Override
    public void run(List<String> args, CommandContext context) throws Exception {
        String task = String.join(" ", args);
        if (task.isBlank()) {
            System.out.println("graph-agent needs a task");
            return;
        }
        String role = context.role();
        AuthSession auth = context.login();
        RunBudget budget = context.budget();
        List<OperationTool> toolList = context.tools(role);

        // The gate is deliberately absent from the callbacks here: in this class the
        // gate is the graph's interrupt, one level up.
        Map<String, GraphQlToolCallback> callbacks = new LinkedHashMap<>();
        Map<String, OperationTool> byName = new LinkedHashMap<>();
        for (OperationTool tool : toolList) {
            callbacks.put(tool.name(),
                    new GraphQlToolCallback(tool, context.endpoint(), auth, null, budget));
            byName.put(tool.name(), tool);
        }
        Set<String> mutations = toolList.stream()
                .filter(OperationTool::mutation)
                .map(OperationTool::name)
                .collect(Collectors.toSet());

        var model = OllamaChatModel.builder()
                .baseUrl(context.env().getProperty("spring.ai.ollama.base-url", "http://localhost:11434"))
                .modelName(context.modelName())
                .build();
        var concierge = new LangGraphConcierge(model, toolList, callbacks, mutations,
                context.env().getProperty("agents.budget.max-model-calls", Integer.class, 8));
        var graph = concierge.compile();
        var config = RunnableConfig.builder().threadId("class-6").build();
        List<ChatMessage> initial = List.of(SystemMessage.from(SYSTEM), UserMessage.from(task));

        System.out.println("framework : LangGraph4j (StateGraph, interruptBefore tools)");
        System.out.println("role      : " + role + " (" + toolList.size() + " tools)");
        System.out.println("task      : " + task);
        System.out.println();

        ApprovalGate gate = new ApprovalGate(context.console());
        List<ChatMessage> submission = initial;
        String turn = task;
        while (!turn.isBlank()) {
            ConciergeState finalState = driveToEnd(graph, config, submission, concierge, byName, gate);
            if (finalState == null) {
                System.out.println("Run abandoned at the interrupt; the checkpoint"
                        + " keeps the paused state for inspection.");
                return;
            }
            System.out.println();
            System.out.println("answer: " + finalState.lastAiMessage()
                    .map(AiMessage::text).orElse("(no final message)"));
            System.out.println("budget: " + budget.summary());
            System.out.println();
            System.out.print("reply, or blank to return to the menu: ");
            turn = context.console().hasNextLine() ? context.console().nextLine().strip() : "";
            System.out.println();
            // The thread id stays the same, so the checkpointer restores everything
            // said so far and the appender channel adds this message to it.
            submission = List.of(UserMessage.from(turn));
        }
    }

    /**
     * Runs the graph until it reaches the end, pausing at each interrupt to ask about
     * the writes waiting there. Returns the final state, or null when a human declined.
     */
    private ConciergeState driveToEnd(CompiledGraph<ConciergeState> graph,
                                      RunnableConfig config,
                                      List<ChatMessage> submission,
                                      LangGraphConcierge concierge,
                                      Map<String, OperationTool> byName,
                                      ApprovalGate gate) {
        var stream = graph.stream(Map.<String, Object>of("messages", submission), config);
        while (true) {
            String lastNode = null;
            ConciergeState lastState = null;
            for (var output : stream) {
                lastNode = output.node();
                lastState = output.state();
                System.out.println("node: " + output.node() + " (round " + output.state().rounds() + ")");
            }
            if (StateGraph.END.equals(lastNode) || lastState == null) {
                return lastState;
            }
            // The graph checkpointed and paused before the tools node.
            for (var request : concierge.pendingMutations(lastState)) {
                if (!gate.approve(request.name(),
                        byName.get(request.name()).operationDocument(),
                        request.arguments())) {
                    return null;
                }
            }
            stream = graph.stream(GraphInput.resume(), config);
        }
    }
}
