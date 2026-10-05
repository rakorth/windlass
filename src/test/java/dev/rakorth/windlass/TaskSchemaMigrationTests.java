package dev.rakorth.windlass;

import dev.rakorth.windlass.task.TaskSchemaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskSchemaMigrationTests {
    @Test
    void upgradesExistingTasksAndCanRunAgain() {
        var datasource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new JdbcTemplate(datasource);
            jdbc.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY, name TEXT NOT NULL)");
            jdbc.update("INSERT INTO tasks (id, name) VALUES ('old', 'Existing task')");
            var migration = new TaskSchemaMigration(jdbc);
            migration.migrate();
            migration.migrate();
            assertEquals("[]", jdbc.queryForObject("SELECT steps FROM tasks WHERE id = 'old'", String.class));
            assertEquals("Existing task", jdbc.queryForObject("SELECT name FROM tasks WHERE id = 'old'", String.class));
        } finally {
            datasource.destroy();
        }
    }
}
