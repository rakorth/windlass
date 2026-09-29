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
| `WINDLASS_PORT` | `8080` | Compose host-side port only; does not change the container's listener |
| `WINDLASS_BASE_URL` | Agent examples default to `http://localhost:8080` | Client-side skill convention; not read by the server |

Configuration lives in [application.yaml](../src/main/resources/application.yaml).
If changing the container's listener with `SERVER_PORT`, also change its port
mapping; setting `WINDLASS_PORT` alone intentionally keeps the internal port 8080.
There is no built-in authentication. The default Compose port is bound to host
loopback (`127.0.0.1`).

## Docker Compose

```sh
docker compose up -d --build
docker compose logs -f windlass
```

[compose.yaml](../compose.yaml) builds the root Dockerfile, runs service
`windlass`, maps host `127.0.0.1:8080` to container `8080`, and uses restart policy
`unless-stopped`. To avoid an occupied host port:

```sh
WINDLASS_PORT=8081 docker compose up -d --build
```

Then open http://localhost:8081. Keep the same port setting for later Compose
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
  -p 127.0.0.1:8080:8080 -v windlass-data:/data windlass:local
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
container port 8080 to host `127.0.0.1:8080` and lists it in `forwardPorts` for IDE
forwarding. Docker mapping changes require container recreation; IDE forwarding
can be managed separately. Start the app inside the dev container after setup.

The [.devcontainer/Dockerfile](../.devcontainer/Dockerfile) is a development
workspace image, separate from the root application image. It includes
Docker-in-Docker tooling; ports published by nested containers are relative to
that Docker host. Verify the forwarding path when accessing a nested Compose
service from the physical host. See [dev container details](../.devcontainer/README.md)
for tooling and Java setup.
