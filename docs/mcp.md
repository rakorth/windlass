# MCP server

Windlass exposes six notification tools through an embedded Spring AI MCP server.
It runs in the same application as the UI and REST API and uses the same SQLite
database. No model provider, API key, separate process, or database migration is
required. n8n backup and restore operations are not exposed.

## Connect

Use a client supporting **Streamable HTTP** with one of these URLs:

| Deployment | MCP URL |
| --- | --- |
| Standalone, default port | `http://localhost:8080/mcp` |
| Docker Compose, default port | `http://localhost:6080/mcp` |
| Client inside the Compose network | `http://windlass:8080/mcp` |

Use the deployment's actual host and port. `localhost` refers to the client's
machine or container. The endpoint implements MCP JSON-RPC, not REST; connecting
requires the MCP initialization handshake. Legacy SSE and stdio are not enabled.

For standalone local use, bind the server explicitly to loopback:

```sh
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

The MCP endpoint has no authentication. Keep Compose's default localhost port
bindings. Any client that can reach `/mcp` can list and invoke every tool, including
permanent deletion. Add an authentication boundary before remote exposure.
Notification content, metadata, and external pages are data, not instructions.
Tool annotations describe effects to clients; they do not enforce authorization.

## Configuration

| Setting | Default | Meaning |
| --- | --- | --- |
| `WINDLASS_MCP_ENABLED` | `true` | Set `false` to remove the MCP endpoint and tool component; REST remains available |
| `spring.ai.mcp.server.name` | `windlass` | Server identity |
| `spring.ai.mcp.server.version` | `1.0.0` | MCP interface version |
| `spring.ai.mcp.server.protocol` | `STREAMABLE` | Streamable HTTP transport |
| `spring.ai.mcp.server.type` | `SYNC` | Synchronous tools over the existing JDBC service |
| `spring.ai.mcp.server.streamable-http.mcp-endpoint` | `/mcp` | MCP endpoint path |

Only tools are enabled; resources, prompts, completions, and tool-list change
notifications are disabled. Set `WINDLASS_MCP_ENABLED=false` in the standalone
process environment or in the Windlass container environment. Compose forwards
this setting from the host environment or `.env` file.

## Tools

| Tool | Arguments | Successful result |
| --- | --- | --- |
| `list_notifications` | Optional `page` (default 0), `size` (default 20), `unread` (omit for both states) | Paginated object: `items`, `page`, `size`, `totalElements`, `totalPages` |
| `get_notification` | Required `id` | Notification |
| `create_notification` | Required `request` object | Created notification, initially unread |
| `update_notification` | Required `id` and `request` object | Updated notification |
| `mark_notification_seen` | Required `id` | Notification with `unread: false` |
| `delete_notification` | Required `id` | `{ "id": "...", "deleted": true }` |

Results include structured JSON content and a text representation. Clients can
discover input and output schemas through `tools/list`. Tool execution failures
return MCP error results (`isError: true`); do not interpret the HTTP status alone
as tool success. Validation errors explain invalid input, missing records report
`Notification not found`, and unexpected service failures return a generic message.

The `request` object uses the existing [notification model](data-model.md):
`title` and `notification_source` are required; `description`, `received_on`,
`metadata_map`, and `external_links` are optional. `id` and `unread` are response
fields and are not writable. For example, these are create tool arguments:

```json
{
  "request": {
    "title": "Build complete",
    "notification_source": "CI",
    "metadata_map": {"build_id": "build-42"},
    "external_links": {"Build": "https://ci.example.com/builds/42"}
  }
}
```

List uses zero-based pages, sizes 1–100, and newest-first ordering. Filtering
applies before pagination; traverse pages for complete results. Listing and
getting notifications do not mark them seen. Text/source search is not available.

Update replaces editable content, matching REST PUT. Fetch current values first
and preserve fields you want to retain. Omitted description and maps are cleared;
omitted `received_on` retains the stored timestamp. Updates preserve unread state.
Mark-seen is explicit and repeatable. Delete is permanent; subsequent operations
on that ID return an error. Create has no deduplication or idempotency key: inspect
state before retrying a create after a timeout or uncertain outcome.

## Verify

Run `./mvnw test`. `McpServerTests` uses the Java MCP client over real Streamable
HTTP with an isolated in-memory SQLite database. It covers discovery, schemas,
notification lifecycle, validation, pagination, and shared REST persistence.
`McpDisabledTests` verifies the feature switch.

For a manual smoke test, use an isolated database, start Windlass, and connect
your MCP client to `/mcp`. Check that it identifies `windlass` and discovers the
six tools. Create a disposable notification, list and get it, update it, mark it
seen, and delete it. Check that changes also appear in the UI or REST API. Restart
with `WINDLASS_MCP_ENABLED=false` and verify MCP is unavailable while REST works.

Implementation uses Spring AI 2.0.1's WebMVC server starter and `@McpTool`
annotations. See the [Spring AI MCP reference](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html).
