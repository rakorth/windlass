# Implemented features

## Feature inventory

| Feature | Behavior |
| --- | --- |
| Create notification | UI dialog or POST; generated UUID and unread state |
| List notifications | Cards in UI; full JSON array through API; newest received time first |
| Inspect notification | Card content, expandable ID/metadata, or GET by ID |
| Edit notification | UI dialog or PUT; preserves ID and unread state |
| Delete notification | UI confirmation or DELETE; permanent removal |
| Metadata | Editable JSON object with nested objects, arrays, and primitive values |
| External links | Editable label-to-URL map; clickable links to other services |
| Unread state | Persisted boolean; highlighted cards, unread label, and count |
| Mark as seen | Per-card button or PATCH; removes unread emphasis and button |
| Search | Local case-insensitive substring match on title, description, and source |
| Refresh | Initial load and manual Refresh fetch the collection |
| Persistence | SQLite file; startup upgrades for older schemas; Docker named volume |
| Agent integration | Reusable REST skill and endpoint documentation |
| Container builds | Multi-stage Docker image and GitHub Actions build/publish workflow |
| Automations | n8n runs alongside Windlass in Compose; header link opens its editor in a new tab |
| n8n backup/restore | Full ZIP downloads, validated uploads, automatic recovery copy, rollback on failed startup |

## Browser UI

The interface uses a dark theme with mint accents, compact spacing, and full-width
content. The card grid adapts its column count to the browser width and becomes
one column on small screens. Cards show source, received time, read state, title,
description, links, metadata details, and actions.

The **Automations ↗** header link opens the n8n workflow editor in a new tab.
The editor address is configurable with `N8N_EDITOR_BASE_URL`. Compose runs n8n
with its own persistent storage. Workflows can create and manage notifications
through the existing REST API; integrations are configured in n8n. See
[n8n setup](deployment.md#n8n-automations).

The **n8n backups** header link opens `/backups.html`. Users can download a full
n8n backup, upload a matching-version backup after confirming replacement, and
download the recovery copy retained before the last restore attempt. Status and
errors are shown while operations run. The feature requires the Compose backup
manager; see [backup and restore](n8n-backups.md).

The editor includes title, description, source, received time, metadata JSON, and
external-links JSON. The timestamp input uses local browser time and is converted
to an ISO timestamp for the API. Leaving it blank uses server time for creation
and preserves the stored timestamp for an edit.

External links open a new tab with `noopener noreferrer`. Only HTTP/HTTPS URLs
are rendered. Labels and notification content are inserted as text, not HTML.
Metadata and external links must be JSON objects; invalid input produces an error
in the dialog. Failed requests show an error without claiming success.

The notification count next to “All notifications” counts the current search
results. The unread count covers all loaded notifications, even those excluded
by the current search. Search does not include metadata, URLs, IDs, or dates.

Successful create, edit, delete, and mark-as-seen operations update the browser's
local collection. Changes from other clients require Refresh or a page reload;
there is no polling, WebSocket, or server-sent event connection.

## Unread lifecycle

| Action | Effect on `unread` |
| --- | --- |
| Create notification | `true` |
| Upgrade a record from a schema without unread state | `true` |
| List, view, expand metadata, or open a link | No change |
| Edit content | No change |
| Mark as seen | `false` |
| Mark an already-seen notification as seen | Remains `false` |
| Restart the service | Stored value retained |

Seen state is shared by all clients. There are no user accounts or per-user read
receipts. The UI displays “Seen” after marking a notification, with no reverse
button. The API also has no mark-unread operation.

## Not implemented

Do not assume support for automatic email/GitHub/webhook ingestion, outbound
notifications, authentication, authorization, user accounts, pagination,
server-side search/filtering, bulk actions, attachments, scheduled delivery,
mark-unread, undo-delete, real-time updates, deduplication, idempotency keys,
optimistic locking, or an OpenAPI/Swagger endpoint. The source string and external
links describe other services; Windlass does not connect to them automatically.
