# Run Windlass on another machine

This bundle runs Windlass using `ghcr.io/rakorth/windlass:latest` with persistent
storage. No local image builds are required.
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
docker compose up -d --wait
```

Open http://localhost:6080 for Windlass. If it is still starting,
wait a few seconds and refresh. Docker restarts the services after a machine
reboot once Docker itself starts.

MCP clients supporting Streamable HTTP can connect to
`http://localhost:6080/mcp` for notification list/get/create/update/mark-seen/delete
tools. They share the UI and REST API's notification database. Set
`WINDLASS_MCP_ENABLED=false` in `.env` and recreate Windlass to disable MCP.
The endpoint has no authentication; keep the default localhost bindings or
protect access with an authentication boundary. Updates replace editable content
and deletion is permanent.

Optional settings are in `.env.example`. Copy it to `.env` and edit the port
or image reference before starting. For example, set
`WINDLASS_PORT=6081` if port 6080 is already in use. A specific published tag or
digest in `WINDLASS_IMAGE` pins the application version.

If GHCR reports an access error, the package must be public or the recipient
must sign in with `docker login ghcr.io` using an account and token with read
access to that package. Do not include registry credentials in the bundle.

## Access a remote machine

The default ports bind to `127.0.0.1` on the Docker host. To use a remote Linux
machine, keep those defaults and forward the port from your own computer:

```sh
ssh -N -L 6080:127.0.0.1:6080 user@server
```

Then open the same localhost URL in your browser. Adjust both sides of the
forwarding rules if you change the configured port.

For a reverse proxy, configure `BIND_ADDRESS` as needed. Windlass has no login;
external access requires authentication in front of Windlass.

Docker volumes preserve Windlass data across container updates and
`docker compose down`. Preserve `.env` and the data volume separately. Stop
Windlass before making a file copy of its data volume so SQLite is consistent.

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

When replacing this bundle, retain your `.env` and Compose project name, then
repeat the start commands.

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
folder separately from the SQLite database. Standalone runs
use `REF_DOCS_DIRECTORY`, defaulting to `./ref-docs`.

## json-conf host storage

The JSON configuration editor is at `/json-conf.html`. Compose bind-mounts
`JSON_CONF_HOST_DIRECTORY` (default `./json-conf`) to `/json-conf`. Standalone
runs use `JSON_CONF_DIRECTORY`, defaulting to `./json-conf`. Prepare the host
folder and grant container UID 10001 read/write access before starting Compose
(for a new folder on Linux, `mkdir -p json-conf` then
`sudo chown 10001:10001 json-conf`). Back up this directory separately from
SQLite. Files must contain valid JSON when saved through Windlass.
