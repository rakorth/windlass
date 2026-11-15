# Notification data model

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

Flyway manages SQLite schema versions at startup. Historical V1 and V2 migrations
remain unchanged for upgrade compatibility. V3 removes the retired task and template
tables, including their saved records, while retaining notifications and unread state.
Applied versions are tracked in `flyway_schema_history`, so upgrades are not
repeated on restart. See [database migrations](migrations.md) for migration files
and development workflow.

Local runs default to `./notifications.db`; containers use
`/data/notifications.db`. See [deployment and persistence](deployment.md) before
changing storage location or removing volumes.
