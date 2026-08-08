package org.ntrloc.graph.db.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

// Verifies the actual upgrade path Flyway exists for -- not just "a fresh database gets every
// migration," which the rest of the suite already covers via AbstractIntegrationTest's shared
// container, but "a database that already has an older version applied gets a later one applied
// correctly on top." V1_0_0_1__baseline.sql faithfully includes the UNIQUE(entity_id,
// link_definition_id) bug 1.0.0 actually shipped with (see that file's own comment), so migrating
// only as far as 1.0.0.1 genuinely reproduces a real 1.0.0 database, and finishing the migration
// to 1.0.1.1 exercises V1_0_1_1__drop_stale_perspective_unique_constraint.sql doing real
// corrective work rather than a no-op.
//
// Uses Flyway's Java API directly against its own dedicated container, not
// AbstractIntegrationTest's shared singleton -- every other test class's full Spring Boot context
// boot would have already run every migration against that container, leaving no way to inspect
// the intermediate 1.0.0.1-only state. Deliberately not @Testcontainers/@Container (see
// AbstractIntegrationTest's own comment on why that extension is avoided for a *shared* static
// field); plain @BeforeAll/@AfterAll is safe here since this container is private to this one
// class, not shared across others.
class FlywayMigrationUpgradeIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    private Flyway flywayTargeting(String target) {
        var config = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private boolean perspectiveUniqueConstraintExists() throws Exception {
        try (Connection conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT 1 FROM pg_constraint
                     WHERE conname = 'schema_entity_link_perspective_entity_id_link_definition_id_key'
                     """)) {
            return rs.next();
        }
    }

    @Test
    void existingV1_0_0Database_getsTheV1_0_1_1FixAppliedOnUpgrade() throws Exception {
        flywayTargeting("1.0.0.1").migrate();
        assertThat(perspectiveUniqueConstraintExists())
                .as("1.0.0 shipped with the UNIQUE constraint -- baseline alone must still reproduce it")
                .isTrue();

        flywayTargeting(null).migrate();
        assertThat(perspectiveUniqueConstraintExists())
                .as("upgrading to 1.0.1 must remove the constraint for real, not as a no-op")
                .isFalse();
    }
}
