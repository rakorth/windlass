package dev.rakorth.windlass;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FlywayMigrationTests {
    private final String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
    private final PGSimpleDataSource source = new PGSimpleDataSource();

    @BeforeEach
    void createSchema() {
        source.setUrl(System.getProperty("spring.datasource.url"));
        source.setUser(System.getProperty("spring.datasource.username"));
        source.setPassword(System.getProperty("spring.datasource.password"));
        new JdbcTemplate(source).execute("CREATE SCHEMA " + schema);
        source.setCurrentSchema(schema);
    }

    @AfterEach
    void dropSchema() {
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(source).load();
    }

    @Test
    void createsFreshDatabaseAndDoesNotReapplyMigrationsOnRestart() {
        assertEquals(1, flyway().migrate().migrationsExecuted);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO notifications (id, title, description, notification_source, received_on, metadata_map) VALUES ('new', 'Hello', '', 'CI', '2026-01-01T00:00:00Z', '{}')");
        assertEquals(1, jdbc.queryForObject("SELECT unread FROM notifications", Integer.class));
        assertEquals("{}", jdbc.queryForObject("SELECT external_links FROM notifications", String.class));
        jdbc.update("UPDATE notifications SET unread = 0");
        assertEquals(0, flyway().migrate().migrationsExecuted);
        assertEquals(0, jdbc.queryForObject("SELECT unread FROM notifications", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class));
        assertTrue(flyway().validateWithResult().validationSuccessful);
    }
}
