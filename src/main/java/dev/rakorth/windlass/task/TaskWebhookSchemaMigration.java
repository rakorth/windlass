package dev.rakorth.windlass.task;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
public class TaskWebhookSchemaMigration {
    private final JdbcTemplate jdbc;

    public TaskWebhookSchemaMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    public void migrate() {
        for (String table : new String[]{"tasks", "task_templates"}) {
            var columns = jdbc.query("PRAGMA table_info(" + table + ")", (rs, row) -> rs.getString("name"));
            if (!columns.contains("webhooks")) {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN webhooks TEXT NOT NULL DEFAULT '[]'");
            }
        }
    }
}
