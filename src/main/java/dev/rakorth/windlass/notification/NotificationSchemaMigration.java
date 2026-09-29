package dev.rakorth.windlass.notification;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Additive migration for databases created before external links and unread state were introduced. */
@Component
@DependsOnDatabaseInitialization
public class NotificationSchemaMigration {
    private final JdbcTemplate jdbc;

    public NotificationSchemaMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void migrate() {
        var columns = jdbc.query("PRAGMA table_info(notifications)", (rs, row) -> rs.getString("name"));
        if (!columns.contains("unread")) {
            jdbc.execute("ALTER TABLE notifications ADD COLUMN unread INTEGER NOT NULL DEFAULT 1 CHECK (unread IN (0, 1))");
        }
        if (!columns.contains("external_links")) {
            jdbc.execute("ALTER TABLE notifications ADD COLUMN external_links TEXT NOT NULL DEFAULT '{}'");
        }
    }
}
