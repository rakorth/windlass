# Database migrations

Windlass uses the Spring Boot Flyway starter and the configured PostgreSQL datasource
(`DATABASE_URL`). Flyway runs automatically at startup, including in integration
tests. There is no separate migration command required for deployment.
`spring.sql.init.mode` is `never`; Flyway owns schema initialization.

## Initial schema

Use an empty PostgreSQL schema for a new installation.
[V1](../src/main/resources/db/migration/V1__create_notifications.sql) creates the
notifications table. Flyway tracks applied versions in `flyway_schema_history`.

## Adding a migration

1. Add the next unused version to `src/main/resources/db/migration`, for example
   `V2__add_notification_index.sql`. Use two underscores between version and name.
2. Write the change against the schema produced by all previous migrations.
   For example: `CREATE INDEX idx_notifications_source ON notifications(notification_source);`.
3. Add migration tests for existing records as well as empty databases, and run
   `./mvnw clean verify` with JDK 24 or newer.
4. Update the data model and any affected API or feature documentation.

For changes needing Java logic, put a `BaseJavaMigration` subclass in
`src/main/java/db/migration` using the same version naming convention. Use the
connection supplied by Flyway's migration context.

Flyway validates applied SQL checksums at startup and fails startup on validation
or migration errors. Java migrations do not have automatic checksum validation.
Do not restore `schema.sql` initialization or add startup components that alter
schema outside Flyway.

## Deployment and recovery

Keep a consistent PostgreSQL backup before deploying schema changes. Migrations
run against the configured application database. Flyway clean
is disabled to prevent accidental schema deletion.

If migration fails, inspect the startup error and the database state before
retrying. Do not delete the history table or enable clean to bypass an error.
Restore a known backup when rollback is needed; application rollback alone does
not undo schema changes. Preserve a separate Windlass database backup.
