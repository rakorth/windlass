# Implemented features

## Feature inventory

| Feature | Behavior |
| --- | --- |
| Create notification | UI dialog or POST; generated UUID and unread state |
| List notifications | Cards in UI; paginated JSON object through API; newest received time first |
| Inspect notification | Click an inbox title or look up an ID on `/notification.html`; API GET by ID |
| Edit notification | UI dialog or PUT; preserves ID and unread state |
| Delete notification | UI confirmation or DELETE; permanent removal |
| Metadata | Editable JSON object with nested objects, arrays, and primitive values |
| External links | Editable label-to-URL map; clickable links to other services |
| Unread state | Persisted boolean; highlighted cards, unread label, and count |
| Mark as seen | Per-card button or PATCH; removes unread emphasis and button |
| Search | Local case-insensitive substring match on title, description, and source |
| Refresh | Initial load and manual Refresh fetch the collection |
| Persistence | PostgreSQL database with Flyway startup migrations |
| Agent integration | Reusable REST skill and endpoint documentation |
| Container builds | Multi-stage Docker image and GitHub Actions build/publish workflow |

## Browser UI

The interface uses a dark theme with mint accents, compact spacing, and a
horizontally scrollable notification table. Each row shows source, received time,
read state, title, a description preview of up to 160 characters, and actions.
Whitespace in previews is collapsed. **View details →** opens the full description,
external links, ID, and metadata on the single-notification page. Search still
matches the full description.

Click a notification title to open its detail page at `/notification.html?id=<id>`.
The **Find notification by ID** link opens an ID lookup form. The page shows one
notification’s title, ID, source, received time, read state, description, external
links, and metadata. Its URL can be bookmarked or shared. Refresh fetches that
notification again; viewing it does not mark it seen. **Mark as read** / **Mark as unread**
toggles the persisted read state and updates the button and status indicator. Missing IDs and failed
requests show an error.

The editor includes title, description, source, received time, metadata JSON, and
external-links JSON. The timestamp input uses local browser time and is converted
to an ISO timestamp for the API. Leaving it blank uses server time for creation
and preserves the stored timestamp for an edit.

External links open a new tab with `noopener noreferrer`. Only HTTP/HTTPS URLs
are rendered. Labels and notification content are inserted as text, not HTML.
Metadata and external links must be JSON objects; invalid input produces an error
in the dialog. Failed requests show an error without claiming success.

The notification count shows the total matching the source and unread filters.
Previous/Next buttons navigate pages of 20 items. The source dropdown lists
sources across all notifications and includes an **All sources** option.
Changing the source or unread-only filter resets to the first page. Search and the inbox header’s unread count cover only the current page. The browser tab shows the total unread count across all sources and pages and refreshes it every 30 seconds. Search does not include metadata, URLs, IDs, or dates.

Successful create, edit, delete, and mark-as-seen operations update the browser's
current page by fetching it again. Changes from other clients require Refresh or a page reload;
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
| Mark as unread | `true` |
| Restart the service | Stored value retained |

Seen state is shared by all clients. There are no user accounts or per-user read
receipts. The UI displays “Seen” after marking a notification as read. The details
page allows marking it unread again.

Read notifications are automatically deleted daily at 03:00 UTC when their
`received_on` timestamp is older than `NOTIFICATION_RETENTION_DAYS` days (default
`30`, positive integer). Age is measured from receipt, rather than when marked
read. Unread notifications are retained. Restart after changing the environment
variable supplied to Windlass.

## Not implemented

Do not assume support for automatic email/GitHub/webhook ingestion, outbound
notifications, authentication, authorization, user accounts,
server-side text/source search, bulk actions, attachments, scheduled delivery,
undo-delete, real-time updates, deduplication, idempotency keys,
optimistic locking, or an OpenAPI/Swagger endpoint. The source string and external
links describe other services; Windlass does not connect to them automatically.

## Notification dashboard

Open **Dashboard** from the inbox or visit `/dashboard.html` for unread counts
by notification source (type), retained notification totals, read percentage,
and the number of sources with unread notifications. A seven-day UTC activity
chart shows received notification counts, and the five most recent unread
notifications link directly to their details. Counts include all retained
records across every inbox page, including sources with no unread notifications.
Use Refresh to update; failures preserve the previous view with an error message.
Opening the dashboard does not change read state. Deleted records are excluded.

## Bookmarkable list filters

The notification inbox keeps applied filters and the current page
in the browser URL. Bookmark or copy the address to reopen the same view. Changing
filters or pages updates the URL; browser Back and Forward restore earlier views.
Default filters and page zero are omitted from the URL. Invalid page numbers and
invalid filters fall back to defaults. If a saved page no longer exists,
the list moves to the last available page and updates the address.

- Notifications: `/?source=CI&unread=true&search=build&page=1` restores source,
  unread-only, search text, and zero-based page. Search still filters only the
  current page in the browser and updates the URL as you type. Bookmarked source
  names remain selectable even when they no longer have notifications.

These are browser page URLs. REST endpoint parameters remain unchanged.
