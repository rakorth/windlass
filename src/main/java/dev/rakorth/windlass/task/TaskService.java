package dev.rakorth.windlass.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import java.util.Map;
import java.util.UUID;

@Service
public class TaskService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Task> mapper;

    public TaskService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, row) -> new Task(rs.getString("id"), rs.getString("name"),
                rs.getString("description"),
                json.readValue(rs.getString("links"), new TypeReference<Map<String, String>>() {}),
                json.readValue(rs.getString("metadata"), new TypeReference<Map<String, Object>>() {}));
    }

    public TaskPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be nonnegative and size must be between 1 and 100");
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Long.class);
        var items = jdbc.query("SELECT * FROM tasks ORDER BY name, id LIMIT ? OFFSET ?",
                mapper, size, (long) page * size);
        return new TaskPage(items, page, size, total, (total + size - 1) / size);
    }

    public Task get(String id) {
        return jdbc.query("SELECT * FROM tasks WHERE id = ?", mapper, id).stream()
                .findFirst().orElseThrow(this::notFound);
    }

    public Task create(TaskRequest request) {
        var value = normalize(UUID.randomUUID().toString(), request);
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata) VALUES (?, ?, ?, ?, ?)",
                value.id(), value.name(), value.description(),
                json.writeValueAsString(value.links()), json.writeValueAsString(value.metadata()));
        return value;
    }

    public Task update(String id, TaskRequest request) {
        get(id);
        var value = normalize(id, request);
        int changed = jdbc.update("UPDATE tasks SET name = ?, description = ?, links = ?, metadata = ? WHERE id = ?",
                value.name(), value.description(), json.writeValueAsString(value.links()),
                json.writeValueAsString(value.metadata()), id);
        if (changed == 0) throw notFound();
        return get(id);
    }

    public void delete(String id) {
        if (jdbc.update("DELETE FROM tasks WHERE id = ?", id) == 0) throw notFound();
    }

    private Task normalize(String id, TaskRequest request) {
        var links = request.links() == null ? Map.<String, String>of() : request.links();
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
        return new Task(id, request.name().strip(), request.description() == null ? "" : request.description(),
                links, request.metadata() == null ? Map.of() : request.metadata());
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found");
    }
}
