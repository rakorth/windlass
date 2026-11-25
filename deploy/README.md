# Run Windlass on another machine

Install Docker Engine or Docker Desktop configured for Linux containers. The
published Windlass image runs on Linux AMD64 and ARM64. You also need a
PostgreSQL database reachable from the container.

## Start

Extract `windlass-deployment.tar.gz` and open a terminal in its
`windlass-deployment` directory. Copy `.env.example` to `.env` and fill in
`DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`. Then run:

```sh
docker pull ghcr.io/rakorth/windlass:latest
docker run -d --name windlass --restart unless-stopped \
  -p 127.0.0.1:6080:8080 --env-file .env \
  -v windlass-files:/data ghcr.io/rakorth/windlass:latest
```

Open http://localhost:6080. To choose a different host port, change `6080` in the
port mapping. MCP clients can connect to `http://localhost:6080/mcp` with
Streamable HTTP. Set `WINDLASS_MCP_ENABLED=false` in `.env` and recreate the
container to disable MCP.

Use a published tag or digest to pin a version. If the registry requires
authentication, use `docker login ghcr.io` with an account and token that
can read the package.

## Access a remote machine

Windlass has no built-in authentication. Keep the port bound to host loopback
and forward it from your computer:

```sh
ssh -N -L 6080:127.0.0.1:6080 user@server
```

Then open http://localhost:6080 locally. Put authentication in front of
Windlass before exposing it over the network.

## Update and manage

Pull the updated image, stop and remove the old container, then repeat the
`docker run` command with the same `.env` and volume name.

```sh
docker pull ghcr.io/rakorth/windlass:latest
docker stop windlass
docker rm windlass
```

Use `docker logs --tail=100 -f windlass` to check startup. PostgreSQL stores
notifications. The `windlass-files` volume stores reference documents and
JSON configuration under `/data`. Back up PostgreSQL and the volume separately.
