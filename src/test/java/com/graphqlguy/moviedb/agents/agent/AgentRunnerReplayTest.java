package com.graphqlguy.moviedb.agents.agent;

import com.graphqlguy.moviedb.agents.safety.RunBudget;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The loop itself, driven by a scripted model. The two replies below are the shape
 * a real run produces: one asking for a tool, then one answering in text. Because
 * the replies are fixed, this test says whether our loop does the right thing with
 * them, and it says nothing about what any model would reply today.
 */
class AgentRunnerReplayTest {

    private final List<String> toolCallsMade = new ArrayList<>();

    @Test
    void loop_shouldExecuteTheToolAndThenReturnTheModelsAnswer() {
        ChatModel scriptedModel = replying(
                asksFor("myWatchLists"),
                answers("You have one watch list, To watch this weekend."));
        RunBudget budget = new RunBudget(8, 6);

        new AgentRunner(scriptedModel, "scripted").run(
                "what is on my weekend list", List.of(recordingTool()), budget);

        assertThat(toolCallsMade).containsExactly("myWatchLists");
        // Two model calls: the one that asked for the tool, and the one that answered.
        // The budget's tool counter stays at zero because counting lives in
        // GraphQlToolCallback, and this test's tool is a plain recording double.
        assertThat(budget.summary()).contains("2 model calls");
    }

    @Test
    void budget_shouldStopALoopThatKeepsAskingForTools() {
        ChatModel neverFinishes = replying(
                asksFor("myWatchLists"), asksFor("myWatchLists"), asksFor("myWatchLists"),
                asksFor("myWatchLists"), asksFor("myWatchLists"), asksFor("myWatchLists"));
        RunBudget budget = new RunBudget(3, 6);

        new AgentRunner(neverFinishes, "scripted").run(
                "loop forever", List.of(recordingTool()), budget);

        // The ceiling is three model calls, so the fourth never happens.
        assertThat(toolCallsMade).hasSize(3);
    }

    /** A model that replies with the given responses in order, one per call. */
    private static ChatModel replying(ChatResponse... scripted) {
        Deque<ChatResponse> remaining = new ArrayDeque<>(List.of(scripted));
        return (Prompt prompt) -> remaining.isEmpty()
                ? answers("(the script ran out)")
                : remaining.removeFirst();
    }

    private static ChatResponse asksFor(String toolName) {
        AssistantMessage.ToolCall call =
                new AssistantMessage.ToolCall("call-1", "function", toolName, "{}");
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
    }

    private static ChatResponse answers(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** A tool that records that it ran and returns a fixed result. */
    private ToolCallback recordingTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("myWatchLists")
                        .description("lists the caller's watch lists")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                toolCallsMade.add("myWatchLists");
                return "{\"data\":{\"myWatchLists\":[{\"id\":\"1\"}]}}";
            }
        };
    }
}
