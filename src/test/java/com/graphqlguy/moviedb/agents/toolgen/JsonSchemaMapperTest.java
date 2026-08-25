package com.graphqlguy.moviedb.agents.toolgen;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The translation rules of Class 2, pinned as executable facts. Each test names
 * one rule; if a refactor bends a rule, the failure message says which promise
 * to the model broke.
 */
@SuppressWarnings("unchecked")
class JsonSchemaMapperTest {

    private final JsonSchemaMapper mapper = new JsonSchemaMapper();

    private GraphQLSchema schema(String sdl) {
        return UnExecutableSchemaGenerator.makeUnExecutableSchema(
                new SchemaParser().parse(sdl));
    }

    @Test
    void enumsBecomeClosedValueLists() {
        var schema = schema("""
                type Query { q(g: Genre!): String }
                enum Genre { DRAMA COMEDY HORROR }
                """);
        var argType = (graphql.schema.GraphQLInputType)
                schema.getQueryType().getFieldDefinition("q").getArgument("g").getType();
        Map<String, Object> json = mapper.toJsonSchema(argType);
        assertThat(json.get("type")).isEqualTo("string");
        assertThat((List<Object>) json.get("enum"))
                .containsExactly("DRAMA", "COMEDY", "HORROR");
    }

    @Test
    void nonNullMovesToTheParentsRequiredList() {
        var schema = schema("""
                type Query { q(in: In!): String }
                input In { must: String! may: String }
                """);
        var argType = (graphql.schema.GraphQLInputType)
                schema.getQueryType().getFieldDefinition("q").getArgument("in").getType();
        Map<String, Object> json = mapper.toJsonSchema(argType);
        assertThat((List<Object>) json.get("required")).containsExactly("must");
        assertThat(json.get("additionalProperties")).isEqualTo(false);
    }

    @Test
    void idsTravelAsStringsAndListsAsArrays() {
        var schema = schema("type Query { q(ids: [ID!]!): String }");
        var argType = (graphql.schema.GraphQLInputType)
                schema.getQueryType().getFieldDefinition("q").getArgument("ids").getType();
        Map<String, Object> json = mapper.toJsonSchema(argType);
        assertThat(json.get("type")).isEqualTo("array");
        assertThat(((Map<?, ?>) json.get("items")).get("type")).isEqualTo("string");
    }
}
