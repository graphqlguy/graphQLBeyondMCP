package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchTool;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Class 7: the retrieval layer behind Spring AI's tool search, with the model taken
 * out of the picture. It indexes the catalog exactly as the advisor does, then shows
 * what each backend ranks for one query, and what tool search costs before it saves
 * anything.
 */
@Component
public class ToolSearchCommand implements AgentCommand {

    @Override
    public String name() {
        return "tool-search";
    }

    @Override
    public int order() {
        return 72;
    }

    @Override
    public String summary() {
        return "rank the catalog with both tool indexes, and price what tool search costs";
    }

    @Override
    public String prompt() {
        return "natural-language query, the way a model would phrase a need, e.g. add a movie to my list";
    }

    @Override
    public void run(List<String> args, CommandContext context) throws Exception {
        String query = String.join(" ", args);
        if (query.isBlank()) {
            System.out.println("tool-search needs a query, e.g. add a movie to my list");
            return;
        }
        String role = context.role();
        List<OperationTool> tools = context.tools(role);
        // What the advisor indexes is exactly this: a tool's name and its description.
        // The input schema, which is most of a definition's cost, is never indexed.
        List<ToolReference> references = tools.stream()
                .map(tool -> ToolReference.builder()
                        .toolName(tool.name()).summary(tool.description()).build())
                .toList();
        Encoding encoding = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);
        int catalogTokens = tools.stream().mapToInt(OperationTool::tokenCount).sum();

        // What tool search costs before it saves anything: the advisor appends a suffix
        // to the system message and advertises one tool of its own.
        String suffix = new DefaultResourceLoader()
                .getResource("classpath:/DEFAULT_SYSTEM_PROMPT_SUFFIX.md")
                .getContentAsString(StandardCharsets.UTF_8);
        ToolCallback searchTool = MethodToolCallbackProvider.builder()
                .toolObjects(new ToolSearchTool(new RegexToolIndex(), 5))
                .build().getToolCallbacks()[0];
        ToolDefinition searchDef = searchTool.getToolDefinition();
        int overhead = encoding.countTokens(suffix)
                + encoding.countTokens(searchDef.name() + " " + searchDef.description()
                        + " " + searchDef.inputSchema());

        System.out.println("role     : " + role + " (" + tools.size() + " tools)");
        System.out.println("query    : " + query);
        System.out.println("note     : this command indexes and ranks on its own,"
                + " whatever agents.tool-search is set to");
        System.out.println("catalog  : " + catalogTokens
                + " tokens if every definition is advertised up front");
        System.out.println("overhead : " + overhead
                + " tokens tool search would add to a run before it saves anything"
                + " (system-message suffix + the toolSearchTool definition)");
        System.out.println();

        Map<String, ToolIndex> indexes = new LinkedHashMap<>();
        indexes.put("regex (the auto-configured default)", new RegexToolIndex());
        indexes.put("lucene (BM25, StandardAnalyzer)", new LuceneToolIndex());
        for (Map.Entry<String, ToolIndex> entry : indexes.entrySet()) {
            ToolIndex index = entry.getValue();
            index.indexTools("probe", references);
            List<ToolReference> hits = index
                    .search(new ToolSearchRequest("probe", query, 5, null))
                    .toolReferences();
            System.out.println(entry.getKey() + " -> "
                    + (hits.isEmpty() ? "no tool matched" : hits.size() + " hits"));
            for (ToolReference hit : hits) {
                System.out.printf("    %-24s score %.3f%n", hit.toolName(), hit.relevanceScore());
            }
            System.out.println();
        }
    }
}
