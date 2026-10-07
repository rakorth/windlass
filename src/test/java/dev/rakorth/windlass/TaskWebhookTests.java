package dev.rakorth.windlass;

import com.sun.net.httpserver.HttpServer;
import dev.rakorth.windlass.task.TaskWebhookSchemaMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
@AutoConfigureMockMvc
class TaskWebhookTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM tasks");
        jdbc.update("DELETE FROM task_templates");
        jdbc.update("DELETE FROM notifications");
    }

    @Test
    void sendsTaskAndStepEventsOnlyForStatusChangesAndNotifiesEachFailure() throws Exception {
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/fail", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            String initial = body(url, "PENDING");
            var response = mvc.perform(post("/api/tasks").contentType("application/json").content(initial))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.webhooks[0]").value(url + "/fail"))
                    .andReturn();
            String id = json.readTree(response.getResponse().getContentAsString()).get("id").asString();
            assertTrue(requests.isEmpty());
            mvc.perform(put("/api/tasks/" + id).contentType("application/json").content(initial)).andExpect(status().isOk());
            assertTrue(requests.isEmpty());
            mvc.perform(put("/api/tasks/" + id).contentType("application/json").content(body(url, "DONE")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
            assertEquals(2, requests.size());
            var taskEvent = json.readTree(requests.get(0));
            assertEquals("task.status_changed", taskEvent.get("event").asString());
            assertEquals(id, taskEvent.get("taskId").asString());
            assertEquals("PENDING", taskEvent.get("previousStatus").asString());
            assertEquals("DONE", taskEvent.get("status").asString());
            var stepEvent = json.readTree(requests.get(1));
            assertEquals("task_step.status_changed", stepEvent.get("event").asString());
            assertEquals(0, stepEvent.get("stepIndex").asInt());
            assertEquals("Build", stepEvent.get("step").get("name").asString());
            mvc.perform(get("/api/notifications"))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.items[0].unread").value(true))
                    .andExpect(jsonPath("$.items[0].notification_source").value("task-webhooks"))
                    .andExpect(jsonPath("$.items[0].metadata_map.httpStatus").value(503))
                    .andExpect(jsonPath("$.items[0].metadata_map.taskId").value(id));
            mvc.perform(put("/api/tasks/" + id).contentType("application/json").content(body(url, "DONE"))).andExpect(status().isOk());
            assertEquals(2, requests.size());
        } finally { server.stop(0); }
    }

    private String body(String url, String status) {
        return """
                {"name":"Release","status":"%s","webhooks":["%s/fail","%s/ok"],
                 "steps":[{"name":"Build","status":"%s","webhooks":["%s/ok","%s/fail"]}]}
                """.formatted(status, url, url, status, url, url);
    }

    @Test
    void connectionFailuresCreateNotificationsAndDoNotUndoUpdates() throws Exception {
        var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"));
        int port = socket.getLocalPort();
        socket.close();
        String body = "{\"name\":\"Test\",\"webhooks\":[\"http://127.0.0.1:" + port + "/\"]}";
        var response = mvc.perform(post("/api/tasks").contentType("application/json").content(body)).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).get("id").asString();
        mvc.perform(put("/api/tasks/" + id).contentType("application/json")
                .content(body.replace("\"Test\"", "\"Test\",\"status\":\"DONE\"")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/notifications"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].metadata_map.error").isNotEmpty());
        mvc.perform(get("/api/tasks/" + id)).andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void validatesUrlsAndCopiesTemplatesWithoutSendingEvents() throws Exception {
        for (String invalid : new String[]{"/relative", "ftp://example.com", "", "http://", "https://bad host"}) {
            for (String field : new String[]{"\"webhooks\":[\"%s\"]", "\"steps\":[{\"name\":\"Build\",\"status\":\"PENDING\",\"webhooks\":[\"%s\"]}]"}) {
                mvc.perform(post("/api/tasks").contentType("application/json")
                        .content("{\"name\":\"Test\"," + field.formatted(invalid) + "}"))
                        .andExpect(status().isBadRequest());
            }
        }
        var response = mvc.perform(post("/api/task-templates").contentType("application/json")
                .content(body("http://127.0.0.1:1", "PENDING"))).andExpect(status().isCreated()).andReturn();
        String id = json.readTree(response.getResponse().getContentAsString()).get("id").asString();
        mvc.perform(put("/api/task-templates/" + id).contentType("application/json")
                .content(body("http://127.0.0.1:1", "DONE"))).andExpect(status().isOk());
        mvc.perform(post("/api/task-templates/" + id + "/tasks"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.webhooks.length()").value(2))
                .andExpect(jsonPath("$.steps[0].webhooks.length()").value(2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
    }

    @Test
    void defaultsLegacyStepListsAndReplacesWebhookListsWithoutFalseEvents() throws Exception {
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata, steps) VALUES ('legacy', 'Release', '', '{}', '{}', ?)",
                "[{\"name\":\"Build\",\"status\":\"PENDING\"}]");
        mvc.perform(get("/api/tasks/legacy"))
                .andExpect(jsonPath("$.webhooks").isEmpty())
                .andExpect(jsonPath("$.steps[0].webhooks").isEmpty());
        String renamed = """
                {"name":"Release","webhooks":["http://127.0.0.1:1/"],
                 "steps":[{"name":"Deploy","status":"DONE","webhooks":["http://127.0.0.1:1/"]}]}
                """;
        mvc.perform(put("/api/tasks/legacy").contentType("application/json").content(renamed))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks/legacy"))
                .andExpect(jsonPath("$.webhooks.length()").value(1))
                .andExpect(jsonPath("$.steps[0].webhooks.length()").value(1));
        for (String clear : new String[]{"", ",\"webhooks\":null"}) {
            mvc.perform(put("/api/tasks/legacy").contentType("application/json")
                    .content("{\"name\":\"Release\",\"status\":\"DONE\"" + clear + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.webhooks").isEmpty());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
    }

    @Test
    void migratesLegacyTablesIdempotentlyWithoutLosingData() {
        var legacy = new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var db = new JdbcTemplate(legacy);
            for (String table : new String[]{"tasks", "task_templates"}) {
                db.execute("CREATE TABLE " + table + " (id TEXT, steps TEXT)");
                db.update("INSERT INTO " + table + " VALUES ('old', '[]')");
            }
            var migration = new TaskWebhookSchemaMigration(db);
            migration.migrate();
            migration.migrate();
            for (String table : new String[]{"tasks", "task_templates"}) {
                assertEquals("[]", db.queryForObject("SELECT webhooks FROM " + table + " WHERE id = 'old'", String.class));
            }
        } finally { legacy.destroy(); }
    }
}
