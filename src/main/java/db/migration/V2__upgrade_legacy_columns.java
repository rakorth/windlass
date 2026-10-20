package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;

/** Bridge for pre-Flyway databases, which may already have some or all of these columns. */
public class V2__upgrade_legacy_columns extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws SQLException {
        addMissingColumn(context.getConnection(), "notifications", "external_links", "TEXT NOT NULL DEFAULT '{}'");
        addMissingColumn(context.getConnection(), "notifications", "unread", "INTEGER NOT NULL DEFAULT 1 CHECK (unread IN (0, 1))");
        for (String table : new String[]{"tasks", "task_templates"}) {
            addMissingColumn(context.getConnection(), table, "webhooks", "TEXT NOT NULL DEFAULT '[]'");
        }
    }

    private void addMissingColumn(Connection connection, String table, String column, String definition) throws SQLException {
        var columns = new HashSet<String>();
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) columns.add(result.getString("name"));
        }
        if (!columns.contains(column)) {
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }
}
