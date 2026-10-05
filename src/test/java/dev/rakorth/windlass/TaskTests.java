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

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
@AutoConfigureMockMvc
class TaskTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clear() { jdbc.update("DELETE FROM tasks"); }

    @Test
    void completeCrudLifecycleAndDefaults() throws Exception {
        var result = mvc.perform(post("/api/tasks").contentType("application/json").content("""
                {"name":"  Review  ","description":"Check build","links":{"Build":"https://ci.example.com/42"},
                 "metadata":{"nested":{"ok":true},"tags":["release"],"optional":null}}
                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Review")).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        String path = "/api/tasks/" + id;
        mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(jsonPath("$.links.Build").value("https://ci.example.com/42"))
                .andExpect(jsonPath("$.metadata.nested.ok").value(true))
                .andExpect(jsonPath("$.metadata.tags[0]").value("release"));
        org.junit.jupiter.api.Assertions.assertEquals(path, result.getResponse().getHeader("Location"));
        mvc.perform(put(path).contentType("application/json").content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.links").isEmpty()).andExpect(jsonPath("$.metadata").isEmpty());
        mvc.perform(get(path)).andExpect(jsonPath("$.name").value("Updated"));
        mvc.perform(delete(path)).andExpect(status().isNoContent());
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(delete(path)).andExpect(status().isNotFound());
        mvc.perform(put(path).contentType("application/json").content("{\"name\":\"Missing\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nullableNotifyMeOnPersistsAndValidatesDatetime() throws Exception {
        var result = mvc.perform(post("/api/tasks").contentType("application/json")
                .content("{\"name\":\"Reminder\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.notifyMeOn").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        String path = "/api/tasks/" + id;
        String body = "{\"name\":\"Reminder\",\"notifyMeOn\":\"2026-10-06T12:30:00+02:00\"}";
        mvc.perform(post("/api/tasks").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.notifyMeOn").value("2026-10-06T10:30:00Z"));
        mvc.perform(put(path).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notifyMeOn").value("2026-10-06T10:30:00Z"));
        mvc.perform(get(path)).andExpect(jsonPath("$.notifyMeOn").value("2026-10-06T10:30:00Z"));
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.items[0].notifyMeOn").value("2026-10-06T10:30:00Z"));
        org.junit.jupiter.api.Assertions.assertEquals("2026-10-06T10:30:00Z",
                jdbc.queryForObject("SELECT notify_me_on FROM tasks WHERE id = ?", String.class, id));
        for (String value : new String[]{"not-a-date", "2026-10-06T12:30:00"}) {
            String invalid = "{\"name\":\"Reminder\",\"notifyMeOn\":\"" + value + "\"}";
            mvc.perform(post("/api/tasks").contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
            mvc.perform(put(path).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(path)).andExpect(jsonPath("$.notifyMeOn").value("2026-10-06T10:30:00Z"));
        for (String clear : new String[]{"{\"name\":\"Reminder\",\"notifyMeOn\":null}", "{\"name\":\"Reminder\"}"}) {
            mvc.perform(put(path).contentType("application/json").content(body)).andExpect(status().isOk());
            mvc.perform(put(path).contentType("application/json").content(clear))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.notifyMeOn").value(org.hamcrest.Matchers.nullValue()));
            org.junit.jupiter.api.Assertions.assertNull(
                    jdbc.queryForObject("SELECT notify_me_on FROM tasks WHERE id = ?", String.class, id));
        }
    }

    @Test
    void taskStatusDefaultsPersistsAndRejectsInvalidValues() throws Exception {
        var result = mvc.perform(post("/api/tasks").contentType("application/json")
                .content("{\"name\":\"Review\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        String path = "/api/tasks/" + id;
        for (String value : new String[]{"DONE", "SKIPPED", "WAITING", "PENDING"}) {
            mvc.perform(put(path).contentType("application/json")
                    .content("{\"name\":\"Review\",\"status\":\"" + value + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(value));
            mvc.perform(get(path)).andExpect(jsonPath("$.status").value(value));
            mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.items[0].status").value(value));
        }
        for (String value : new String[]{"pending", "UNKNOWN", ""}) {
            String body = "{\"name\":\"Review\",\"status\":\"" + value + "\"}";
            mvc.perform(post("/api/tasks").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
            mvc.perform(put(path).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(path)).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/api/tasks").contentType("application/json")
                .content("{\"name\":\"Done\",\"status\":\"DONE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(put(path).contentType("application/json")
                .content("{\"name\":\"Review\",\"status\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(put(path).contentType("application/json")
                .content("{\"name\":\"Review\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void rejectsInvalidContentWithoutChangingStoredTask() throws Exception {
        var result = mvc.perform(post("/api/tasks").contentType("application/json").content("{\"name\":\"Keep\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        for (String invalid : new String[]{"{}", "{\"name\":\" \"}",
                "{\"name\":\"" + "x".repeat(201) + "\"}",
                "{\"name\":\"Task\",\"description\":\"" + "x".repeat(10001) + "\"}",
                "{\"name\":\"Task\",\"metadata\":[]}",
                "{\"name\":\"Task\",\"links\":{\" \":\"https://example.com\"}}",
                "{\"name\":\"Task\",\"links\":{\"Issue\":null}}",
                "{\"name\":\"Task\",\"links\":{\"Issue\":\"/relative\"}}",
                "{\"name\":\"Task\",\"links\":{\"Issue\":\"javascript:alert(1)\"}}"}) {
            mvc.perform(post("/api/tasks").contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
            mvc.perform(put("/api/tasks/" + id).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/tasks/" + id)).andExpect(jsonPath("$.name").value("Keep"));
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void stepsPersistInOrderAndAreReplacedWithTask() throws Exception {
        var result = mvc.perform(post("/api/tasks").contentType("application/json").content("""
                {"name":"Release","steps":[
                  {"name":" Build ","status":"PENDING","metadata":{"nested":{"attempt":2}},
                   "links":{"Build":"https://ci.example.com/42"}},
                  {"name":"Deploy","status":"SKIPPED"}]}
                """))
                .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        String path = "/api/tasks/" + id;
        mvc.perform(get(path)).andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].name").value("Build"))
                .andExpect(jsonPath("$.steps[0].status").value("PENDING"))
                .andExpect(jsonPath("$.steps[0].metadata.nested.attempt").value(2))
                .andExpect(jsonPath("$.steps[0].links.Build").value("https://ci.example.com/42"))
                .andExpect(jsonPath("$.steps[1].status").value("SKIPPED"))
                .andExpect(jsonPath("$.steps[1].metadata").isEmpty())
                .andExpect(jsonPath("$.steps[1].links").isEmpty());
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.items[0].steps[1].name").value("Deploy"));
        for (String steps : new String[]{"[null]", "[{}]",
                "[{\"name\":\"x\",\"status\":null}]",
                "[{\"name\":\"x\",\"status\":\"pending\"}]",
                "[{\"name\":\"x\",\"status\":\"SkIPPED\"}]", "[{\"name\":\"x\",\"status\":\" \"}]",
                "[{\"name\":\"x\",\"status\":\"PENDING\",\"links\":{\"Issue\":\"/relative\"}}]"}) {
            mvc.perform(put(path).contentType("application/json").content("{\"name\":\"Release\",\"steps\":" + steps + "}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(path)).andExpect(jsonPath("$.steps.length()").value(2));
        mvc.perform(put(path).contentType("application/json").content("""
                {"name":"Release","steps":[{"name":"Done","status":"DONE"}]}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.steps.length()").value(1));
        mvc.perform(get(path)).andExpect(jsonPath("$.steps[0].status").value("DONE"));
        for (String body : new String[]{"{\"name\":\"Release\"}", "{\"name\":\"Release\",\"steps\":null}"}) {
            mvc.perform(put(path).contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.steps").isEmpty());
        }
    }

    @Test
    void searchesContentAndNestedMetadataBeforePagination() throws Exception {
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata) VALUES ('a', 'Release plan', '', '{}', '{}')");
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata) VALUES ('b', 'Review', 'RELEASE notes', '{}', '{}')");
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata) VALUES ('c', 'Ship', '', '{}', ?)",
                "{\"nested\":{\"tags\":[\"release\"],\"ticket_id\":42},\"release_flag\":true}");
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata) VALUES ('d', 'Other', '', ?, '{}')",
                "{\"release\":\"https://example.com/release\"}");
        mvc.perform(get("/api/tasks").param("search", " RELEASE ").param("size", "2").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("c"))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2));
        for (String term : new String[]{"ticket_id", "42", "true"}) {
            mvc.perform(get("/api/tasks").param("search", term))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.items[0].id").value("c"));
        }
        for (String term : new String[]{"missing", "%", "%_", "' OR 1=1 --"}) {
            mvc.perform(get("/api/tasks").param("search", term))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata) VALUES ('e', '100%_done', '', '{}', '{}')");
        mvc.perform(get("/api/tasks").param("search", "%_"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value("e"));
        mvc.perform(get("/api/tasks").param("search", "  "))
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void filtersTaskStatusBeforePaginationAndCombinesWithSearch() throws Exception {
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata, status) VALUES ('a', 'Release', '', '{}', '{}', 'PENDING')");
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata, status) VALUES ('b', 'Release', '', '{}', '{}', 'WAITING')");
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata, status) VALUES ('c', 'Other', 'Release', '{}', '{}', 'WAITING')");
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata, status) VALUES ('d', 'Other', '', '{}', '{\"tag\":\"release\"}', 'DONE')");
        jdbc.update("INSERT INTO tasks (id, name, description, links, metadata, status) VALUES ('e', 'Other', '', '{}', '{\"tag\":\"release\"}', 'WAITING')");
        for (String filter : new String[]{"PENDING", "WAITING", "DONE", "SKIPPED"}) {
            var response = mvc.perform(get("/api/tasks").param("status", filter)).andExpect(status().isOk()).andReturn();
            var items = json.readTree(response.getResponse().getContentAsString()).get("items");
            for (var item : items) org.junit.jupiter.api.Assertions.assertEquals(filter, item.get("status").asString());
        }
        mvc.perform(get("/api/tasks").param("status", "WAITING").param("size", "1").param("page", "2"))
                .andExpect(jsonPath("$.items[0].id").value("b"))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/tasks").param("status", "WAITING").param("search", "RELEASE"))
                .andExpect(jsonPath("$.items.length()").value(3)).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/tasks").param("status", "WAITING").param("search", "tag"))
                .andExpect(jsonPath("$.items[0].id").value("e")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/tasks").param("status", "SKIPPED"))
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalPages").value(0));
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.totalElements").value(5));
        for (String invalid : new String[]{"waiting", "UNKNOWN", "' OR 1=1 --"}) {
            mvc.perform(get("/api/tasks").param("status", invalid)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void paginatesWithStableOrderingAndValidatesBounds() throws Exception {
        for (int i = 4; i >= 0; i--) {
            jdbc.update("INSERT INTO tasks (id, name, description, links, metadata) VALUES (?, 'Same', '', '{}', '{}')", "id-" + i);
        }
        mvc.perform(get("/api/tasks")).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20));
        mvc.perform(get("/api/tasks?page=1&size=2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("id-2"))
                .andExpect(jsonPath("$.items[1].id").value("id-3"))
                .andExpect(jsonPath("$.totalElements").value(5)).andExpect(jsonPath("$.totalPages").value(3));
        mvc.perform(get("/api/tasks?page=2&size=2")).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/tasks?page=2147483647&size=100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        for (String query : new String[]{"page=-1", "size=0", "size=101", "page=abc", "size=abc"}) {
            mvc.perform(get("/api/tasks?" + query)).andExpect(status().isBadRequest());
        }
        jdbc.update("DELETE FROM tasks");
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
    }
}
