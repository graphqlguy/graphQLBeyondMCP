package com.graphqlguy.moviedb.agents;

import com.graphqlguy.moviedb.agents.agent.AgentRunner;
import com.graphqlguy.moviedb.agents.langchain.LangChainToolBridge;
import com.graphqlguy.moviedb.agents.langchain.MovieConcierge;
import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.catalog.GraphQlToolCallback;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import com.graphqlguy.moviedb.agents.toolgen.AllowList;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import com.graphqlguy.moviedb.agents.toolgen.ToolCatalogGenerator;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Commands, one per idea the course has introduced so far:
 * <pre>{@code
 *   catalog [role]         Class 2: generate and print the tool catalog
 *   call <tool> <json>     Class 2: execute one tool directly, no model involved
 *   agent <task>           Class 4: the full loop with login, approval gate, and budget
 *   lc4j-agent <task>      Class 5: the same catalog and safety layer, driven by LangChain4j
 *   graph-agent <task>     Class 6: the loop as an explicit LangGraph4j state machine,
 *                          with the approval gate as a checkpointed interrupt
 *   spring-agent <task>    Class 7: the ChatClient with the advisor-owned loop; the
 *                          callbacks plug in with no bridge at all
 *   spring-stream <ask>    Class 7: the same client, streaming the answer token by token
 *   recommend <ask>        Class 8: tools plus typed output; the answer arrives as
 *                          List<MovieRecommendation>, never as free text to parse
 *   defer-demo             Class 9: graphql-java's experimental @defer, engine-level,
 *                          with timestamps proving the initial payload arrives first
 *   probe-depth            Class 4: show the server's own depth cap refusing a
 *                          pathological query, the backstop below every agent control
 * }</pre>
 */
