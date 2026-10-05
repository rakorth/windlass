package dev.rakorth.windlass.task;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Add missing task fields to existing databases. */
@Component
@DependsOnDatabaseInitialization
public class TaskSchemaMigration {
    private final JdbcTemplate jdbc;

    public TaskSchemaMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void migrate() {
        var columns = jdbc.query("PRAGMA table_info(tasks)", (rs, row) -> rs.getString("name"));
        if (!columns.contains("notify_me_on")) {
            jdbc.execute("ALTER TABLE tasks ADD COLUMN notify_me_on TEXT");
        }
        if (!columns.contains("status")) {
            jdbc.execute("ALTER TABLE tasks ADD COLUMN status TEXT NOT NULL DEFAULT 'PENDING'");
        }
        if (!columns.contains("steps")) {
            jdbc.execute("ALTER TABLE tasks ADD COLUMN steps TEXT NOT NULL DEFAULT '[]'");
        }
    }
}
