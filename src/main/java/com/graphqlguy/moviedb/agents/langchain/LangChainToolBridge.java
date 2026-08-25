package com.graphqlguy.moviedb.agents.langchain;

import com.graphqlguy.moviedb.agents.catalog.GraphQlToolCallback;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The whole LangChain4j integration, and its size is the lesson. Each generated
 * tool becomes a ToolSpecification (what the model sees) paired with a
 * ToolExecutor (what runs), and the executor simply delegates to the SAME
 * GraphQlToolCallback that served Spring AI in Classes 2 and 4, so the login,
 * the approval gate, the run budget, and the persisted operations all carry
 * over without a line changing. The framework changed; the safety did not.
 */
public class LangChainToolBridge {

    private final LangChainSchemaMapper schemaMapper = new LangChainSchemaMapper();

    public Map<ToolSpecification, ToolExecutor> bridge(
            List<OperationTool> tools,
            Function<OperationTool, GraphQlToolCallback> callbackFactory) {
        Map<ToolSpecification, ToolExecutor> bridged = new LinkedHashMap<>();
        for (OperationTool tool : tools) {
            ToolSpecification specification = ToolSpecification.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(schemaMapper.toObjectSchema(tool.inputSchema()))
                    .build();
            GraphQlToolCallback callback = callbackFactory.apply(tool);
            ToolExecutor executor = (request, memoryId) -> {
                System.out.println("tool call: " + request.name() + " " + request.arguments());
                return callback.call(request.arguments());
            };
            bridged.put(specification, executor);
        }
        return bridged;
    }
}
