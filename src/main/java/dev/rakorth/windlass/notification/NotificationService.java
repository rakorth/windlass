package dev.rakorth.windlass.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

@org.springframework.context.annotation.DependsOn("notificationSchemaMigration")
@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Notification> mapper;

    public NotificationService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, row) -> new Notification(rs.getString("id"), rs.getString("title"),
                rs.getString("description"), rs.getString("notification_source"),
                Instant.parse(rs.getString("received_on")),
                json.readValue(rs.getString("metadata_map"), new TypeReference<Map<String, Object>>() {}),
                json.readValue(rs.getString("external_links"), new TypeReference<Map<String, String>>() {}), rs.getBoolean("unread"));
    }

    public NotificationPage list(int page, int size, Boolean unread) {
        return list(page, size, unread, null);
    }

    public NotificationPage list(int page, int size, Boolean unread, String source) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be nonnegative and size must be between 1 and 100");
        }
        var conditions = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        if (unread != null) {
            conditions.add("unread = ?");
            parameters.add(unread ? 1 : 0);
        }
        if (source != null) {
            conditions.add("notification_source = ?");
            parameters.add(source);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM notifications" + where,
                Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add((long) page * size);
        var items = jdbc.query("SELECT * FROM notifications" + where
                + " ORDER BY julianday(received_on) DESC, id LIMIT ? OFFSET ?", mapper, parameters.toArray());
        return new NotificationPage(items, page, size, total, (total + size - 1) / size);
    }

    public List<String> sources() {
        return jdbc.queryForList("SELECT DISTINCT notification_source FROM notifications ORDER BY notification_source", String.class);
    }

    public Notification get(String id) {
        return jdbc.query("SELECT * FROM notifications WHERE id = ?", mapper, id).stream()
                .findFirst().orElseThrow(this::notFound);
    }

    public Notification create(NotificationRequest request) {
        var value = normalize(UUID.randomUUID().toString(), request, Instant.now(), true);
        jdbc.update("INSERT INTO notifications (id, title, description, notification_source, received_on, metadata_map, external_links) VALUES (?, ?, ?, ?, ?, ?, ?)",
                value.id(), value.title(), value.description(), value.notification_source(),
                value.received_on().toString(), json.writeValueAsString(value.metadata_map()), json.writeValueAsString(value.external_links()));
        return value;
    }

    public Notification update(String id, NotificationRequest request) {
        var existing = get(id);
        var value = normalize(id, request, existing.received_on(), existing.unread());
        int changed = jdbc.update("UPDATE notifications SET title = ?, description = ?, notification_source = ?, received_on = ?, metadata_map = ?, external_links = ? WHERE id = ?",
                value.title(), value.description(), value.notification_source(), value.received_on().toString(),
                json.writeValueAsString(value.metadata_map()), json.writeValueAsString(value.external_links()), id);
        if (changed == 0) throw notFound();
        return get(id);
    }

    public Notification markSeen(String id) {
        if (jdbc.update("UPDATE notifications SET unread = 0 WHERE id = ?", id) == 0) throw notFound();
        return get(id);
    }

    public Notification markUnread(String id) {
        if (jdbc.update("UPDATE notifications SET unread = 1 WHERE id = ?", id) == 0) throw notFound();
        return get(id);
    }

    public void delete(String id) {
        if (jdbc.update("DELETE FROM notifications WHERE id = ?", id) == 0) throw notFound();
    }

    private Notification normalize(String id, NotificationRequest request, Instant fallback, boolean unread) {
        var links = request.external_links() == null ? Map.<String, String>of() : request.external_links();
        links.forEach((label, url) -> {
            try {
                var uri = java.net.URI.create(url);
                if (label == null || label.isBlank() || uri.getHost() == null
                        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "External links require a nonblank label and an absolute HTTP or HTTPS URL");
            }
        });
        return new Notification(id, request.title().strip(), request.description() == null ? "" : request.description(),
                request.notification_source().strip(), request.received_on() == null ? fallback : request.received_on(),
                request.metadata_map() == null ? Map.of() : request.metadata_map(), links, unread);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
    }
}
