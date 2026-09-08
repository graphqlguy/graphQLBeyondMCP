package com.graphqlguy.moviedb.agents.toolgen;

import graphql.language.Document;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import graphql.validation.ValidationError;
import graphql.validation.Validator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A property every tool in the catalog has to hold: the operation it would send is
 * valid against the schema it was generated from. One assertion covers every tool,
 * including tools nobody has run, and it holds without a model in sight.
 * <p>
 * This is the guarantee that makes an agent's mistakes cheap. An agent choosing a
 * tool and filling its typed arguments cannot produce a document the server refuses
 * to parse, because the document was fixed at generation time and checked here.
 */
class CatalogDocumentsTest {

    private static final Path SCHEMA_SNAPSHOT = Path.of("src/main/resources/schema-snapshot.graphqls");
    private static final Path ALLOW_LIST = Path.of("src/main/resources/tool-allowlist.txt");

    @Test
    void everyGeneratedDocument_shouldValidateAgainstTheSchema() throws Exception {
        GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
                new SchemaParser().parse(Files.readString(SCHEMA_SNAPSHOT)));
        List<OperationTool> catalog = new ToolCatalogGenerator()
                .generate(schema, AllowList.load(ALLOW_LIST), null);

        assertThat(catalog).isNotEmpty();
        for (OperationTool tool : catalog) {
            Document document = new Parser().parseDocument(tool.operationDocument());
            List<ValidationError> errors =
                    new Validator().validateDocument(schema, document, Locale.ENGLISH);
            assertThat(errors)
                    .describedAs("%s sends an invalid document: %s", tool.name(), errors)
                    .isEmpty();
        }
    }
}
