# Windlass n8n backup API

Use the Windlass base URL selected in SKILL.md. This feature manages the local SQLite-backed n8n in the backup-enabled Compose setup. It does not back up Windlass notifications, external n8n instances, or PostgreSQL deployments.

## Routes and request formats

| Method | Path | Request | Success |
| --- | --- | --- | --- |
| GET | `/api/automations/backups/status` | No body or special header | 200; status JSON |
| POST | `/api/automations/backups` | No body; `X-Windlass-Backup: 1` | 200; backup ZIP |
| GET | `/api/automations/backups/recovery` | No body; `X-Windlass-Backup: 1` | 200; latest recovery ZIP |
| POST | `/api/automations/backups/restore` | Multipart `file` and `confirm=RESTORE`; `X-Windlass-Backup: 1` | 200; result JSON |

The special header is required even for the recovery GET. If `Sec-Fetch-Site` is present, it must be `same-origin` or `none`; other values return 403. Ordinary curl requests can omit it. Do not use the backup manager's internal routes or read its bearer token: Windlass forwards requests using its configured token.

## Inspect status first

```sh
curl --fail-with-body --silent --show-error --max-time 15 \
  "$WINDLASS_BASE_URL/api/automations/backups/status"
```

When configured, status includes `available`, `phase`, `error`, `n8nVersion`, `n8nReady`, `maxUploadBytes`, and `recoveryAvailable`. Outside the configured setup, it can return only `available: false` and `message`; do not assume other fields exist.

Start a backup or restore when `available` and `n8nReady` are true and `phase` is `ready`. A recovery download can be requested when `recoveryAvailable` is true and phase is `ready` or `recovery-required`. Other phases indicate work in progress. Only one backup, restore, or recovery download can run at once.

## Download a backup or recovery copy

A requested backup stops n8n, creates and validates a ZIP, restarts n8n, and waits for readiness before returning the download. Allow minutes for the operation; the notification examples' 15-second timeout is unsuitable. Use a bounded timeout appropriate to the deployment (Windlass's upstream timeout is 600 seconds).

Choose a private output path and avoid overwriting an existing backup. For example, with a new `backup_path`:

```sh
curl --fail --silent --show-error --max-time 660 \
  -X POST -H 'X-Windlass-Backup: 1' \
  --output "$backup_path" \
  "$WINDLASS_BASE_URL/api/automations/backups"
```

For recovery, use the same download options with GET and `/api/automations/backups/recovery`. The recovery ZIP is the snapshot from before the last restore attempt, not a fresh backup. Only the latest is retained; save it before another restore if needed.

Downloads are binary `application/zip`, with `Content-Disposition` and `Cache-Control: no-store`; do not parse them as JSON. Check successful HTTP completion and ZIP integrity before reporting a saved backup. A failed or interrupted download may leave a partial file; do not report it as usable. The ZIP contains encrypted credentials and their encryption key, so store it securely and never commit it to a repository or print its contents.

## Restore

Restore only when the user requested replacement of the current n8n data using the selected archive. Explain replacement and the temporary n8n interruption if the user's intent is ambiguous; the literal API field `confirm=RESTORE` does not itself supply user authorization.

Use a nonempty Windlass-generated ZIP no larger than 512 MiB (also inspect `maxUploadBytes`). Workflow JSON exports and older manual `.tar.gz` archives are unsupported. The backup's n8n version must exactly match the running version. Expanded data is limited to 2 GiB and 20,000 data entries.

With `backup_path` pointing to the requested archive:

```sh
curl --fail-with-body --silent --show-error --max-time 660 \
  -H 'X-Windlass-Backup: 1' \
  --form "file=@${backup_path}" --form 'confirm=RESTORE' \
  "$WINDLASS_BASE_URL/api/automations/backups/restore"
```

Let curl set multipart Content-Type and boundary; do not send `application/json`. Quote paths and choose a multipart-capable client if a filename contains curl form syntax characters.

The server validates the archive before stopping n8n and creates a recovery ZIP before replacing data. The current instance needs an initialized, valid database for this recovery snapshot. Restore includes workflows, credentials, accounts, settings, history, and stored files; restored active workflows may run immediately and account changes since the backup are reverted. Compose configuration, environment overrides, external services, and external mounts are excluded.

On installation/startup failure the manager attempts rollback. Inspect the returned result and status rather than assuming rollback succeeded. Report the confirmed result message and recovery availability.

## Failures and uncertain outcomes

| Status | Meaning/action |
| --- | --- |
| 400 | Invalid confirmation or archive; correct the request |
| 403 | Missing backup header or rejected browser request origin; check request and proxy context |
| 404 | No recovery ZIP exists |
| 409 | Competing operation or n8n version mismatch; inspect status/error before retrying |
| 413 | Empty or oversized upload, or archive size limits exceeded |
| 503 | Feature/service unavailable or recovery failed; inspect status |

After a timeout, connection failure, or 5xx, query status before any retry: work can continue after the client disconnects. Do not automatically resubmit a restore. If phase is `recovery-required`, report the error and preserve recovery artifacts and maintenance markers; do not remove them to bypass recovery. If the outcome remains uncertain, stop and report that uncertainty.
