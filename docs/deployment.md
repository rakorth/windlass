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
Flyway migrates the configured SQLite database before the application starts serving
requests. Existing pre-Flyway databases are adopted automatically without deleting
records. See [Database migrations](migrations.md) for upgrade behavior and recovery.

## Configuration

For a demo configuration in this checkout, copy the root
[.env.example](../.env.example) to `.env` with `cp .env.example .env`.
Compose loads it automatically. It includes the browser port and
host folders for ref-docs and json-conf. Prepare the host folders and grant
container UID 10001 read/write access as described in the example before
starting Compose. Standalone Maven/Java runs require exporting environment
variables; they do not automatically load `.env`.

| Setting | Default | Scope |
| --- | --- | --- |
| `NOTIFICATION_RETENTION_DAYS` | `30` | Positive integer; read notifications older than this many days by `received_on` are deleted daily at 03:00 UTC; unread notifications are retained |
| `DATABASE_URL` | `jdbc:sqlite:./notifications.db` | Application datasource; Docker overrides to `jdbc:sqlite:/data/notifications.db` |
| `SERVER_PORT` | `8080` | Spring Boot HTTP listener; no explicit port currently in application YAML |
| `WINDLASS_MCP_ENABLED` | `true` | Enable notification MCP tools at `/mcp`; see [MCP server](mcp.md) |
| `WINDLASS_PORT` | `6080` | Compose host-side port only; does not change the container's listener |
| `WINDLASS_BASE_URL` | Agent examples default to `http://localhost:8080` | Client-side skill convention; not read by the server |

Configuration lives in [application.yaml](../src/main/resources/application.yaml).
If changing the container's listener with `SERVER_PORT`, also change its port
mapping; setting `WINDLASS_PORT` alone intentionally keeps the internal port 8080.
There is no built-in authentication. The default Compose port is bound to host
loopback (`127.0.0.1`).

## Portable deployment with the published image

[compose.deploy.yaml](../compose.deploy.yaml) is a standalone deployment file
using `ghcr.io/rakorth/windlass:latest` (Linux AMD64 and ARM64), with the same
Windlass storage configuration as the development stack. It does not
build Windlass. Run it by itself:

```sh
docker compose -f compose.deploy.yaml pull windlass
docker compose -f compose.deploy.yaml up -d --wait
```

To hand the setup to someone without giving them a source checkout:

```sh
sh scripts/package-deployment.sh
```

This creates `target/windlass-deployment.tar.gz` containing a `windlass-deployment`
folder with the deployment file renamed to `compose.yaml`, optional `.env.example`,
and operator instructions. No helper images need to be built.
Local `.env`, databases, credentials, Git metadata, and application source are
excluded. The recipient needs Docker with Compose v2 and internet access.
See the [bundled guide](../deploy/README.md) for the startup commands.

`WINDLASS_IMAGE` overrides the full published image reference; use a published
tag or digest to pin a version. `BIND_ADDRESS` defaults to `127.0.0.1` for the
browser port. The [example environment](../deploy/.env.example) documents the
remaining overrides. Windlass has no authentication; use the documented SSH tunnel for remote access or put authentication
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

[compose.yaml](../compose.yaml) builds the root Dockerfile for `windlass`.
It maps host `127.0.0.1:6080` to container `8080` and uses restart policy
`unless-stopped`. To avoid an occupied host port:

```sh
WINDLASS_PORT=6081 docker compose up -d --build
```

Then open http://localhost:6081. Keep the same port setting for later Compose
operations that recreate the service.

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
`/data` for SQLite. Image defaults also store ref-docs in `/data/ref-docs` and
json-conf in `/data/json-conf`, so standalone containers can create the folders
as UID 10001 and retain files in the data volume. Compose overrides these paths
with its separate host bind mounts. Integration tests must pass when the build
stage runs.
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
Git tag, such as `v1.0.0`. The workflow builds and tests `linux/amd64` and `linux/arm64` in parallel on
native runners, then combines their digests into the same multi-platform tags.
Each architecture uses its own GitHub Actions layer cache. Superseded runs for
the same ref are canceled.

Publishing uses the built-in `GITHUB_TOKEN` with `packages: write`. Repository
and organization settings must permit package publication. No Docker Hub secret
is configured. Package visibility/access depends on GitHub settings. Committing
the workflow does not itself certify that a remote build or publication ran.

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

## Document folder access errors

`AccessDeniedException: /app/json-conf` means the application is resolving the
standalone relative default under its container working directory. The current
Compose configuration explicitly sets `JSON_CONF_DIRECTORY=/json-conf` and
`REF_DOCS_DIRECTORY=/ref-docs`. Check the effective configuration with
`docker compose config`, including any override files you use, then recreate
the Windlass container with `docker compose up -d --build windlass` for the
source-build stack. For the published-image deployment, use
`docker compose -f compose.deploy.yaml up -d --force-recreate windlass`.

If the error names `/json-conf` or `/ref-docs`, check the corresponding host
folder permissions. They must allow UID 10001 to create, read, update, and delete
files. See the storage sections above. Changing `.env` alone does not update
the environment or mounts of an existing container.
