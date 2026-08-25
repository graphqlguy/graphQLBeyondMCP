package com.graphqlguy.moviedb.agents.langgraph;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The agent's whole condition, as data. LangGraph4j models a run as a typed state
 * flowing through nodes, and the schema declares how each key merges: "messages"
 * is an appender channel (every node's contribution is appended, which is exactly
 * how a conversation grows), while "rounds" is a plain value the agent node
 * overwrites as its loop counter.
 *
 * Making state explicit is the framework's entire proposition. The hand loop of
 * Class 4 and the AiServices proxy of Class 5 both HAVE state; here it is a value
 * you can inspect, checkpoint, and edit between steps, which is what makes
 * interrupts and time-travel possible instead of clever.
 */
public class ConciergeState extends AgentState {

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            "messages", Channels.appender(ArrayList::new),
            "rounds", Channels.base(() -> 0));

    public ConciergeState(Map<String, Object> initData) {
        super(initData);
    }

    public List<ChatMessage> messages() {
        return this.<List<ChatMessage>>value("messages").orElse(List.of());
    }

    public int rounds() {
        return this.<Integer>value("rounds").orElse(0);
    }

    /** The most recent model message, when the last message is one. */
    public Optional<AiMessage> lastAiMessage() {
        List<ChatMessage> messages = messages();
        if (!messages.isEmpty() && messages.get(messages.size() - 1) instanceof AiMessage ai) {
            return Optional.of(ai);
        }
        return Optional.empty();
    }
}
