# Running, persistence, and image builds

Commands assume the repository root. The UI and REST API share one port.

## Local Java process

Use JDK 24 or newer with the bundled Maven wrapper:

```sh
./mvnw spring-boot:run
```

Open http://localhost:8080. To build a runnable JAR:

```sh
./mvnw package
java -jar target/windlass-0.0.1-SNAPSHOT.jar
```

Local runs store data in `notifications.db` in the process working directory.
No separate database server or frontend package manager is needed.

## Configuration

| Setting | Default | Scope |
| --- | --- | --- |
| `DATABASE_URL` | `jdbc:sqlite:./notifications.db` | Application datasource; Docker overrides to `jdbc:sqlite:/data/notifications.db` |
| `SERVER_PORT` | `8080` | Spring Boot HTTP listener; no explicit port currently in application YAML |
| `WINDLASS_MCP_ENABLED` | `true` | Enable notification MCP tools at `/mcp`; see [MCP server](mcp.md) |
| `WINDLASS_PORT` | `6080` | Compose host-side port only; does not change the container's listener |
| `N8N_PORT` | `5678` | Compose host-side n8n port; the n8n container always listens on 5678 |
| `N8N_EDITOR_BASE_URL` | `http://localhost:5678/` | Browser URL for the Automations link and n8n editor; Compose derives the default from `N8N_PORT` |
| `N8N_WEBHOOK_URL` | `http://localhost:5678/` | Compose URL advertised for n8n webhooks; Compose derives the default from `N8N_PORT` |
| `N8N_VERSION` | `2.41.3` | Pinned n8n image version in Compose |
| `N8N_IMAGE_REPOSITORY` | `ghcr.io/n8n-io/n8n` | n8n base image repository, without a tag; the version still comes from `N8N_VERSION` |
| `N8N_TIMEZONE` | `Etc/UTC` | Compose n8n system timezone and schedule timezone |
| `N8N_SECURE_COOKIE` | `false` | Compose permits n8n cookies over local HTTP; set `true` when using HTTPS |
| `N8N_BACKUP_URL` | Empty outside Compose | Private backup-manager URL; Compose sets `http://n8n-backup:5680` |
| `N8N_BACKUP_TOKEN_FILE` | `/n8n-control/token` | Private manager token file; Compose supplies it via a read-only volume |
| `WINDLASS_BASE_URL` | Agent examples default to `http://localhost:8080` | Client-side skill convention; not read by the server |

Configuration lives in [application.yaml](../src/main/resources/application.yaml).
If changing the container's listener with `SERVER_PORT`, also change its port
mapping; setting `WINDLASS_PORT` alone intentionally keeps the internal port 8080.
There is no built-in authentication. The default Compose port is bound to host
loopback (`127.0.0.1`).

## Portable deployment with the published image

[compose.deploy.yaml](../compose.deploy.yaml) is a standalone deployment file
using `ghcr.io/rakorth/windlass:latest` (Linux AMD64), with the same n8n supervisor,
backup manager, and persistent volumes as the development stack. It does not
build Windlass. Run it by itself:

```sh
docker compose -f compose.deploy.yaml pull windlass
docker compose -f compose.deploy.yaml up -d --build --wait
```

To hand the setup to someone without giving them a source checkout:

```sh
sh scripts/package-deployment.sh
```

This creates `target/windlass-deployment.tar.gz` containing a `windlass-deployment`
folder with the deployment file renamed to `compose.yaml`, optional `.env.example`,
operator instructions, and only the files needed to build the n8n helpers.
Local `.env`, databases, credentials, Git metadata, and application source are
excluded. The recipient needs Docker with Compose v2 and internet access.
See the [bundled guide](../deploy/README.md) for the startup commands.

`WINDLASS_IMAGE` overrides the full published image reference; use a published
tag or digest to pin a version. `BIND_ADDRESS` defaults to `127.0.0.1` for both
browser ports. The [example environment](../deploy/.env.example) documents the
remaining overrides. Windlass has no authentication, including for its backup
endpoints; use the documented SSH tunnel for remote access or put authentication
in front of an externally accessible deployment.

The deployment uses project name `windlass-deployment`, independent of the
folder name. Keep that name (or an explicit `COMPOSE_PROJECT_NAME` / `-p` override)
stable to reuse its volumes. These volumes are separate from the development
stack's data; no existing database is copied automatically.

## Docker Compose

```sh
docker compose up -d --build
docker compose logs -f windlass
```

[compose.yaml](../compose.yaml) builds the root Dockerfile for `windlass` and
builds a thin supervisor image on top of pinned n8n, and builds the private
`n8n-backup` manager. Windlass maps host `127.0.0.1:6080` to
container `8080`; n8n maps host `127.0.0.1:5678` to container `5678`. Both use
restart policy `unless-stopped`. All three services share the default Compose
network; the manager has no published ports. Windlass and n8n start after the
manager's HTTP listener is healthy. To avoid an occupied host port:

```sh
WINDLASS_PORT=6081 docker compose up -d --build
```

