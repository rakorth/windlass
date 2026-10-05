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
    void searchesContentAndNestedMetadataBeforePagination() throws Exception {
        jdbc.update("INSERT INTO tasks VALUES ('a', 'Release plan', '', '{}', '{}')");
        jdbc.update("INSERT INTO tasks VALUES ('b', 'Review', 'RELEASE notes', '{}', '{}')");
        jdbc.update("INSERT INTO tasks VALUES ('c', 'Ship', '', '{}', ?)",
                "{\"nested\":{\"tags\":[\"release\"],\"ticket_id\":42},\"release_flag\":true}");
        jdbc.update("INSERT INTO tasks VALUES ('d', 'Other', '', ?, '{}')",
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
        jdbc.update("INSERT INTO tasks VALUES ('e', '100%_done', '', '{}', '{}')");
        mvc.perform(get("/api/tasks").param("search", "%_"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value("e"));
        mvc.perform(get("/api/tasks").param("search", "  "))
                .andExpect(jsonPath("$.totalElements").value(5));
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
