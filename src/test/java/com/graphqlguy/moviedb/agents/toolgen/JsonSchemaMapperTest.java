package com.graphqlguy.moviedb.agents.toolgen;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The translation rules of Class 2, pinned as executable facts. Each test names one
 * rule, so a refactor that bends a rule fails with the promise it broke.
 * <p>
 * Every schema below is a small slice of the Movie Database rather than a made-up
 * one, so the rule under test is visible against a field the course actually
 * generates a tool from.
 */
class JsonSchemaMapperTest {

    private final JsonSchemaMapper mapper = new JsonSchemaMapper();

    @Test
    void enums_shouldBecomeClosedValueLists() {
        Map<String, Object> jsonSchema = argumentSchema("""
                type Query { movies(genre: Genre!): [Movie!]! }
                enum Genre { DRAMA COMEDY HORROR }
                type Movie { id: ID! }
                """, "movies", "genre");

        assertThat(jsonSchema.get("type")).isEqualTo("string");
        assertThat(stringList(jsonSchema, "enum")).containsExactly("DRAMA", "COMEDY", "HORROR");
    }

    @Test
    void nonNull_shouldMoveToTheParentsRequiredList() {
        Map<String, Object> jsonSchema = argumentSchema("""
                type Query { watchList(input: AddWatchListItemInput!): String }
                input AddWatchListItemInput { watchListId: ID! userNotes: String }
                """, "watchList", "input");

        assertThat(stringList(jsonSchema, "required")).containsExactly("watchListId");
        assertThat(jsonSchema.get("additionalProperties")).isEqualTo(false);
    }

    @Test
    void ids_shouldTravelAsStringsAndListsAsArrays() {
        Map<String, Object> jsonSchema = argumentSchema(
                "type Query { moviesByIds(ids: [ID!]!): String }", "moviesByIds", "ids");

        assertThat(jsonSchema.get("type")).isEqualTo("array");
        assertThat(itemSchema(jsonSchema).get("type")).isEqualTo("string");
    }

    /** Parses the SDL, finds one argument of one query field, and maps its type. */
    private Map<String, Object> argumentSchema(String sdl, String fieldName, String argumentName) {
        GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
                new SchemaParser().parse(sdl));
        GraphQLObjectType queryType = schema.getQueryType();
        GraphQLFieldDefinition field = queryType.getFieldDefinition(fieldName);
        GraphQLArgument argument = field.getArgument(argumentName);
        return mapper.toJsonSchema(argument.getType());
    }

    /** JSON Schema keeps its lists untyped, so the cast lives here and nowhere else. */
    @SuppressWarnings("unchecked")
    private static List<String> stringList(Map<String, Object> jsonSchema, String key) {
        return (List<String>) jsonSchema.get(key);
    }

    /** The schema an array's entries follow, under the "items" key. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> itemSchema(Map<String, Object> jsonSchema) {
        return (Map<String, Object>) jsonSchema.get("items");
    }
}
