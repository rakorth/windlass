# Repository Guidelines

## Project Structure & Module Organization

Windlass is a Java 24 Spring Boot notification manager with SQLite persistence and a vanilla JavaScript UI. Backend code lives in `src/main/java/dev/rakorth/windlass/`, organized into `notification` and `automation` packages. Configuration and SQL schema live in `src/main/resources/`; HTML, CSS, and JavaScript assets are in its `static/` directory. Java integration tests live in `src/test/java/dev/rakorth/windlass/`.

`docker/` contains n8n helpers and the Python backup manager. `deploy/` and `scripts/` support portable deployment bundles. Start with `docs/README.md` for architecture, API, and deployment documentation.

## Agent Skills

- [Windlass REST](skills/windlass-rest/SKILL.md): Read before operating a running Windlass instance through its API. Covers notification CRUD, source discovery and filtering, unread state, tasks, reusable templates, direct record links, status webhooks, and n8n backups. Use the deployment's reachable base URL and actual returned IDs.
- [Tasks and task templates](skills/windlass-rest/references/tasks.md): Read for task/template CRUD, search and status filters, reminder filters, creating tasks from templates, and webhook delivery failures. Task listing supports strict `notifyMeOnBefore` and `notifyMeOnAfter` bounds with timezone-bearing ISO 8601 timestamps; filters exclude null reminders and apply before pagination. Templates do not expose reminder filters.
- [n8n backups](skills/windlass-rest/references/n8n-backups.md): Read for backup status, creation, ZIP downloads, and restores, including required headers, confirmation, timeouts, and failure handling.

Share browser links using the deployment's browser-accessible base URL: `/task.html?id=<URL-encoded-id>` for tasks and `/notification.html?id=<URL-encoded-id>` for notifications. Templates have no dedicated details page. Traverse all list pages when complete results are required; collect matching IDs before bulk mutations.

Repository development uses the guidelines below. Keep the REST skill and its relevant references aligned with changes to API behavior and user-facing record links.

## Build, Test, and Development Commands

Use JDK 24 or newer and the Maven wrapper:

- `./mvnw spring-boot:run`: run locally at `http://localhost:8080`.
- `./mvnw test`: run Java integration tests.
- `./mvnw package`: test and build the executable JAR in `target/`.
- `docker compose up -d --build`: build and run Windlass, n8n, and backup services; Windlass is available on port 6080.
- `(cd docker/n8n-backup && python3 -m unittest -v)`: run backup manager tests.
- `sh scripts/package-deployment.sh`: create `target/windlass-deployment.tar.gz`.

There is no separate frontend build.

## Coding Style & Naming Conventions

Follow surrounding code: four-space indentation in Java and Python, two spaces in JavaScript. Use PascalCase for Java classes and camelCase for methods and variables. Preserve API property names such as `notification_source` and `metadata_map`. Keep controllers focused on HTTP behavior and notification persistence in the service layer. No dedicated formatter or linter is configured.

## Testing Guidelines

Java tests use JUnit Jupiter, Spring Boot, and MockMvc with isolated in-memory SQLite databases. Name classes `*Tests` and methods after the behavior being verified. Cover changed API behavior, validation, persistence, and migrations; backup changes should also exercise Python `unittest` cases. No numeric coverage threshold is configured. Manually verify UI changes in the browser.

## Commit & Pull Request Guidelines

History follows Conventional Commits, including `feat: ...`, scoped messages such as `feat(notifications): ...`, and `!` for breaking changes. Follow this pattern. PR descriptions should explain behavior changes and validation performed; link relevant issues and include screenshots for visible UI changes. Update affected documentation when behavior changes.

## Security & Configuration Tips

Use `DATABASE_URL` and `SERVER_PORT` for local configuration. The application has no authentication; retain localhost bindings unless access is protected. Never commit databases, backup archives, or credentials. Avoid `docker compose down --volumes` unless intentionally deleting persistent data.
