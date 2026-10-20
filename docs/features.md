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
| Persistence | SQLite file; startup upgrades for older schemas; Docker named volume |
| Agent integration | Reusable REST skill and endpoint documentation |
| Container builds | Multi-stage Docker image and GitHub Actions build/publish workflow |
| Automations | n8n runs alongside Windlass in Compose; header link opens its editor in a new tab |
| n8n backup/restore | Full ZIP downloads, validated uploads, automatic recovery copy, rollback on failed startup |

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

The notification count shows the total matching the source and unread filters.
Previous/Next buttons navigate pages of 20 items. The source dropdown lists
sources across all notifications and includes an **All sources** option.
Changing the source or unread-only filter resets to the first page. Search and the unread count cover only the current page. Search does not include metadata, URLs, IDs, or dates.

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
variable; Docker Compose forwards it to Windlass.

## Not implemented

Do not assume support for automatic email/GitHub/webhook ingestion, outbound
notifications, authentication, authorization, user accounts,
server-side text/source search, bulk actions, attachments, scheduled delivery,
undo-delete, real-time updates, deduplication, idempotency keys,
optimistic locking, or an OpenAPI/Swagger endpoint. The source string and external
links describe other services; Windlass does not connect to them automatically.

## Tasks and steps UI

The **Tasks** header link opens `/tasks.html`. The page lists tasks sorted by
name, with server-side search across task names, descriptions, and metadata,
and pages of 20 tasks. Tasks and templates appear in full-width list rows with
their descriptions, task status, step completion count, and ordered steps always
visible. Each step shows its Pending, Done, or Skipped status.
Step descriptions appear below their names, preserving line breaks. On smaller
screens, steps appear below the task summary. Refresh fetches current data.
Expand **View task details** to see the ID, links, and metadata; **Step details**
reveals any step links and metadata.

Click a task name to open its details page at `/task.html?id=<id>`. Share or
bookmark that URL to open the task directly. `/task.html` also lets you look up
a task by ID. The page shows the task status, description, reminder, ordered
steps and their statuses, external links, metadata, and webhooks. **Refresh**
reloads the task; missing or deleted tasks show an error with an option to retry.
**Edit** opens the task editor from this page so you can modify the task and its
steps. Saving refreshes the details; Cancel leaves the task unchanged.

Create and edit tasks in a dialog. Add, remove, and reorder steps, set their
statuses (Pending, Executing, Waiting, Done, or Skipped) and descriptions, and edit task and step metadata and links as JSON objects. Saving
replaces the complete task, including its steps. Links require full HTTP/HTTPS
URLs and open in a new tab. Delete requires confirmation and removes the task
and its steps. Failed requests and invalid input show errors. Changes from other
clients require Refresh; concurrent edits are not merged.

## Task templates

In `/tasks.html`, select **Task templates**, then **+ New template**. Templates
have the same fields and ordered steps as tasks, with the same validation.
They are saved in a separate library and do not appear in the task list.
Use **Edit** or **Delete** to maintain a template, or **Save as template** on an
existing task to open a copy in the template editor.

Select **Use template** to open a new task with all the template's fields filled
in. Adjust the name, status, reminder date, or other fields, then **Save task**.
Cancel creates nothing. Each saved copy has a new ID; editing or deleting a
copy or its template does not change the other. Statuses and reminder timestamps
are copied exactly, so review them when reusing a template.

See [Task templates](task-templates.md) for the complete UI and REST workflow.

Task and step editors include webhooks (one HTTP(S) URL per line).
Creating a task, including from a template, calls every task and step destination.
Saving a changed status also sends a JSON POST to each affected destination; delivery failures
appear as unread notifications with the task, step, destination, and error details.
Templates preserve webhook settings when copied into tasks. See the
[webhook contract](api.md#task-status-webhooks) for matching rules and delivery limits.

On the task details page, each step has a status dropdown that saves immediately.
The completion count updates after saving. Failed saves restore the previous
selection and show an error; status changes use the focused step PATCH endpoint.

## Notification dashboard

Open **Dashboard** from the inbox or visit `/dashboard.html` for unread counts
by notification source (type), retained notification totals, read percentage,
and the number of sources with unread notifications. A seven-day UTC activity
chart shows received notification counts, and the five most recent unread
notifications link directly to their details. Counts include all retained
records across every inbox page, including sources with no unread notifications.
Use Refresh to update; failures preserve the previous view with an error message.
Opening the dashboard does not change read state. Deleted records are excluded.

## Excluding task statuses

The Tasks and Task templates lists offer **Exclude statuses** checkboxes. Select
one or more statuses to hide; clear the checkboxes to include them again. Changes
apply immediately and reset pagination to the first page. Search and the existing
status selector combine with exclusions; including and excluding the same status
produces no results. Filters remain selected when switching between the two lists.

Both `GET /api/tasks` and `GET /api/task-templates` accept repeated `excludeStatus`
parameters, for example `?excludeStatus=DONE&excludeStatus=SKIPPED`. Values must be
`PENDING`, `EXECUTING`, `WAITING`, `DONE`, or `SKIPPED`; invalid values return 400.
Omit the parameter to exclude nothing. Exclusions apply before pagination and
counts and combine with `search`, `status`, and task reminder bounds.

## Bookmarkable list filters

The notification inbox and Tasks page keep applied filters and the current page
in the browser URL. Bookmark or copy the address to reopen the same view. Changing
filters or pages updates the URL; browser Back and Forward restore earlier views.
Default filters and page zero are omitted from the URL. Invalid page numbers and
unknown task statuses fall back to defaults. If a saved page no longer exists,
the list moves to the last available page and updates the address.

- Notifications: `/?source=CI&unread=true&search=build&page=1` restores source,
  unread-only, search text, and zero-based page. Search still filters only the
  current page in the browser and updates the URL as you type. Bookmarked source
  names remain selectable even when they no longer have notifications.
- Tasks: `/tasks.html?search=release&status=WAITING&excludeStatus=DONE&excludeStatus=SKIPPED`
  restores the applied search, status, and exclusions. Apply search with Search
  or Enter; status and exclusion changes apply immediately.
- Templates: add `library=templates` to a Tasks page URL to restore the template
  library with the same filter options. Page position uses zero-based `page`.

These are browser page URLs. REST endpoint parameters remain unchanged.
