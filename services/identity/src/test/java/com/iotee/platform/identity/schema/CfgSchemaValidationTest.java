package com.iotee.platform.identity.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exercises the draft cfg.yaml JSON Schema (ADR 0012 Decision 3) against
 * three synthetic fixtures, proving the schema is wired up and actually
 * enforces something -- not just present on disk -- including ADR 0016's
 * pooled-rls-only tenant_mode.
 *
 * <p>Reads {@code contracts/cfg/cfg.schema.json} directly from the
 * shared {@code contracts/} directory at the repository root, rather
 * than from this module's own classpath resources -- there is no copy
 * of the schema inside this module (ADR 0013: {@code contracts/} is
 * source only, and this service validates against the shared file
 * itself, never a duplicate that could drift from it). The relative
 * path below assumes Maven Surefire's default working directory (this
 * module's own {@code basedir}, i.e. {@code services/identity/}) --
 * the same assumption Maven itself makes for every module's tests
 * unless a build overrides {@code <workingDirectory>}, which this
 * module's {@code pom.xml} does not.
 */
class CfgSchemaValidationTest {

    private static final Path SCHEMA_PATH =
            Path.of("..", "..", "contracts", "cfg", "cfg.schema.json");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private JsonSchema loadSchema() throws IOException {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream schemaStream = Files.newInputStream(SCHEMA_PATH)) {
            return factory.getSchema(schemaStream);
        }
    }

    private JsonNode loadYamlFixture(String resourceName) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/" + resourceName)) {
            return yamlMapper.readTree(in);
        }
    }

    @Test
    void validFixturePassesSchemaValidation() throws Exception {
        JsonSchema schema = loadSchema();
        JsonNode node = loadYamlFixture("cfg.sample.valid.yaml");

        Set<com.networknt.schema.ValidationMessage> errors = schema.validate(node);

        assertTrue(errors.isEmpty(), () -> "expected no validation errors, got: " + errors);
    }

    @Test
    void invalidFixtureFailsSchemaValidation() throws Exception {
        JsonSchema schema = loadSchema();
        JsonNode node = loadYamlFixture("cfg.sample.invalid.yaml");

        Set<com.networknt.schema.ValidationMessage> errors = schema.validate(node);

        assertFalse(errors.isEmpty(), "expected the deliberately-invalid fixture to fail validation");
    }

    /**
     * ADR 0016 fixes tenancy to pooled tenant_id + PostgreSQL RLS and
     * rejects schema-per-tenant, which was this draft schema's old
     * default. Guards against that value being accepted again.
     */
    @Test
    void schemaPerTenantIsRejected() throws Exception {
        JsonSchema schema = loadSchema();
        JsonNode node = loadYamlFixture("cfg.sample.schema-per-tenant.yaml");

        Set<com.networknt.schema.ValidationMessage> errors = schema.validate(node);

        assertFalse(errors.isEmpty(), "expected tenant_mode: schema-per-tenant to be rejected (ADR 0016)");
    }
}
