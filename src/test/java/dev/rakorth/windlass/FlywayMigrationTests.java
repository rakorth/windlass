package dev.rakorth.windlass;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FlywayMigrationTests {
    @TempDir Path directory;

    private DriverManagerDataSource source() {
        return new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("migration.db"));
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(source()).baselineOnMigrate(true).baselineVersion("0").load();
    }

    @Test
    void createsFreshDatabaseAndDoesNotReapplyMigrationsOnRestart() {
        assertEquals(3, flyway().migrate().migrationsExecuted);
        var jdbc = new JdbcTemplate(source());
        jdbc.update("INSERT INTO notifications (id, title, description, notification_source, received_on, metadata_map) VALUES ('new', 'Hello', '', 'CI', '2026-01-01T00:00:00Z', '{}')");
        assertEquals(1, jdbc.queryForObject("SELECT unread FROM notifications", Integer.class));
        assertEquals("{}", jdbc.queryForObject("SELECT external_links FROM notifications", String.class));
        assertTaskTablesRemoved(jdbc);
        jdbc.update("UPDATE notifications SET unread = 0");
        assertEquals(0, flyway().migrate().migrationsExecuted);
        assertEquals(0, jdbc.queryForObject("SELECT unread FROM notifications", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE type <> 'BASELINE'", Integer.class));
        assertTrue(flyway().validateWithResult().validationSuccessful);
    }

    @Test
    void adoptsLegacyDatabasePreservesNotificationsAndRemovesTaskStorage() {
        var jdbc = new JdbcTemplate(source());
        jdbc.execute("""
                CREATE TABLE notifications (id TEXT PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL,
                notification_source TEXT NOT NULL, received_on TEXT NOT NULL, metadata_map TEXT NOT NULL)
                """);
        jdbc.update("INSERT INTO notifications VALUES ('old', 'Keep me', 'Description', 'CI', '2026-01-01T00:00:00Z', '{\"attempt\":2}')");
        for (String table : new String[]{"tasks", "task_templates"}) {
            jdbc.execute("CREATE TABLE " + table + " (id TEXT PRIMARY KEY, name TEXT, description TEXT, links TEXT, metadata TEXT, steps TEXT, status TEXT, notify_me_on TEXT)");
            jdbc.update("INSERT INTO " + table + " VALUES ('old', 'Task', '', '{}', '{}', '[{\"name\":\"Step\"}]', 'DONE', '2026-01-01T00:00:00Z')");
        }
        assertEquals(3, flyway().migrate().migrationsExecuted);
        assertEquals("Keep me", jdbc.queryForObject("SELECT title FROM notifications", String.class));
        assertEquals("{\"attempt\":2}", jdbc.queryForObject("SELECT metadata_map FROM notifications", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT unread FROM notifications", Integer.class));
        assertEquals("{}", jdbc.queryForObject("SELECT external_links FROM notifications", String.class));
        assertTaskTablesRemoved(jdbc);
        assertEquals("0", jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE type = 'BASELINE'", String.class));
        assertEquals(0, flyway().migrate().migrationsExecuted);
    }

    @Test
    void preservesAlreadyUpgradedNotificationColumnsAndRemovesTaskStorage() {
        var jdbc = new JdbcTemplate(source());
        jdbc.execute("""
                CREATE TABLE notifications (id TEXT PRIMARY KEY, title TEXT, description TEXT,
                notification_source TEXT, received_on TEXT, metadata_map TEXT,
                external_links TEXT NOT NULL DEFAULT '{}', unread INTEGER NOT NULL DEFAULT 1)
                """);
        jdbc.update("INSERT INTO notifications VALUES ('old', 'Read', '', 'CI', '2026-01-01T00:00:00Z', '{}', '{\"Build\":\"https://example.com\"}', 0)");
        jdbc.execute("CREATE TABLE tasks (id TEXT, name TEXT, description TEXT, links TEXT, metadata TEXT, steps TEXT, status TEXT, notify_me_on TEXT, webhooks TEXT NOT NULL DEFAULT '[]')");
        jdbc.update("INSERT INTO tasks (id, webhooks) VALUES ('old', '[{\"url\":\"https://example.com/hook\"}]')");
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(WindlassApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .run("--spring.datasource.url=" + source().getUrl(),
                        "--spring.ai.mcp.server.enabled=false",
                        "--windlass.ref-docs-directory=" + directory.resolve("ref-docs"),
                        "--windlass.json-conf-directory=" + directory.resolve("json-conf"))) {
            var configuredFlyway = context.getBean(Flyway.class);
            assertEquals("3", configuredFlyway.info().current().getVersion().toString());
            assertEquals(0, configuredFlyway.migrate().migrationsExecuted);
            assertTrue(configuredFlyway.getConfiguration().isCleanDisabled());
        }
        assertEquals(0, jdbc.queryForObject("SELECT unread FROM notifications", Integer.class));
        assertEquals("{\"Build\":\"https://example.com\"}", jdbc.queryForObject("SELECT external_links FROM notifications", String.class));
        assertTaskTablesRemoved(jdbc);
        assertEquals(0, flyway().migrate().migrationsExecuted);
    }

    @Test
    void upgradesVersionTwoDatabaseWithoutChangingNotifications() {
        var previous = Flyway.configure().dataSource(source()).target("2").load();
        assertEquals(2, previous.migrate().migrationsExecuted);
        var jdbc = new JdbcTemplate(source());
        jdbc.update("INSERT INTO notifications VALUES ('keep', 'Saved', 'Description', 'CI', '2026-01-01T00:00:00Z', '{\"nested\":{\"value\":1}}', '{\"Build\":\"https://example.com\"}', 0)");
        var notification = jdbc.queryForMap("SELECT * FROM notifications");
        for (String table : new String[]{"tasks", "task_templates"}) {
            jdbc.update("INSERT INTO " + table + " (id, name, description, links, metadata, steps, status, webhooks) VALUES ('retired', 'Old work', '', '{}', '{}', '[]', 'DONE', '[]')");
        }
        assertEquals(1, flyway().migrate().migrationsExecuted);
        assertTaskTablesRemoved(jdbc);
        assertEquals(notification, jdbc.queryForMap("SELECT * FROM notifications"));
        assertEquals(0, flyway().migrate().migrationsExecuted);
        assertTrue(flyway().validateWithResult().validationSuccessful);
    }

    private void assertTaskTablesRemoved(JdbcTemplate jdbc) {
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN ('tasks', 'task_templates')",
                Integer.class));
    }
}
