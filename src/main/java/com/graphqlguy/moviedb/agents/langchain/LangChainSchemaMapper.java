package com.graphqlguy.moviedb.agents.langchain;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;
import java.util.Map;

/**
 * Translates the catalog's JSON Schema maps into LangChain4j's typed schema model.
 * Spring AI accepts a JSON Schema string; LangChain4j models the same information
 * as objects (JsonObjectSchema, JsonEnumSchema, and friends). Same facts, second
 * dialect, and one more small proof that the catalog is the stable artifact while
 * frameworks are interchangeable consumers of it.
 */
public class LangChainSchemaMapper {

    @SuppressWarnings("unchecked")
    public JsonObjectSchema toObjectSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        Object description = schema.get("description");
        if (description instanceof String text) {
            builder.description(text);
        }
        Map<String, Object> properties =
                (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        for (Map.Entry<String, Object> property : properties.entrySet()) {
            builder.addProperty(property.getKey(),
                    toElement((Map<String, Object>) property.getValue()));
        }
        Object required = schema.get("required");
        if (required instanceof List<?> names) {
            builder.required(names.stream().map(Object::toString).toList());
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private JsonSchemaElement toElement(Map<String, Object> schema) {
        String description = schema.get("description") instanceof String s ? s : null;
        if (schema.get("enum") instanceof List<?> values) {
            return JsonEnumSchema.builder()
                    .enumValues(values.stream().map(Object::toString).toList())
                    .description(description)
                    .build();
        }
        String type = String.valueOf(schema.get("type"));
        return switch (type) {
            case "integer" -> JsonIntegerSchema.builder().description(description).build();
            case "number" -> JsonNumberSchema.builder().description(description).build();
            case "boolean" -> JsonBooleanSchema.builder().description(description).build();
            case "array" -> JsonArraySchema.builder()
                    .items(toElement((Map<String, Object>) schema.get("items")))
                    .description(description)
                    .build();
            case "object" -> toObjectSchema(schema);
            default -> JsonStringSchema.builder().description(description).build();
        };
    }
}
