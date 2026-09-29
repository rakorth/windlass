package dev.rakorth.windlass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "N8N_EDITOR_BASE_URL=http://localhost:15678/"
})
@AutoConfigureMockMvc
class WindlassApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clear() { jdbc.update("DELETE FROM notifications"); }

    @Test
    void completeCrudLifecycle() throws Exception {
        var result = mvc.perform(post("/api/notifications").contentType("application/json").content("""
                {"title":"Build complete","description":"Ready to deploy","notification_source":"CI",
                 "received_on":"2026-01-01T12:00:00Z","metadata_map":{"attempt":2,"tags":["release"],"nested":{"ok":true}}}
                """))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.metadata_map.nested.ok").value(true)).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        String path = "/api/notifications/" + id;
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Build complete"));
        mvc.perform(get("/api/notifications")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(put(path).contentType("application/json").content("""
                {"title":"Updated","notification_source":"Email","metadata_map":{"priority":"high"}}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.received_on").value("2026-01-01T12:00:00Z"));
        mvc.perform(get(path)).andExpect(jsonPath("$.title").value("Updated"))
                .andExpect(jsonPath("$.metadata_map.priority").value("high"));
        mvc.perform(delete(path)).andExpect(status().isNoContent());
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(delete(path)).andExpect(status().isNotFound());
        mvc.perform(put(path).contentType("application/json").content("""
                {"title":"Missing","notification_source":"CI"}
                """ )).andExpect(status().isNotFound());
        mvc.perform(get("/api/notifications")).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void defaultsAndValidation() throws Exception {
        mvc.perform(post("/api/notifications").contentType("application/json").content("""
                {"title":"  Hello  ","notification_source":" App "}
                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("Hello"))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.received_on").isNotEmpty()).andExpect(jsonPath("$.metadata_map").isMap());
        for (String invalid : new String[]{
                "{}", "{\"title\":\"  \",\"notification_source\":\"app\"}",
                "{\"title\":\"Hello\",\"notification_source\":\"app\",\"received_on\":\"bad-date\"}",
                "{\"title\":\"Hello\",\"notification_source\":\"app\",\"metadata_map\":[]}"}) {
            mvc.perform(post("/api/notifications").contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void externalLinksRoundTripAndValidation() throws Exception {
        String body = """
                {"title":"Issue","notification_source":"GitHub","external_links":{"Issue":"https://github.com/example/issues/1"}}
                """;
        var result = mvc.perform(post("/api/notifications").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        String path = "/api/notifications/" + id;
        mvc.perform(get(path)).andExpect(jsonPath("$.external_links.Issue").value("https://github.com/example/issues/1"));
        mvc.perform(get("/api/notifications")).andExpect(jsonPath("$[0].external_links.Issue").exists());
        mvc.perform(put(path).contentType("application/json").content(body.replace("https://github.com/example/issues/1", "http://localhost:9000/ticket")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.external_links.Issue").value("http://localhost:9000/ticket"));
        for (String url : new String[]{"javascript:alert(1)", "data:text/html,hello", "/relative", "https://", "not a url"}) {
            mvc.perform(put(path).contentType("application/json").content(body.replace("https://github.com/example/issues/1", url)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(path)).andExpect(jsonPath("$.external_links.Issue").value("http://localhost:9000/ticket"));
        mvc.perform(put(path).contentType("application/json").content("""
                {"title":"Issue","notification_source":"GitHub"}
                """ )).andExpect(status().isOk()).andExpect(jsonPath("$.external_links").isEmpty());
    }

    @Test
    void migratesLegacyDatabaseWithoutLosingNotifications() {
        var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var legacy = new JdbcTemplate(source);
            legacy.execute("CREATE TABLE notifications (id TEXT PRIMARY KEY, title TEXT)");
            legacy.update("INSERT INTO notifications VALUES ('existing', 'Keep me')");
            var migration = new dev.rakorth.windlass.notification.NotificationSchemaMigration(legacy);
            migration.migrate();
            org.junit.jupiter.api.Assertions.assertEquals(1, legacy.queryForObject("SELECT unread FROM notifications", Integer.class));
            legacy.update("UPDATE notifications SET unread = 0");
            migration.migrate();
            org.junit.jupiter.api.Assertions.assertEquals(0, legacy.queryForObject("SELECT unread FROM notifications", Integer.class));
            org.junit.jupiter.api.Assertions.assertEquals("Keep me", legacy.queryForObject("SELECT title FROM notifications", String.class));
            org.junit.jupiter.api.Assertions.assertEquals("{}", legacy.queryForObject("SELECT external_links FROM notifications", String.class));
        } finally { source.destroy(); }
    }

    @Test
    void unreadStatePersistsAndSurvivesEdits() throws Exception {
        String body = """
                {"title":"New notification","notification_source":"App"}
                """;
        var result = mvc.perform(post("/api/notifications").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.unread").value(true)).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        String path = "/api/notifications/" + id;
        mvc.perform(get(path)).andExpect(jsonPath("$.unread").value(true));
        mvc.perform(put(path).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(true));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(patch(path + "/seen")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.unread").value(false))
                    .andExpect(jsonPath("$.title").value("New notification"));
        }
        mvc.perform(get(path)).andExpect(jsonPath("$.unread").value(false));
        mvc.perform(get("/api/notifications")).andExpect(jsonPath("$[0].unread").value(false));
        mvc.perform(put(path).contentType("application/json").content(body.replace("New notification", "Edited")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(false));
        mvc.perform(get(path)).andExpect(jsonPath("$.unread").value(false))
                .andExpect(jsonPath("$.title").value("Edited"));
        mvc.perform(patch("/api/notifications/missing/seen")).andExpect(status().isNotFound());
    }

    @Test
    void automationsOpensConfiguredEditor() throws Exception {
        mvc.perform(get("/automations"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:15678/"));
    }

    @Test
    void newestFirstAndUiAvailable() throws Exception {
        for (String timestamp : new String[]{"2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z"}) {
            mvc.perform(post("/api/notifications").contentType("application/json").content(
                    "{\"title\":\"Update\",\"notification_source\":\"CI\",\"received_on\":\"" + timestamp + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/api/notifications"))
                .andExpect(jsonPath("$[0].received_on").value("2026-01-01T00:00:00Z"));
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/app.js")).andExpect(status().isOk());
    }
}
