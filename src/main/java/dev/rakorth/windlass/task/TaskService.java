package dev.rakorth.windlass.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

@org.springframework.context.annotation.DependsOn("taskWebhookSchemaMigration")
@Service
public class TaskService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Task> mapper;
    private final String table;
    private final TaskWebhookService webhooks;

    @org.springframework.beans.factory.annotation.Autowired
    public TaskService(JdbcTemplate jdbc, ObjectMapper json, TaskWebhookService webhooks) {
        this(jdbc, json, "tasks", webhooks);
    }

    TaskService(JdbcTemplate jdbc, ObjectMapper json, String table, TaskWebhookService webhooks) {
        this.table = table;
        this.webhooks = webhooks;
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, row) -> {
            String notifyMeOn = rs.getString("notify_me_on");
            return new Task(rs.getString("id"), rs.getString("name"),
                    rs.getString("description"), TaskStatus.valueOf(rs.getString("status")),
                    json.readValue(rs.getString("links"), new TypeReference<Map<String, String>>() {}),
                    json.readValue(rs.getString("metadata"), new TypeReference<Map<String, Object>>() {}),
                    json.readValue(rs.getString("steps"), new TypeReference<List<Step>>() {}),
                    notifyMeOn == null ? null : Instant.parse(notifyMeOn),
                    json.readValue(rs.getString("webhooks"), new TypeReference<List<String>>() {}));
        };
    }

    public TaskPage list(int page, int size, String search, TaskStatus status) {
        return list(page, size, search, status, null, null);
    }

    public TaskPage list(int page, int size, String search, TaskStatus status,
                         Instant notifyMeOnBefore, Instant notifyMeOnAfter) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be nonnegative and size must be between 1 and 100");
        }
        String where = "";
        var parameters = new ArrayList<Object>();
        if (search != null && !search.isBlank()) {
            where = """
                     WHERE (instr(lower(name), lower(?)) > 0
                        OR instr(lower(description), lower(?)) > 0
                        OR EXISTS (SELECT 1 FROM json_tree(%s.metadata) AS entry
                                   WHERE instr(lower(CAST(entry.key AS TEXT)), lower(?)) > 0
                                      OR instr(lower(CASE WHEN entry.type IN ('true', 'false', 'null')
                                                          THEN entry.type ELSE CAST(entry.atom AS TEXT) END), lower(?)) > 0))
                    """.formatted(table);
            for (int i = 0; i < 4; i++) parameters.add(search.strip());
        }
        if (status != null) {
            where += where.isEmpty() ? " WHERE status = ?" : " AND status = ?";
            parameters.add(status.name());
        }
        // Remove Z so whole seconds sort before fractional seconds at the same instant.
        if (notifyMeOnBefore != null) {
            where += where.isEmpty() ? " WHERE " : " AND ";
            where += "replace(notify_me_on, 'Z', '') < ?";
            parameters.add(notifyMeOnBefore.toString().replace("Z", ""));
        }
        if (notifyMeOnAfter != null) {
            where += where.isEmpty() ? " WHERE " : " AND ";
            where += "replace(notify_me_on, 'Z', '') > ?";
            parameters.add(notifyMeOnAfter.toString().replace("Z", ""));
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + where,
                Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add((long) page * size);
        var items = jdbc.query("SELECT * FROM " + table + where + " ORDER BY name, id LIMIT ? OFFSET ?",
                mapper, parameters.toArray());
        return new TaskPage(items, page, size, total, (total + size - 1) / size);
    }

    public Task get(String id) {
        return jdbc.query("SELECT * FROM " + table + " WHERE id = ?", mapper, id).stream()
                .findFirst().orElseThrow(this::notFound);
    }

    public Task create(TaskRequest request) {
        var value = normalize(UUID.randomUUID().toString(), request);
        jdbc.update("INSERT INTO " + table + " (id, name, description, links, metadata, steps, status, notify_me_on, webhooks) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                value.id(), value.name(), value.description(),
                json.writeValueAsString(value.links()), json.writeValueAsString(value.metadata()), json.writeValueAsString(value.steps()), value.status().name(),
                value.notifyMeOn() == null ? null : value.notifyMeOn().toString(), json.writeValueAsString(value.webhooks()));
        return value;
    }

    public Task update(String id, TaskRequest request) {
        var previous = get(id);
        var value = normalize(id, request);
        int changed = jdbc.update("UPDATE " + table + " SET name = ?, description = ?, links = ?, metadata = ?, steps = ?, status = ?, notify_me_on = ?, webhooks = ? WHERE id = ?",
                value.name(), value.description(), json.writeValueAsString(value.links()),
                json.writeValueAsString(value.metadata()), json.writeValueAsString(value.steps()), value.status().name(),
                value.notifyMeOn() == null ? null : value.notifyMeOn().toString(), json.writeValueAsString(value.webhooks()), id);
        if (changed == 0) throw notFound();
        if (table.equals("tasks")) webhooks.statusChanged(previous, value);
        return value;
    }

    public void delete(String id) {
        if (jdbc.update("DELETE FROM " + table + " WHERE id = ?", id) == 0) throw notFound();
    }

    private Task normalize(String id, TaskRequest request) {
        var links = request.links() == null ? Map.<String, String>of() : request.links();
        validateLinks(links);
        var steps = request.steps() == null ? List.<Step>of() : request.steps().stream().map(step -> {
            var stepLinks = step.links() == null ? Map.<String, String>of() : step.links();
            validateLinks(stepLinks);
            return new Step(step.name().strip(), step.description(), step.status(),
                    step.metadata() == null ? Map.of() : step.metadata(), stepLinks, normalizeWebhooks(step.webhooks()));
        }).toList();
        return new Task(id, request.name().strip(), request.description() == null ? "" : request.description(),
                request.status() == null ? TaskStatus.PENDING : request.status(),
                links, request.metadata() == null ? Map.of() : request.metadata(), steps, request.notifyMeOn(), normalizeWebhooks(request.webhooks()));
    }

    private List<String> normalizeWebhooks(List<String> urls) {
        if (urls == null) return List.of();
        for (String url : urls) validateLinks(Map.of("Webhook", url == null ? "" : url));
        return List.copyOf(urls);
    }

    private void validateLinks(Map<String, String> links) {
        links.forEach((label, url) -> {
            try {
                var uri = java.net.URI.create(url);
                if (label == null || label.isBlank() || uri.getHost() == null
                        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Links require a nonblank label and an absolute HTTP or HTTPS URL");
            }
        });
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, table.equals("tasks") ? "Task not found" : "Task template not found");
    }
}
