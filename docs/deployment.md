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

Run against a PostgreSQL database reachable from the Java process. Flyway
applies schema migrations before the application starts serving requests.
See [Database migrations](migrations.md) for details.

## Configuration

Copy [.env.example](../.env.example) to `.env` and fill in the connection
settings for an existing PostgreSQL database. Give the database account
permission to create and alter tables in its target schema. Pass the file to
`docker run --env-file .env`, or export the settings for a local Java process.
If PostgreSQL runs on the Docker host, use an address reachable from the
container. The container's loopback address refers to Windlass itself.

| Setting | Default | Scope |
| --- | --- | --- |
| `NOTIFICATION_RETENTION_DAYS` | `30` | Positive integer; read notifications older than this many days by `received_on` are deleted daily at 03:00 UTC; unread notifications are retained |
| `DATABASE_URL` | Required | PostgreSQL JDBC connection string, for example `jdbc:postgresql://db.example.com:5432/windlass` |
| `DATABASE_USERNAME` | Empty | PostgreSQL account name |
| `DATABASE_PASSWORD` | Empty | PostgreSQL account password |
| `SERVER_PORT` | `8080` | Spring Boot HTTP listener; no explicit port currently in application YAML |
| `WINDLASS_MCP_ENABLED` | `true` | Enable notification MCP tools at `/mcp`; see [MCP server](mcp.md) |
| `WINDLASS_BASE_URL` | Agent examples default to `http://localhost:8080` | Client-side skill convention; not read by the server |

Configuration lives in [application.yaml](../src/main/resources/application.yaml).
The Docker examples publish host port 6080 to container port 8080. To change
only the browser port, change the host side of the mapping. Keep it bound to
`127.0.0.1` unless access is protected by authentication.

## Portable deployment with the published image

Run `sh scripts/package-deployment.sh` to create
`target/windlass-deployment.tar.gz`. The archive contains the
[bundled guide](../deploy/README.md) and an example environment file. The
recipient uses the published image and fills in their PostgreSQL connection.

## PostgreSQL persistence

Notification data lives in the database identified by `DATABASE_URL`. Docker
container recreation and removal do not affect it. Back it up with your
PostgreSQL backup process.

## Application image

```sh
docker build -t windlass:local .
docker run --name windlass-app --rm \
  -p 127.0.0.1:6080:8080 --env-file .env -v windlass-files:/data windlass:local
```

The root [Dockerfile](../Dockerfile) uses Java 25 JDK to package Windlass, compiling
with the project's Java 24 target. The runtime stage contains a Java 25 JRE and
the executable JAR, runs as non-root UID/GID `10001`, exposes port 8080, and connects to PostgreSQL using the supplied environment.
Image defaults store ref-docs in `/data/ref-docs` and
json-conf in `/data/json-conf`, so standalone containers can create the folders
as UID 10001 and retain files in the data volume. CI runs integration tests against PostgreSQL
before building the image.
BuildKit caches Maven downloads. [.dockerignore](../.dockerignore) limits the
context to build inputs, excluding local databases, environment files, and Git
metadata.

## GitHub Actions

[The Docker workflow](../.github/workflows/docker.yml) defines these behaviors:

| Event/ref | Run PostgreSQL tests and build image | Publish to GHCR |
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

## Document storage

The `windlass-files` volume at `/data` persists [ref-docs](ref-docs.md) and
[json-conf](json-conf.md). To use existing host folders, add bind mounts for
`/data/ref-docs` and `/data/json-conf`, for example
`--mount type=bind,source=/srv/windlass/ref-docs,target=/data/ref-docs`.
Create the source folders first and grant UID 10001 read/write access.
Back up the document folders separately from PostgreSQL.
