package com.graphqlguy.moviedb.agents.agent;

import com.graphqlguy.moviedb.agents.safety.CostMeter;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * The agent loop, driven by hand as in the schema-navigation class, with one
 * addition: the run budget has the final say. The model proposes, the tools
 * dispose, and the budget adjourns. When the ceiling is reached mid-task, the
 * loop stops with a plain statement instead of one more hopeful model call,
 * because a stuck agent's most expensive behavior is optimism.
 */
public class AgentRunner {

    private static final String SYSTEM = """
            You are a concierge for the Movie Database. Use the tools to answer and to act;
            never invent ids, and never claim an action succeeded without a tool result
            proving it. Writes may require the human's approval; if a write is denied,
            accept the decision, explain it, and ask what they would like instead.
            When you need an id (a watchlist, a movie), find it with a read tool first.""";

    private final ChatModel chatModel;
    private final String modelName;
    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

    public AgentRunner(ChatModel chatModel, String modelName) {
        this.chatModel = chatModel;
        this.modelName = modelName;
    }

    public void run(String task, List<ToolCallback> tools, RunBudget budget) {
        run(task, tools, budget, null);
    }

    public void run(String task, List<ToolCallback> tools, RunBudget budget, CostMeter costMeter) {
        ToolCallingChatOptions options = OllamaChatOptions.builder()
                .model(modelName)
                .toolCallbacks(tools)
                .build();
        List<Message> conversation = new ArrayList<>(List.of(
                new SystemMessage(SYSTEM), new UserMessage(task)));

        String finalAnswer = null;
        while (budget.allowModelCall()) {
            Prompt prompt = new Prompt(conversation, options);
            ChatResponse response = chatModel.call(prompt);
            if (costMeter != null) {
                var usage = response.getMetadata().getUsage();
                costMeter.record(
                        usage.getPromptTokens() == null ? 0 : usage.getPromptTokens().longValue(),
                        usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens().longValue());
            }
            if (response.hasToolCalls()) {
                response.getResult().getOutput().getToolCalls().forEach(call ->
                        System.out.println("model asks for: " + call.name()));
                ToolExecutionResult executed =
                        toolCallingManager.executeToolCalls(prompt, response);
                conversation = new ArrayList<>(executed.conversationHistory());
                if (!conversation.isEmpty()
                        && conversation.get(conversation.size() - 1)
                                instanceof ToolResponseMessage toolResponses) {
                    toolResponses.getResponses().forEach(tr -> {
                        String data = tr.responseData();
                        System.out.println("  result: " + (data.length() > 220
                                ? data.substring(0, 220) + "..." : data));
                    });
                }
            } else {
                finalAnswer = response.getResult().getOutput().getText();
                break;
            }
        }

        System.out.println();
        if (finalAnswer == null) {
            System.out.println("The run budget stopped the loop before a final answer: "
                    + budget.summary());
        } else {
            System.out.println("answer: " + finalAnswer);
            System.out.println("budget: " + budget.summary());
            if (costMeter != null) {
                System.out.println("cost  : " + costMeter.receipt());
            }
        }
    }
}