Then open http://localhost:6081. Keep the same port setting for later Compose
operations that recreate the service.

### n8n image download errors

The n8n build uses the [official n8n GitHub container registry](https://github.com/n8n-io/n8n/pkgs/container/n8n).
This avoids the `429 Too Many Requests` error encountered when fetching image
metadata from `docker.n8n.io`. The n8n version remains pinned to `N8N_VERSION`.
After updating the configuration, retry your original Compose command, or build
just the n8n image with:

```sh
docker compose build n8n
```

To use a different repository, set `N8N_IMAGE_REPOSITORY` in `.env`, for example
`N8N_IMAGE_REPOSITORY=docker.io/n8nio/n8n` or
`N8N_IMAGE_REPOSITORY=docker.n8n.io/n8nio/n8n`. Use a repository that publishes the
same n8n release. If that registry also rate-limits requests, wait for its limit
to reset or authenticate with `docker login <registry>` before retrying.

## n8n automations

Open http://localhost:5678, or click **Automations ↗** in the Windlass header.
The link opens a new tab through `/automations`, which redirects to
`N8N_EDITOR_BASE_URL`. On the first visit, n8n asks you to create its owner
account. This account belongs to n8n; Windlass itself has no login.

To create a notification from a workflow:

1. Create a workflow in n8n and add a **Manual Trigger**.
2. Connect an **HTTP Request** node with method **POST**, URL
   `http://windlass:8080/api/notifications`, and authentication **None**.
3. Enable **Send Body**, choose **JSON**, and use this JSON body:

   ```json
   {
     "title": "Hello from n8n",
     "description": "Created by my first automation",
     "notification_source": "n8n",
     "metadata_map": {"workflow": "First automation"}
   }
   ```

4. Execute the workflow, then click **Refresh** in Windlass. The new notification
   appears unread. Replace the manual trigger with your chosen integration or
   schedule when ready.

Inside n8n, `localhost` refers to the n8n container. Always use
`http://windlass:8080` for the Windlass API in this Compose setup, even if you
change `WINDLASS_PORT`. Requests to n8n webhooks from another Compose service use
`http://n8n:5678/webhook/<path>`. Browser links use the host-facing URL instead.
Windlass does not automatically call n8n webhooks when notifications change.

To change the editor's host port, add `N8N_PORT=5679` to the existing `.env` file
or prefix the Compose command:

```sh
N8N_PORT=5679 docker compose up -d --build
```

This also updates the Automations link and n8n's advertised webhook URL. Keep
the same setting for later Compose commands. An explicit `N8N_EDITOR_BASE_URL`
or `N8N_WEBHOOK_URL` takes precedence over the generated URL. For an existing
remote n8n instance, set `N8N_EDITOR_BASE_URL` to its browser URL. The built-in
backup feature still applies only to n8n's local Compose data, not that remote
instance. Use a standalone Java run for a remote editor without the local stack.

For Maven or standalone JAR runs, set `N8N_EDITOR_BASE_URL` explicitly if the
editor is not at `http://localhost:5678/`; `N8N_PORT` only configures Compose.
The `windlass` hostname is available to n8n only when Windlass runs on the same
Compose network.

The local setup supports workflows making outbound requests to other services.
Receiving webhooks from external services requires a reachable HTTPS endpoint
or tunnel and the corresponding `N8N_WEBHOOK_URL`. When configuring HTTPS for
the editor, also set `N8N_EDITOR_BASE_URL` and `N8N_SECURE_COOKIE=true`.

n8n persists its SQLite database and encryption key under `/home/node/.n8n` in
the `n8n-data` volume. Preserve and back up the whole volume: the encryption key
is needed to read stored credentials. `docker compose down` retains all data
volumes; `docker compose down --volumes` deletes them. Stop n8n before copying
its SQLite files for a file-based backup. Use the same Compose project name to
reuse its storage.

Use **n8n backups** in Windlass to download or restore a full snapshot. The
manager stores its latest automatic recovery copy in `n8n-backups`; its private
control token and maintenance marker live in `n8n-control`. See
[backup and restore](n8n-backups.md) for usage and recovery details.

To upgrade n8n, back up its volume, choose a version with `N8N_VERSION` in `.env`,
then run:

```sh
docker compose build --pull n8n n8n-backup
docker compose up -d n8n n8n-backup
```

The editor readiness endpoint is checked by Docker; inspect it with
`docker compose ps` and `docker compose logs n8n`. For hosting guidance, see the
[official n8n Docker documentation](https://docs.n8n.io/deploy/host-n8n/install-options/install-with-docker).

## SQLite persistence

Compose mounts the named volume `windlass-data` at `/data`. Docker Compose
normally prefixes the actual volume name with its project name; use
`docker compose config` and Docker inspection to identify it rather than
assuming a global name. Keep the same Compose project name to reuse that volume.

The volume contains `/data/notifications.db` and any SQLite journal files.
Restarts, rebuilds, container recreation, and `docker compose down` preserve it.
`docker compose down --volumes` deletes it. The application runs as UID/GID
`10001`; a replacement host bind mount must be writable by that UID.

Compose starts with a separate database and does not import a local project
`notifications.db`. Merely changing `DATABASE_URL` also does not copy data.
For file-based backup or migration, stop the writer before copying SQLite data,
or use a SQLite-aware backup tool; copying a live database file alone may omit
uncommitted journal/WAL data. Store backups outside the volume being replaced.

## Application image

```sh
docker build -t windlass:local .
docker run --name windlass-app --rm \
  -p 127.0.0.1:6080:8080 -v windlass-data:/data windlass:local
```

The root [Dockerfile](../Dockerfile) uses Java 25 JDK for Maven `verify`, compiling
with the project's Java 24 target. The runtime stage contains a Java 25 JRE and
the executable JAR, runs as non-root UID/GID `10001`, exposes port 8080, and uses
`/data` for SQLite. Integration tests must pass when the build stage runs.
BuildKit caches Maven downloads. [.dockerignore](../.dockerignore) limits the
context to build inputs, excluding local databases, environment files, and Git
metadata.

The standalone `docker run` example uses a globally named `windlass-data` volume;
it does not automatically share Compose's project-prefixed volume.

## GitHub Actions

[The Docker workflow](../.github/workflows/docker.yml) defines these behaviors:

| Event/ref | Build image and test build stage | Publish to GHCR |
| --- | --- | --- |
| Pull request | Yes | No |
| Push to non-default branch | Yes | No |
| Push to default branch | Yes | Yes |
| Push of a `v*` tag | Yes | Yes |
| Manual run | Yes | Only for default branch or a `v*` tag |

The registry destination is `ghcr.io/<owner>/<repository>`, normalized to
lowercase. Published images include a `sha-<short-commit>` tag. Default-branch
builds also publish the branch tag and `latest`; release builds publish the full
Git tag, such as `v1.0.0`. The workflow builds `linux/amd64`, uses the GitHub
Actions layer cache, and cancels superseded runs for the same ref.

Publishing uses the built-in `GITHUB_TOKEN` with `packages: write`. Repository
and organization settings must permit package publication. No Docker Hub secret
is configured. Package visibility/access depends on GitHub settings. Committing
the workflow does not itself certify that a remote build or publication ran.

## Development container

[.devcontainer/devcontainer.json](../.devcontainer/devcontainer.json) publishes
Windlass and n8n to host `127.0.0.1:18080` and `127.0.0.1:15678` and lists both in
`forwardPorts` for IDE forwarding. Docker mapping changes require container
recreation; IDE forwarding can be managed separately for an existing container.

Start the stack inside the dev container with:

```sh
docker compose -f compose.yaml -f .devcontainer/compose.ports.yaml up -d --build --wait
```

Open http://localhost:18080 for Windlass and http://localhost:15678 for its n8n
instance. These separate development ports avoid existing host services on
8080 and 5678. For an already-running dev container, forward container ports
18080 and 15678 to the same local ports in the IDE.
If IDE forwarding is unavailable, the [host forwarding setup](../.devcontainer/README.md#forward-an-existing-container-without-rebuilding-it)
connects the existing dev container without rebuilding it.

The development override makes the nested services reachable through the outer
container's published ports. It requires Docker Compose 2.24.4+ and should only
be used inside the dev container. Update the outer mappings and IDE forwarding
if you override `WINDLASS_PORT` or `N8N_PORT`.

The [.devcontainer/Dockerfile](../.devcontainer/Dockerfile) is a development
workspace image, separate from the root application image. It includes
Docker-in-Docker tooling; ports published by nested containers are relative to
that Docker host. Verify the forwarding path when accessing a nested Compose
service from the physical host. See [dev container details](../.devcontainer/README.md)
for tooling and Java setup.

## ref-docs host storage

The ref-docs UI is at `/ref-docs.html`. Compose bind-mounts
`REF_DOCS_HOST_DIRECTORY` (default `./ref-docs`) to `/ref-docs`, keeping files
on the host outside the container and SQLite. Set an absolute host directory
in `.env` to use an existing folder. Prepare it before starting Compose and
grant the container UID 10001 read/write access (for a new folder on Linux,
`mkdir -p ref-docs` then `sudo chown 10001:10001 ref-docs`). Back up this
folder separately; n8n backups do not include Markdown files. Standalone runs
use `REF_DOCS_DIRECTORY`, defaulting to `./ref-docs`.

## json-conf host storage

The JSON configuration editor is at `/json-conf.html`. Compose bind-mounts
`JSON_CONF_HOST_DIRECTORY` (default `./json-conf`) to `/json-conf`. Standalone
runs use `JSON_CONF_DIRECTORY`, defaulting to `./json-conf`. Prepare the host
folder and grant container UID 10001 read/write access before starting Compose
(for a new folder on Linux, `mkdir -p json-conf` then
`sudo chown 10001:10001 json-conf`). Back up this directory separately from
SQLite and n8n. Files must contain valid JSON when saved through Windlass.
