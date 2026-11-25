# Architecture and code map

## Request flow

```text
Browser UI or REST client
    -> NotificationController (HTTP routing and request validation)
    -> NotificationService (normalization, URL checks, CRUD, seen state)
    -> JdbcTemplate -> PostgreSQL notifications table
```

Spring Boot also serves static files from `src/main/resources/static` at the
same origin. The browser calls `/api/notifications` with `fetch`. There is no
frontend build step or separate frontend server.

MCP clients connect to `/mcp` using Spring AI 2.0.1's WebMVC Streamable HTTP
transport. `NotificationTools` validates writable requests and calls the same
`NotificationService` directly. Tools use synchronous execution and share REST
persistence and normalization. See [MCP server](mcp.md) for the interface.

The stack is Spring Boot 4.1.1, Spring MVC, Jakarta Validation, Spring JDBC,
Jackson JSON serialization, PostgreSQL JDBC, and vanilla HTML/CSS/JavaScript.
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
| MCP notification tools and validation | [NotificationTools.java](../src/main/java/dev/rakorth/windlass/mcp/NotificationTools.java) |
| SQL, defaults, ordering, link checks, seen operation | [NotificationService.java](../src/main/java/dev/rakorth/windlass/notification/NotificationService.java) |
| Initial PostgreSQL schema | [V1__create_notifications.sql](../src/main/resources/db/migration/V1__create_notifications.sql) |
| Schema initialization and restart test | [FlywayMigrationTests.java](../src/test/java/dev/rakorth/windlass/FlywayMigrationTests.java) |
| Datasource and server defaults | [application.yaml](../src/main/resources/application.yaml) |
| Page, dialog, and card template | [index.html](../src/main/resources/static/index.html) |
| Shared bookmarkable list state | [list-url.js](../src/main/resources/static/list-url.js) |
| Fetch calls, local state, search, rendering, actions | [app.js](../src/main/resources/static/app.js) |
| Dark theme and compact responsive layout | [styles.css](../src/main/resources/static/styles.css) |
| API and migration integration tests | [WindlassApplicationTests.java](../src/test/java/dev/rakorth/windlass/WindlassApplicationTests.java) |
| Application container | [Dockerfile](../Dockerfile) |
| GitHub image build/publish automation | [docker.yml](../.github/workflows/docker.yml) |
| Agent REST workflow | [SKILL.md](../skills/windlass-rest/SKILL.md) |

## Implementation invariants

- Flyway runs versioned migrations before database-dependent services are initialized; SQL script initialization is disabled.
- SQL uses bound parameters for notification values and IDs.
- Maps are serialized to JSON text and deserialized on reads.
- Content updates exclude the unread column; marking seen changes only that column.
- GET returns persisted values. POST generates a UUID and uses the database unread default.
- The UI keeps one page in memory; search filters that page without a server request. Pagination and unread filtering use server requests.
- The form sends the full editable payload. New fields may require changes across model, request, service SQL, schema, migration, form, and rendering.
- Add schema changes as new versioned migrations; do not edit migrations that have already shipped.

## Verification

```sh
./mvnw test
./mvnw package
node --check src/main/resources/static/app.js
```

The notification integration tests use a dedicated PostgreSQL test database and
cover CRUD, missing IDs, defaults, validation, nested metadata, external-link
persistence and invalid URLs, schema initialization, unread persistence across
edits, repeated mark-as-seen calls, newest-first ordering, and static UI delivery.
They do not exercise browser layout or click interactions in a real browser.
MCP tests exercise the real Streamable HTTP endpoint with the Java MCP client,
including discovery, schemas, CRUD, validation, pagination, shared REST data,
and disabling MCP while keeping REST available.
CI runs the Java suite against PostgreSQL before building the Docker image.
For container changes, build with `docker build -t windlass:local .` and
verify a write/read through the running container against a test database. Do not use real
notifications as disposable test data. GitHub workflow behavior can be checked
with `actionlint` when available; a local build does not verify registry access.

List URL parsing and serialization tests run with Node.js 22 or newer:

```sh
node src/test/js/list-url.test.mjs
```
