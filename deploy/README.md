# Run Windlass on another machine

This bundle runs Windlass, the n8n workflow editor, and n8n backup/restore.
Windlass uses `ghcr.io/rakorth/windlass:latest`. The two small n8n helper images
are built from the included `docker/` directory; keep it beside `compose.yaml`.
No Java, Maven, Git checkout, or application source is needed.

## Start

Install Docker Engine with Docker Compose v2, or Docker Desktop configured for
Linux containers. The published Windlass image is Linux AMD64; use an x86-64
machine, or a Docker installation with AMD64 emulation. The first start needs
internet access to download images.

Extract `windlass-deployment.tar.gz`, open a terminal in its
`windlass-deployment` directory, and run:

```sh
docker compose pull windlass
docker compose up -d --build --wait
```

Open http://localhost:6080 for Windlass and http://localhost:5678 for n8n.
Create the n8n owner account on the first visit. If Windlass is still starting,
wait a few seconds and refresh. Docker restarts the services after a machine
reboot once Docker itself starts.

MCP clients supporting Streamable HTTP can connect to
`http://localhost:6080/mcp` for notification list/get/create/update/mark-seen/delete
tools. They share the UI and REST API's notification database. Set
`WINDLASS_MCP_ENABLED=false` in `.env` and recreate Windlass to disable MCP.
The endpoint has no authentication; keep the default localhost bindings or
protect access with an authentication boundary. Updates replace editable content
and deletion is permanent. n8n backup operations are not exposed through MCP.

Optional settings are in `.env.example`. Copy it to `.env` and edit the ports,
timezone, or image reference before starting. For example, set
`WINDLASS_PORT=6081` if port 6080 is already in use. A specific published tag or
digest in `WINDLASS_IMAGE` pins the application version.

If GHCR reports an access error, the package must be public or the recipient
must sign in with `docker login ghcr.io` using an account and token with read
access to that package. Do not include registry credentials in the bundle.

## Access a remote machine

The default ports bind to `127.0.0.1` on the Docker host. To use a remote Linux
machine, keep those defaults and forward both ports from your own computer:

```sh
ssh -N -L 6080:127.0.0.1:6080 -L 5678:127.0.0.1:5678 user@server
```

Then open the same localhost URLs in your browser. Adjust both sides of the
forwarding rules if you change the configured ports.

For a reverse proxy, configure `BIND_ADDRESS` as needed, set the browser-facing
`N8N_EDITOR_BASE_URL` and `N8N_WEBHOOK_URL`, and set `N8N_SECURE_COOKIE=true` for
an HTTPS editor. Windlass has no login and its backup feature can download n8n
credentials, so external access requires authentication in front of Windlass.
n8n's own login does not protect Windlass.

## Automations and backups

In an n8n HTTP Request node, use `http://windlass:8080/api/notifications` to
create notifications. This internal URL is independent of the published ports.
Send a POST with a JSON body such as:

```json
{"title":"Hello from n8n","notification_source":"n8n"}
```

Use **n8n backups** in Windlass to download or restore a full n8n backup. n8n
pauses briefly during the operation. Store downloaded backups securely on
another machine: they include credentials and the key needed to decrypt them.
Restoring requires the same `N8N_VERSION` used to create the backup.
See [n8n-backups.md](n8n-backups.md) for limits and recovery instructions.

Docker volumes preserve notifications, n8n workflows and credentials, and the
latest automatic recovery backup across container updates and
`docker compose down`. The n8n backup ZIP does not include Windlass's notification
database or `.env`; preserve those separately. Stop Windlass before making a
file copy of its data volume so SQLite is consistent.

The default Compose project name is `windlass-deployment`. Keep the same name
when updating to reuse its volumes. This starts with fresh data, separate from
a development stack. **`docker compose down --volumes` deletes the deployment's
stored data.**

## Update and manage

Download the configured Windlass image and recreate its container:

```sh
docker compose pull windlass
docker compose up -d --no-build --wait
```

To upgrade n8n, first download a backup, change `N8N_VERSION` in `.env`, then run:

```sh
docker compose build --pull n8n n8n-backup
docker compose up -d --wait
```

When replacing this bundle, retain your `.env` and Compose project name, then
repeat the start commands to rebuild any changed helpers.

```sh
docker compose ps
docker compose logs --tail=100 -f
docker compose down
```

## ref-docs host storage

The ref-docs UI is at `/ref-docs.html`. Compose bind-mounts
`REF_DOCS_HOST_DIRECTORY` (default `./ref-docs`) to `/ref-docs`, keeping files
on the host outside the container and SQLite. Set an absolute host directory
in `.env` to use an existing folder. Prepare it before starting Compose and
grant the container UID 10001 read/write access (for a new folder on Linux,
`mkdir -p ref-docs` then `sudo chown 10001:10001 ref-docs`). Back up this
folder separately; n8n backups do not include Markdown files. Standalone runs
use `REF_DOCS_DIRECTORY`, defaulting to `./ref-docs`.