@SpringBootApplication
public class AgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentsApplication.class, args);
    }

    @Bean
    CommandLineRunner commands(Environment env, ObjectProvider<ChatModel> chatModels) {
        return args -> {
            if (args.length == 0) {
                System.out.println("""
                        Usage:
                          catalog [role]       generate and print the tool catalog
                          call <tool> <json>   execute one generated tool against the endpoint
                          agent <task>         run the agent loop (login, approval gate, budget)
                          lc4j-agent <task>    the same task through LangChain4j's AiServices
                          graph-agent <task>   the loop as a LangGraph4j state machine
                          spring-agent <task>  the ChatClient with the advisor-owned loop
                          spring-stream <ask>  the same client, streaming the answer
                          recommend <ask>      tools plus typed output (List<MovieRecommendation>)
                          defer-demo           graphql-java's experimental @defer, timestamped
                          probe-depth          demonstrate the server's depth cap""");
                return;
            }
            String schemaPath = env.getProperty("agents.schema", "src/main/resources/schema-snapshot.graphqls");
            String allowListPath = env.getProperty("agents.allow-list", "src/main/resources/tool-allowlist.txt");
            String endpoint = env.getProperty("agents.endpoint", "http://localhost:8081/graphql");

            GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
                    new SchemaParser().parse(Files.readString(Path.of(schemaPath))));
            AllowList allowList = AllowList.load(Path.of(allowListPath));
            ToolCatalogGenerator generator = new ToolCatalogGenerator();

            switch (args[0]) {
                case "catalog" -> {
                    String role = args.length > 1 ? args[1] : null;
                    List<OperationTool> tools = generator.generate(schema, allowList, role);
                    System.out.println("schema     : " + schemaPath);
                    System.out.println("role       : " + (role == null ? "(all)" : role));
                    System.out.println("tools      : " + tools.size());
                    System.out.println("total cost : " + tools.stream()
                            .mapToInt(OperationTool::tokenCount).sum()
                            + " tokens to advertise this catalog (O200K_BASE)");
                    System.out.println();
                    System.out.println(generator.toJson(tools));
                }
                case "call" -> {
                    if (args.length < 3) {
                        System.out.println("call needs a tool name and a JSON argument object");
                        return;
                    }
                    String toolName = args[1];
                    String jsonArgs = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    OperationTool tool = generator.generate(schema, allowList, null).stream()
                            .filter(t -> t.name().equals(toolName))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "No allow-listed tool named '" + toolName + "'"));
                    System.out.println("operation : " + tool.operationDocument());
                    System.out.println("arguments : " + jsonArgs);
                    System.out.println();
                    System.out.println(GraphQlToolCallback.plain(tool, endpoint).call(jsonArgs));
                }
                case "agent" -> {
                    String task = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    ChatModel chatModel = chatModels.getIfAvailable();
                    if (task.isBlank() || chatModel == null) {
                        System.out.println(task.isBlank()
                                ? "agent needs a task, e.g.: agent add movie 1 to my watchlist"
                                : "No chat model available; is Ollama running?");
                        return;
                    }
                    String role = env.getProperty("agents.role", "concierge");
                    String username = env.getProperty("agents.auth.username", "");
                    AuthSession auth = username.isBlank()
                            ? AuthSession.anonymous()
                            : AuthSession.login(endpoint, username,
                                    env.getProperty("agents.auth.password", ""));
                    RunBudget budget = new RunBudget(
                            env.getProperty("agents.budget.max-model-calls", Integer.class, 8),
                            env.getProperty("agents.budget.max-tool-calls", Integer.class, 6));
                    ApprovalGate gate = new ApprovalGate();
                    List<ToolCallback> tools = generator.generate(schema, allowList, role).stream()
                            .map(t -> (ToolCallback) new GraphQlToolCallback(t, endpoint, auth, gate, budget))
                            .toList();
                    System.out.println("role    : " + role + " (" + tools.size() + " tools)");
                    System.out.println("user    : " + (username.isBlank() ? "(anonymous)" : username));
                    System.out.println("task    : " + task);
                    System.out.println();
                    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
                    var costMeter = new com.graphqlguy.moviedb.agents.safety.CostMeter(
                            registry, username.isBlank() ? "anonymous" : username,
                            env.getProperty("agents.model", "qwen3:8b"),
                            env.getProperty("agents.pricing.input-per-mtok", Double.class, 1.00),
                            env.getProperty("agents.pricing.output-per-mtok", Double.class, 5.00));
                    new AgentRunner(chatModel, env.getProperty("agents.model", "qwen3:8b"))
                            .run(task, tools, budget, costMeter);
                    System.out.println("meters: " + registry.getMeters().stream()
                            .map(m -> m.getId().getName() + m.getId().getTags() + "="
                                    + ((io.micrometer.core.instrument.Counter) m).count())
                            .sorted().toList());
                }
                case "lc4j-agent" -> {
                    String task = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    if (task.isBlank()) {
                        System.out.println("lc4j-agent needs a task");
                        return;
                    }
                    String role = env.getProperty("agents.role", "concierge");
                    String username = env.getProperty("agents.auth.username", "");
                    AuthSession auth = username.isBlank()
                            ? AuthSession.anonymous()
                            : AuthSession.login(endpoint, username,
                                    env.getProperty("agents.auth.password", ""));
                    RunBudget budget = new RunBudget(
                            env.getProperty("agents.budget.max-model-calls", Integer.class, 8),
                            env.getProperty("agents.budget.max-tool-calls", Integer.class, 6));
                    ApprovalGate gate = new ApprovalGate();
                    var bridged = new LangChainToolBridge().bridge(
                            generator.generate(schema, allowList, role),
                            t2 -> new GraphQlToolCallback(t2, endpoint, auth, gate, budget));
                    var chatModel = dev.langchain4j.model.ollama.OllamaChatModel.builder()
                            .baseUrl(env.getProperty("spring.ai.ollama.base-url", "http://localhost:11434"))
                            .modelName(env.getProperty("agents.model", "qwen3:8b"))
                            .build();
                    MovieConcierge concierge = dev.langchain4j.service.AiServices
                            .builder(MovieConcierge.class)
                            .chatModel(chatModel)
                            .tools(bridged)
                            // The framework owns this loop, so the model-call ceiling
                            // must live in the framework; the tool-call ceiling stays
                            // in the callback and needs nothing from anyone.
                            .maxToolCallingRoundTrips(
                                    env.getProperty("agents.budget.max-model-calls", Integer.class, 8))
                            .chatMemory(dev.langchain4j.memory.chat.MessageWindowChatMemory
                                    .withMaxMessages(20))
                            .build();
                    System.out.println("framework : LangChain4j (AiServices)");
                    System.out.println("role      : " + role + " (" + bridged.size() + " tools)");
                    System.out.println("user      : " + (username.isBlank() ? "(anonymous)" : username));
                    System.out.println("task      : " + task);
                    System.out.println();
                    String answer = concierge.chat(task);
                    System.out.println();
                    System.out.println("answer: " + answer);
                    System.out.println("budget: " + budget.summary());
                }
                case "graph-agent" -> {
                    String task = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    if (task.isBlank()) {
                        System.out.println("graph-agent needs a task");
                        return;
                    }
                    String role = env.getProperty("agents.role", "concierge");
                    String username = env.getProperty("agents.auth.username", "");
                    AuthSession auth = username.isBlank()
                            ? AuthSession.anonymous()
                            : AuthSession.login(endpoint, username,
                                    env.getProperty("agents.auth.password", ""));
                    RunBudget budget = new RunBudget(
                            env.getProperty("agents.budget.max-model-calls", Integer.class, 8),
                            env.getProperty("agents.budget.max-tool-calls", Integer.class, 6));
                    List<OperationTool> toolList = generator.generate(schema, allowList, role);
                    // The gate is deliberately absent from the callbacks here: in this
                    // class the gate is the graph's interrupt, one level up.
                    Map<String, GraphQlToolCallback> callbacks = new java.util.LinkedHashMap<>();
                    Map<String, OperationTool> byName = new java.util.LinkedHashMap<>();
                    for (OperationTool t2 : toolList) {
                        callbacks.put(t2.name(), new GraphQlToolCallback(t2, endpoint, auth, null, budget));
                        byName.put(t2.name(), t2);
                    }
                    java.util.Set<String> mutations = toolList.stream()
                            .filter(OperationTool::mutation)
                            .map(OperationTool::name)
                            .collect(java.util.stream.Collectors.toSet());
                    var lcModel = dev.langchain4j.model.ollama.OllamaChatModel.builder()
                            .baseUrl(env.getProperty("spring.ai.ollama.base-url", "http://localhost:11434"))
                            .modelName(env.getProperty("agents.model", "qwen3:8b"))
                            .build();
                    var concierge = new com.graphqlguy.moviedb.agents.langgraph.LangGraphConcierge(
                            lcModel, toolList, callbacks, mutations,
                            env.getProperty("agents.budget.max-model-calls", Integer.class, 8));
                    var graph = concierge.compile();
                    var config = org.bsc.langgraph4j.RunnableConfig.builder()
                            .threadId("class-6").build();
                    List<dev.langchain4j.data.message.ChatMessage> initial = List.of(
                            dev.langchain4j.data.message.SystemMessage.from(
                                    """
                                    You are a concierge for the Movie Database. Use the tools to answer
                                    and to act; never invent ids, and never claim an action succeeded
                                    without a tool result proving it. Ids are numeric strings like "2";
                                    a name is never an id. Before any write, you MUST first call the
                                    read tools to obtain every id the write needs."""),
                            dev.langchain4j.data.message.UserMessage.from(task));
                    System.out.println("framework : LangGraph4j (StateGraph, interruptBefore tools)");
                    System.out.println("role      : " + role + " (" + toolList.size() + " tools)");
                    System.out.println("task      : " + task);
                    System.out.println();
                    ApprovalGate gate = new ApprovalGate();
                    var stream = graph.stream(
                            Map.<String, Object>of("messages", initial), config);
                    answerLoop:
                    while (true) {
                        String lastNode = null;
                        com.graphqlguy.moviedb.agents.langgraph.ConciergeState lastState = null;
                        for (var output : stream) {
                            lastNode = output.node();
                            lastState = output.state();
                            System.out.println("node: " + output.node()
                                    + " (round " + output.state().rounds() + ")");
                        }
                        if (org.bsc.langgraph4j.StateGraph.END.equals(lastNode) || lastState == null) {
                            System.out.println();
                            System.out.println("answer: " + (lastState == null ? "(none)"
                                    : lastState.lastAiMessage()
                                            .map(dev.langchain4j.data.message.AiMessage::text)
                                            .orElse("(no final message)")));
                            System.out.println("budget: " + budget.summary());
                            break;
                        }
                        // The graph checkpointed and paused before the tools node.
                        for (var request : concierge.pendingMutations(lastState)) {
                            if (!gate.approve(request.name(),
                                    byName.get(request.name()).operationDocument(),
                                    request.arguments())) {
                                System.out.println("Run abandoned at the interrupt; the"
                                        + " checkpoint keeps the paused state for inspection.");
                                break answerLoop;
                            }
                        }
                        stream = graph.stream(org.bsc.langgraph4j.GraphInput.resume(), config);
                    }
                }
                case "spring-agent", "spring-stream" -> {
                    String task = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    ChatModel springModel = chatModels.getIfAvailable();
                    if (task.isBlank() || springModel == null) {
                        System.out.println(task.isBlank() ? "a task is needed"
                                : "No chat model available; is Ollama running?");
                        return;
                    }
                    String role = env.getProperty("agents.role", "concierge");
                    String username = env.getProperty("agents.auth.username", "");
                    AuthSession auth = username.isBlank()
                            ? AuthSession.anonymous()
                            : AuthSession.login(endpoint, username,
                                    env.getProperty("agents.auth.password", ""));
                    RunBudget budget = new RunBudget(
                            env.getProperty("agents.budget.max-model-calls", Integer.class, 8),
                            env.getProperty("agents.budget.max-tool-calls", Integer.class, 6));
                    ApprovalGate gate = new ApprovalGate();
                    List<org.springframework.ai.tool.ToolCallback> callbacks =
                            generator.generate(schema, allowList, role).stream()
                                    .map(t2 -> (org.springframework.ai.tool.ToolCallback)
                                            new GraphQlToolCallback(t2, endpoint, auth, gate, budget))
                                    .toList();
                    var client = org.springframework.ai.chat.client.ChatClient.create(springModel);
                    var spec = client.prompt()
                            .system("""
                                    You are a concierge for the Movie Database. Use the tools to answer
                                    and to act; never invent ids, and never claim an action succeeded
                                    without a tool result proving it. Ids are numeric strings like "2";
                                    a name is never an id. Before any write, first call the read tools
                                    to obtain every id the write needs.""")
                            .user(task)
                            .toolCallbacks(callbacks)
                            .options(org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                                    .model(env.getProperty("agents.model", "qwen3:8b")));
                    System.out.println("framework : Spring AI (ChatClient, advisor-owned loop)");
                    System.out.println("role      : " + role + " (" + callbacks.size() + " tools)");
                    System.out.println("task      : " + task);
                    System.out.println();
                    if (args[0].equals("spring-stream")) {
                        spec.stream().content()
                                .doOnNext(System.out::print)
                                .blockLast();
                        System.out.println();
                    } else {
                        var response = spec.call().chatResponse();
                        System.out.println("answer: " + response.getResult().getOutput().getText());
                        var usage = response.getMetadata().getUsage();
                        System.out.println("usage : " + usage.getPromptTokens() + " prompt + "
                                + usage.getCompletionTokens() + " completion tokens"
                                + " (provider-reported, final call)");
                    }
                    System.out.println("budget: " + budget.summary());
                }
                case "recommend" -> {
                    String ask = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    ChatModel model8 = chatModels.getIfAvailable();
                    if (ask.isBlank() || model8 == null) {
                        System.out.println(ask.isBlank() ? "recommend needs a request"
                                : "No chat model available; is Ollama running?");
                        return;
                    }
                    RunBudget budget = new RunBudget(8, 6);
                    List<org.springframework.ai.tool.ToolCallback> callbacks =
                            generator.generate(schema, allowList, null).stream()
                                    .filter(t2 -> !t2.mutation())
                                    .map(t2 -> (org.springframework.ai.tool.ToolCallback)
                                            new GraphQlToolCallback(t2, endpoint,
                                                    AuthSession.anonymous(), null, budget))
                                    .toList();
                    var client8 = org.springframework.ai.chat.client.ChatClient.create(model8);
                    System.out.println("task    : " + ask);
                    System.out.println();
                    // Step one: ACT. Tools only, no output format in sight, so nothing
                    // competes with grounding. The findings come back as plain text.
                    String findings = client8.prompt()
                            .system("You research movies in the Movie Database. Use the tools"
                                    + " to find real catalog data answering the request; never"
                                    + " invent movies. Report the matching movies with their"
                                    + " exact ids, titles, years, and ratings from tool results.")
                            .user(ask)
                            .toolCallbacks(callbacks)
                            .options(org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                                    .model(env.getProperty("agents.model", "qwen3:8b")))
                            .call()
                            .content();
                    System.out.println("findings (grounded, text): "
                            + findings.replace("\n", " ").strip());
                    System.out.println();
                    // Step two: FORMAT. Entity only, no tools, so nothing competes with
                    // the shape. The model restructures known-good facts.
                    List<com.graphqlguy.moviedb.agents.structured.MovieRecommendation> answer =
                            client8.prompt()
                                    .system("Format the given findings as recommendations."
                                            + " Use only movies present in the findings, with"
                                            + " their exact data; add a one-sentence reason each."
                                            + " If no usable movies appear in the findings, return"
                                            + " an empty list; an empty list is a correct answer.")
                                    .user("Request: " + ask + "\nFindings: " + findings)
                                    .options(org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                                            .model(env.getProperty("agents.model", "qwen3:8b")))
                                    .call()
                                    .entity(new org.springframework.core.ParameterizedTypeReference<
                                            List<com.graphqlguy.moviedb.agents.structured.MovieRecommendation>>() {});
                    for (var rec : answer) {
                        System.out.printf("- %s (%d, rated %.1f): %s%n",
                                rec.title(), rec.releaseYear(), rec.rating(), rec.reason());
                    }
                    System.out.println();
                    System.out.println("type   : List<MovieRecommendation>, " + answer.size()
                            + " typed entries; every field is a Java value, never text to parse");
                    System.out.println("budget : " + budget.summary());
                }
                case "defer-demo" ->
                        new com.graphqlguy.moviedb.agents.incremental.DeferDemo().run();
                case "probe-depth" -> {
                    // Movie -> cast -> movie -> cast ... : the schema's real cycle,
                    // nested far beyond any sane query's needs.
                    StringBuilder q = new StringBuilder("query { movie(id: \"1\")");
                    int pairs = 9;
                    for (int i = 0; i < pairs; i++) {
                        q.append(" { cast { movie");
                    }
                    q.append(" { title ").append("} ".repeat(pairs * 2 + 2));
                    String pathological = q.toString();
                    System.out.println("query  : " + pathological);
                    System.out.println();
                    System.out.println(RestClient.create().post().uri(endpoint)
                            .header("Content-Type", "application/json")
                            .body(Map.of("query", pathological))
                            .retrieve().body(String.class));
                }
                default -> System.out.println("Unknown command: " + args[0]);
            }
        };
    }
}
