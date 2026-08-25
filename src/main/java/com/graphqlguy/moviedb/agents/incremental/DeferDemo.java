package com.graphqlguy.moviedb.agents.incremental;

import graphql.ExecutionInput;
import graphql.ExperimentalApi;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.incremental.DelayedIncrementalPartialResult;
import graphql.incremental.IncrementalExecutionResult;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.CountDownLatch;

/**
 * A minimal, self-contained demonstration that graphql-java's engine can defer,
 * independent of any web framework. One fast field, one deliberately slow field,
 * a query deferring the slow one, and timestamps proving the initial result
 * arrives immediately while the deferred part follows seconds later.
 *
 * Everything here is explicitly experimental: the capability is switched on per
 * execution through a context flag, the deferred work only starts when a
 * subscriber attaches to the publisher, and the wire format the payloads mirror
 * is an older draft of the incremental-delivery proposal. That is the honest
 * state of @defer on the JVM as of August 2026, and seeing it run beats reading
 * about it.
 */
public class DeferDemo {

    private static final String SDL = """
            type Query {
              "Available immediately."
              headline: String
              "Takes two seconds; the part worth deferring."
              analysis: String
            }
            """;

    public void run() throws Exception {
        long start = System.currentTimeMillis();
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
                .type("Query", type -> type
                        .dataFetcher("headline", env -> "The catalog holds 12 movies.")
                        .dataFetcher("analysis", env -> {
                            Thread.sleep(2000); // the resolver a user should never wait behind
                            return "Dramas dominate; ratings cluster between 8.5 and 9.3.";
                        }))
                .build();
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(
                new SchemaParser().parse(SDL), wiring);
        GraphQL graphql = GraphQL.newGraphQL(schema).build();

        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query("query { headline ... @defer { analysis } }")
                .graphQLContext(context -> context.put(
                        ExperimentalApi.ENABLE_INCREMENTAL_SUPPORT, true))
                .build();

        ExecutionResult result = graphql.execute(input);
        System.out.printf("[t=%4dms] initial payload : %s%n",
                System.currentTimeMillis() - start, result.toSpecification());

        if (result instanceof IncrementalExecutionResult incremental) {
            CountDownLatch done = new CountDownLatch(1);
            incremental.getIncrementalItemPublisher().subscribe(
                    new Subscriber<DelayedIncrementalPartialResult>() {
                        public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
                        public void onNext(DelayedIncrementalPartialResult payload) {
                            System.out.printf("[t=%4dms] deferred payload: %s%n",
                                    System.currentTimeMillis() - start, payload.toSpecification());
                        }
                        public void onError(Throwable t) { done.countDown(); }
                        public void onComplete() { done.countDown(); }
                    });
            done.await();
        }
    }
}
