package com.graphqlguy.moviedb.agents.langgraph;

import com.graphqlguy.moviedb.agents.catalog.GraphQlToolCallback;
import com.graphqlguy.moviedb.agents.langchain.LangChainSchemaMapper;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.langchain4j.serializer.jackson.LC4jJacksonStateSerializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * The canonical agent loop, drawn instead of hidden: an agent node that calls the
 * model, a tools node that executes what the model asked for, a conditional edge
 * that decides between another round and the end, and a budget expressed as data
 * the edge can read. Compare Class 4, where this exact control flow lived in a
 * while-loop; here it is a value you can print, checkpoint, and interrupt.
 *
 * The approval gate moves with the loop: compiling with interruptBefore("tools")
 * makes the graph pause and checkpoint BEFORE any tool executes, which is where
 * a human (or a policy service) inspects the proposed calls and resumes, edits,
 * or abandons the run. The callbacks underneath are the plain Class 2 ones, with
 * the gate deliberately left out: this class's gate is the interrupt.
 */
public class LangGraphConcierge {

    private final ChatModel chatModel;
    private final List<ToolSpecification> specifications = new ArrayList<>();
    private final Map<String, GraphQlToolCallback> executors = new LinkedHashMap<>();
    private final Set<String> mutationTools;
    private final int maxRounds;

    public LangGraphConcierge(ChatModel chatModel, List<OperationTool> tools,
                              Map<String, GraphQlToolCallback> callbacksByName,
                              Set<String> mutationTools, int maxRounds) {
        this.chatModel = chatModel;
        this.mutationTools = mutationTools;
        this.maxRounds = maxRounds;
        LangChainSchemaMapper schemaMapper = new LangChainSchemaMapper();
        for (OperationTool tool : tools) {
            specifications.add(ToolSpecification.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(schemaMapper.toObjectSchema(tool.inputSchema()))
                    .build());
            executors.put(tool.name(), callbacksByName.get(tool.name()));
        }
    }

    public CompiledGraph<ConciergeState> compile() throws Exception {
        // Checkpointing serializes the state, and LangChain4j's message classes are
        // not java.io.Serializable, so the integration module's Jackson serializer
        // does the job; the default ObjectStream serializer fails at the first
        // checkpoint. A worthwhile fact discovered the honest way: by failing.
        StateGraph<ConciergeState> graph = new StateGraph<>(ConciergeState.SCHEMA,
                new LC4jJacksonStateSerializer<>(ConciergeState::new));

        graph.addNode("agent", node_async(state -> {
            ChatRequest request = ChatRequest.builder()
                    .messages(state.messages())
                    .toolSpecifications(specifications)
                    .build();
            AiMessage answer = chatModel.chat(request).aiMessage();
            return Map.of("messages", List.of(answer), "rounds", state.rounds() + 1);
        }));

        graph.addNode("tools", node_async(state -> {
            AiMessage last = state.lastAiMessage().orElseThrow();
            List<ChatMessage> results = new ArrayList<>();
            for (ToolExecutionRequest request : last.toolExecutionRequests()) {
                System.out.println("tool call: " + request.name() + " " + request.arguments());
                String result = executors.get(request.name()).call(request.arguments());
                results.add(ToolExecutionResultMessage.from(request, result));
            }
            return Map.of("messages", results);
        }));

        graph.addEdge(START, "agent");
        graph.addConditionalEdges("agent", edge_async(state -> {
            boolean wantsTools = state.lastAiMessage()
                    .map(AiMessage::hasToolExecutionRequests).orElse(false);
            if (state.rounds() > maxRounds) {
                return "end"; // the budget, readable right here in the routing decision
            }
            return wantsTools ? "tools" : "end";
        }), Map.of("tools", "tools", "end", END));
        graph.addEdge("tools", "agent");

        return graph.compile(CompileConfig.builder()
                .checkpointSaver(new MemorySaver())
                .interruptBefore("tools")
                .build());
    }

    /** The proposed calls waiting at an interrupt that would write, if any. */
    public List<ToolExecutionRequest> pendingMutations(ConciergeState state) {
        return state.lastAiMessage()
                .map(AiMessage::toolExecutionRequests).orElse(List.of()).stream()
                .filter(request -> mutationTools.contains(request.name()))
                .toList();
    }
}
