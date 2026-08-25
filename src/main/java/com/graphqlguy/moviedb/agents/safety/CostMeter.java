package com.graphqlguy.moviedb.agents.safety;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Where token counts become money, and where money becomes a metric. Every model
 * call reports its provider-side usage; this meter prices it against the
 * configured per-million-token rates and feeds the same Micrometer registry the
 * rest of a Spring service ships operational metrics through, tagged by tenant
 * and model, because an agent bill you can only see after the invoice is an
 * agent bill you cannot stop.
 *
 * The prices are configuration, deliberately: local models cost zero at the
 * provider and something at the electricity meter, hosted models publish list
 * prices, and the pattern is identical either way. The demo runs price a local
 * model AS IF at hosted list rates, labeled as such, so the arithmetic is
 * visible without an API key.
 */
public class CostMeter {

    private final Counter promptTokens;
    private final Counter completionTokens;
    private final Counter cost;
    private final double inputPerMillion;
    private final double outputPerMillion;
    private long promptTotal;
    private long completionTotal;

    public CostMeter(MeterRegistry registry, String tenant, String model,
                     double inputPerMillion, double outputPerMillion) {
        this.inputPerMillion = inputPerMillion;
        this.outputPerMillion = outputPerMillion;
        this.promptTokens = Counter.builder("agent.tokens")
                .tag("kind", "prompt").tag("tenant", tenant).tag("model", model)
                .register(registry);
        this.completionTokens = Counter.builder("agent.tokens")
                .tag("kind", "completion").tag("tenant", tenant).tag("model", model)
                .register(registry);
        this.cost = Counter.builder("agent.cost.dollars")
                .tag("tenant", tenant).tag("model", model)
                .register(registry);
    }

    public void record(long prompt, long completion) {
        promptTotal += prompt;
        completionTotal += completion;
        promptTokens.increment(prompt);
        completionTokens.increment(completion);
        cost.increment(dollars(prompt, completion));
    }

    private double dollars(long prompt, long completion) {
        return prompt / 1_000_000.0 * inputPerMillion
                + completion / 1_000_000.0 * outputPerMillion;
    }

    public String receipt() {
        double total = dollars(promptTotal, completionTotal);
        return String.format(
                "%,d prompt + %,d completion tokens = $%.6f at $%.2f/$%.2f per million (illustrative list rates)",
                promptTotal, completionTotal, total, inputPerMillion, outputPerMillion);
    }
}
