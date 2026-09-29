# Architecture and code map

## Request flow

```text
Browser UI or REST client
    -> NotificationController (HTTP routing and request validation)
    -> NotificationService (normalization, URL checks, CRUD, seen state)
    -> JdbcTemplate -> SQLite notifications table
```

Spring Boot also serves static files from `src/main/resources/static` at the
same origin. The browser calls `/api/notifications` with `fetch`. There is no
frontend build step or separate frontend server.

The stack is Spring Boot 4.1.1, Spring MVC, Jakarta Validation, Spring JDBC,
Jackson JSON serialization, SQLite JDBC, and vanilla HTML/CSS/JavaScript.
The Maven Java compilation target is 24. Runtime Docker images use Java 25.
There is no JPA repository, Vaadin UI, or Spring Data REST layer in the current app.

## Source map

Paths below are relative to this document; links point to the implementation.

| Concern | Source |
| --- | --- |
| Dependencies, Java target, build plugins | [pom.xml](../pom.xml) |
| Application entry point | [WindlassApplication.java](../src/main/java/dev/rakorth/windlass/WindlassApplication.java) |
| Response model | [Notification.java](../src/main/java/dev/rakorth/windlass/notification/Notification.java) |
| Writable fields and validation annotations | [NotificationRequest.java](../src/main/java/dev/rakorth/windlass/notification/NotificationRequest.java) |
| REST routes and success statuses | [NotificationController.java](../src/main/java/dev/rakorth/windlass/notification/NotificationController.java) |
| SQL, defaults, ordering, link checks, seen operation | [NotificationService.java](../src/main/java/dev/rakorth/windlass/notification/NotificationService.java) |
| Initial SQLite table | [schema.sql](../src/main/resources/schema.sql) |
| Additive upgrades for existing databases | [NotificationSchemaMigration.java](../src/main/java/dev/rakorth/windlass/notification/NotificationSchemaMigration.java) |
| Datasource and server defaults | [application.yaml](../src/main/resources/application.yaml) |
| Page, dialog, and card template | [index.html](../src/main/resources/static/index.html) |
| Fetch calls, local state, search, rendering, actions | [app.js](../src/main/resources/static/app.js) |
| Dark theme and compact responsive layout | [styles.css](../src/main/resources/static/styles.css) |
| API and migration integration tests | [WindlassApplicationTests.java](../src/test/java/dev/rakorth/windlass/WindlassApplicationTests.java) |
| Application container | [Dockerfile](../Dockerfile) |
| SQLite volume and port mapping | [compose.yaml](../compose.yaml) |
| GitHub image build/publish automation | [docker.yml](../.github/workflows/docker.yml) |
| Agent REST workflow | [SKILL.md](../skills/windlass-rest/SKILL.md) |

## Implementation invariants

- Startup schema initialization precedes additive migrations, which precede service use.
- SQL uses bound parameters for notification values and IDs.
- Maps are serialized to JSON text and deserialized on reads.
- Content updates exclude the unread column; marking seen changes only that column.
- GET returns persisted values. POST generates a UUID and uses the database unread default.
- The UI keeps one in-memory collection; search filters it without a server request.
- The form sends the full editable payload. New fields may require changes across model, request, service SQL, schema, migration, form, and rendering.
- A field added only to `CREATE TABLE IF NOT EXISTS` will not upgrade an existing database; add an appropriate migration too.

## Verification

```sh
./mvnw test
./mvnw package
node --check src/main/resources/static/app.js
docker compose config --quiet
```

The six current integration tests use an isolated in-memory SQLite database and
cover CRUD, missing IDs, defaults, validation, nested metadata, external-link
persistence and invalid URLs, legacy-schema migration, unread persistence across
edits, repeated mark-as-seen calls, newest-first ordering, and static UI delivery.
They do not exercise browser layout or click interactions in a real browser.

For container changes, `docker build -t windlass:local .` runs Maven `verify`
inside the build stage. A runtime smoke check should verify the UI/API and a
write/read in an isolated database volume as the non-root user. Do not use real
notifications as disposable test data. GitHub workflow behavior can be checked
with `actionlint` when available; a local build does not verify registry access.
