# Backend data models

## Task

[`Task`](../src/main/java/dev/rakorth/windlass/task/Task.java) is a Java record
with the following fields:

| Field | JSON type | Meaning |
| --- | --- | --- |
| `id` | string (UUID) | Generated, immutable identifier |
| `name` | string | Required, nonblank, max 200 characters; stored trimmed |
| `description` | string | Optional, max 10,000 characters; missing/null becomes `""` |
| `links` | object with string values | Nonblank labels mapped to absolute HTTP/HTTPS URLs with a host; missing/null becomes `{}` |
| `metadata` | object | Arbitrary metadata, including nested JSON values; missing/null becomes `{}` |
| `status` | string | `PENDING`, `DONE`, `SKIPPED`, or `WAITING` (case-sensitive); missing/null becomes `PENDING` |
| `notifyMeOn` | ISO 8601 datetime string or null | Optional datetime with a timezone; missing/null becomes null |
| `webhooks` | array of HTTP(S) URL strings | Status-change webhook destinations; missing/null becomes `[]` |
| `steps` | array of step objects | Ordered steps; missing/null becomes `[]` |

Tasks are persisted using `JdbcTemplate` in the SQLite `tasks` table.
Columns are TEXT; all are NOT NULL except `notify_me_on`, which stores
the nullable `notifyMeOn` instant in UTC. Missing/null on PUT clears this field;
existing tasks receive null through the startup migration.
`id` is the primary key and both maps are serialized JSON.
Startup creates the table if absent, including for existing notification databases.
The [REST API](api.md#tasks) supports creation, retrieval, replacement, deletion,
and listing ordered by name then ID. PUT resets omitted optional fields to their
defaults. Task status is independent of step statuses. Existing tasks receive
`PENDING` through an additive startup migration that preserves saved statuses.

Each step has a required nonblank `name` (max 200 characters, stored trimmed),
a required `status` enum, optional `webhooks` URL list (missing/null becomes `[]`),
and optional `metadata` and `links` maps (missing/null becomes `{}`). Status accepts exactly `DONE`, `PENDING`, or `SKIPPED` (case-sensitive). Step links follow task link validation. Null steps
are invalid. Steps are stored as JSON in a NOT NULL TEXT column with default `[]`;
an additive startup migration gives existing tasks an empty list and preserves
steps on subsequent starts. PUT replaces the complete ordered list.

Task and step webhook lists use absolute HTTP(S) URLs. Startup adds empty lists
to existing tasks and templates; older step JSON defaults to empty lists on read.
See the [delivery contract](api.md#task-status-webhooks) for events and failure notifications.

## Notification

## JSON contract

| Field | JSON type | POST behavior | PUT behavior |
| --- | --- | --- | --- |
| `id` | string (UUID) | Generated; do not send | Immutable; identify target in URL |
| `title` | string | Required, nonblank, max 200 characters | Required, same validation |
| `description` | string | Optional, max 10,000 characters; missing/null becomes `""` | Missing/null becomes `""` |
| `notification_source` | string | Required, nonblank, max 200 characters | Required, same validation |
| `received_on` | ISO 8601 timestamp string | Missing/null uses server's current instant | Missing/null retains stored value |
| `metadata_map` | object | Missing/null becomes `{}` | Missing/null becomes `{}` |
| `external_links` | object with string values | Missing/null becomes `{}` | Missing/null becomes `{}` |
| `unread` | boolean | Server initializes to `true`; do not send | Preserved; do not send |

Title and source are stripped of leading/trailing whitespace when stored; length
validation applies to submitted strings. Description retains its whitespace.
Use timestamps with an explicit timezone, preferably UTC `Z`; responses use
Java `Instant` serialization. The browser displays them in local time.

`metadata_map` supports nested JSON objects, arrays, strings, numbers, booleans,
and null values. It has no domain-specific schema or enforced event uniqueness.

`external_links` maps nonblank labels to nonblank absolute HTTP/HTTPS URLs. Each
URL must parse with a host. Relative URLs and other schemes are invalid. An
example map is `{"Issue": "https://github.com/example/project/issues/42"}`.

## Example response

```json
{
  "id": "3d174fe4-beb7-4901-a240-bce40261e1e7",
  "title": "Build complete",
  "description": "Release build passed.",
  "notification_source": "CI",
  "received_on": "2026-09-29T12:00:00Z",
  "metadata_map": {"build_id": "build-42", "success": true},
  "external_links": {"Build": "https://ci.example.com/builds/42"},
  "unread": true
}
```

This is illustrative, not an existing record. Omit `id` and `unread` when using
its content as a create/update request.

## SQLite representation

The `notifications` table stores ID, title, description, source, and timestamp as
TEXT. Both maps are serialized JSON in TEXT columns. `unread` is INTEGER with a
check restricting it to `0` or `1`; its default is `1`. All columns are NOT NULL.
The ID is the primary key. Length, URL, and JSON-object validation is enforced by
the application rather than equivalent SQLite constraints.

The service uses `JdbcTemplate`, parameterized SQL, and a connection pool with
maximum size 1. It is not a JPA entity/repository implementation. Collection
ordering is `julianday(received_on) DESC, id`.

## Startup and migration

[The schema](../src/main/resources/schema.sql) creates the table if absent.
[The migration component](../src/main/java/dev/rakorth/windlass/notification/NotificationSchemaMigration.java)
then inspects existing columns and adds missing `external_links` and `unread`
columns. Older records receive `{}` and `true`, respectively. Re-running the
migration preserves existing values, including records already marked seen.
This is a small additive migration, not a general versioned migration framework.

Local runs default to `./notifications.db`; containers use
`/data/notifications.db`. See [deployment and persistence](deployment.md) before
changing storage location or removing volumes.

## Task template

Task templates share the [task](#task) model, including all eight writable fields
and ordered steps. The SQLite `task_templates` table is created automatically at
startup, separately from `tasks`. Both libraries use the same normalization,
validation, and persistence logic. A template's ID identifies the template;
creating a task from it assigns a fresh task ID and persists an independent copy.
There is no ongoing link between a template and its copies.
