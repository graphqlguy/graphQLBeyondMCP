package com.graphqlguy.moviedb.agents.safety;

/**
 * Hard ceilings for one agent run. A budget is the safety control that survives
 * every failure of judgment upstream: whatever the model believes, however the
 * prompt was manipulated, the loop stops when the numbers say stop.
 * <p>
 * Two ceilings cover the two ways runs go bad: too many model calls (the agent
 * loops on a problem it cannot solve) and too many tool calls (the agent thrashes
 * against the API). Spring AI's own ToolCallingManager enforces a related pair
 * (40 calls per tool, 150 in total per turn, added in 2.0.1); the run budget here is
 * deliberately far tighter, because a Movie Database concierge that needs ten
 * model calls has already gone wrong.
 */
public class RunBudget {

    private final int maxModelCalls;
    private final int maxToolCalls;
    private int modelCalls;
    private int toolCalls;

    public RunBudget(int maxModelCalls, int maxToolCalls) {
        this.maxModelCalls = maxModelCalls;
        this.maxToolCalls = maxToolCalls;
    }

    /** Returns false when the budget is spent; the loop must stop, whatever the model wants. */
    public boolean allowModelCall() {
        modelCalls++;
        return modelCalls <= maxModelCalls;
    }

    public void countToolCall() {
        toolCalls++;
        if (toolCalls > maxToolCalls) {
            throw new IllegalStateException(
                    "Run budget exceeded: " + toolCalls + " tool calls against a ceiling of "
                    + maxToolCalls + ". The loop stops here by design.");
        }
    }

    public String summary() {
        return modelCalls + " model calls (ceiling " + maxModelCalls + "), "
                + toolCalls + " tool calls (ceiling " + maxToolCalls + ")";
    }
}
