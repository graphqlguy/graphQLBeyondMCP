package com.graphqlguy.moviedb.agents.toolgen;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The drift gate Class 2 promised, as a test. The catalog is regenerated from
 * the committed schema snapshot and compared byte for byte against the golden
 * copy; when the schema (or the generator) changes, this test fails, and the
 * fix is a deliberate regeneration reviewed in the same pull request as the
 * change that caused it. "The schema and the catalog disagree" becomes a red
 * build a human resolves instead of a surprise an agent discovers.
 */
class ToolCatalogGoldenTest {

    private static final Path SCHEMA_SNAPSHOT = Path.of("src/main/resources/schema-snapshot.graphqls");
    private static final Path ALLOW_LIST = Path.of("src/main/resources/tool-allowlist.txt");
    private static final Path GOLDEN_CATALOG = Path.of("src/test/resources/catalog-golden.json");

    /** Tools the support role may see, from the allow-list's role suffixes. */
    private static final int TOOLS_FOR_SUPPORT = 5;
    /** The concierge sees those plus the watch-list read and the one permitted write. */
    private static final int TOOLS_FOR_CONCIERGE = 7;
    private static final int WRITES_FOR_CONCIERGE = 1;

    private final ToolCatalogGenerator generator = new ToolCatalogGenerator();

    @Test
    void generatedCatalog_shouldMatchTheCommittedGolden() throws Exception {
        List<OperationTool> catalog = generateFor(null);
        String json = generator.toJson(catalog);

        Path golden = GOLDEN_CATALOG;
        if (!Files.exists(golden)) {
            // First run: write the file and fail, so a person reads the catalog before
            // it becomes the thing every later run is compared against.
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, json);
            fail("Golden file created at " + golden + ". Read it, and commit it once"
                    + " the catalog it describes is the catalog you meant to ship.");
        }
        assertThat(json.strip()).isEqualTo(Files.readString(golden).strip());
    }

    @Test
    void roles_shouldNarrowTheCatalog() throws Exception {
        assertThat(generateFor("support")).hasSize(TOOLS_FOR_SUPPORT);
        assertThat(generateFor("concierge")).hasSize(TOOLS_FOR_CONCIERGE);
        assertThat(generateFor("concierge").stream().filter(OperationTool::mutation))
                .hasSize(WRITES_FOR_CONCIERGE);
    }

    /** The catalog as the application builds it: committed snapshot, committed allow-list. */
    private List<OperationTool> generateFor(String role) throws Exception {
        GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
                new SchemaParser().parse(Files.readString(SCHEMA_SNAPSHOT)));
        return generator.generate(schema, AllowList.load(ALLOW_LIST), role);
    }
}
