# Database migrations

Windlass uses the Spring Boot Flyway starter and the configured SQLite datasource
(`DATABASE_URL`). Flyway runs automatically at startup, including in integration
tests. There is no separate migration command required for deployment.
`spring.sql.init.mode` is `never`; Flyway owns schema initialization.

## Initial adoption

- [V1](../src/main/resources/db/migration/V1__create_initial_tables.sql) creates
  missing notification, task, and task-template tables. `IF NOT EXISTS` lets it
  also run against existing pre-Flyway databases.
- [V2](../src/main/java/db/migration/V2__upgrade_legacy_columns.java) inspects
  existing columns and adds missing notification `external_links` and `unread`
  fields and task/template `webhooks` fields. Existing values are preserved;
  missing fields default to `{}`, `1`, and `[]`, respectively.

For an existing database without Flyway history, `baseline-on-migrate: true`
and `baseline-version: "0"` record the pre-Flyway state at version 0. Both V1
and V2 then run, so adoption works for older and fully upgraded databases.
New databases run V1 and V2 directly. Flyway records applied versions in
`flyway_schema_history`; subsequent starts only execute pending migrations.
Automatic adoption assumes `DATABASE_URL` points at a Windlass database.
It does not repair arbitrary or manually corrupted schemas.

## Adding a migration

1. Add the next unused version to `src/main/resources/db/migration`, for example
   `V3__add_notification_index.sql`. Use two underscores between version and name.
2. Write the change against the schema produced by all previous migrations.
   For example: `CREATE INDEX idx_notifications_source ON notifications(notification_source);`.
3. Add migration tests for existing records as well as empty databases, and run
   `./mvnw clean verify` with JDK 24 or newer.
4. Update the data model and any affected API or feature documentation.

For changes needing Java logic, put a `BaseJavaMigration` subclass in
`src/main/java/db/migration` using the same version naming convention. Use the
connection supplied by Flyway's migration context. V2 uses Java because SQLite
requires inspecting columns before conditionally adding them.

Never edit or renumber a migration that has shipped; add a new version instead.
Flyway validates applied SQL checksums at startup and fails startup on validation
or migration errors. Java migrations do not have automatic checksum validation.
Do not restore `schema.sql` initialization or add startup components that alter
schema outside Flyway.

## Deployment and recovery

Keep a consistent SQLite backup before deploying schema changes (stop Windlass
before copying its database, or use SQLite's backup facilities). Deploy normally;
migrations use the same database file and volume as the application. Flyway clean
is disabled to prevent accidental schema deletion.

If migration fails, inspect the startup error and the database state before
retrying. Do not delete the history table or enable clean to bypass an error.
Restore a known backup when rollback is needed; application rollback alone does
not undo schema changes. The n8n backup service backs up n8n and does not replace
a Windlass database backup.
