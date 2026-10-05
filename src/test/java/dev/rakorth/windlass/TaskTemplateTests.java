package dev.rakorth.windlass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
@AutoConfigureMockMvc
class TaskTemplateTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM tasks");
        jdbc.update("DELETE FROM task_templates");
    }

    @Test
    void copiesEveryFieldIntoIndependentTasksAndKeepsTemplateSeparate() throws Exception {
        var result = mvc.perform(post("/api/task-templates").contentType("application/json").content("""
                {"name":" Release ","description":"Ship it","status":"WAITING",
                 "notifyMeOn":"2026-12-01T12:30:00Z","links":{"Issue":"https://example.com/42"},
                 "metadata":{"nested":{"tags":["release"],"optional":null}},"steps":[
                 {"name":" Build ","status":"DONE","metadata":{"attempt":2},"links":{"CI":"https://ci.example.com"}},
                 {"name":"Deploy","status":"PENDING"}]}
                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Release")).andReturn();
        var template = json.readTree(result.getResponse().getContentAsString());
        String path = "/api/task-templates/" + template.get("id").asString();
        assertEquals(path, result.getResponse().getHeader("Location"));
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/task-templates").param("search", "release").param("status", "WAITING"))
                .andExpect(jsonPath("$.totalElements").value(1));
        String firstId = null;
        for (int i = 0; i < 2; i++) {
            var copy = mvc.perform(post(path + "/tasks")).andExpect(status().isCreated()).andReturn();
            var task = json.readTree(copy.getResponse().getContentAsString());
            String id = task.get("id").asString();
            assertNotEquals(template.get("id").asString(), id);
            assertNotEquals(firstId, id);
            firstId = id;
            assertEquals("/api/tasks/" + id, copy.getResponse().getHeader("Location"));
            for (String field : new String[]{"name", "description", "status", "notifyMeOn", "links", "metadata", "steps"}) {
                assertEquals(template.get(field), task.get(field), field);
            }
            mvc.perform(get("/api/tasks/" + id)).andExpect(jsonPath("$.steps[0].metadata.attempt").value(2));
        }
        mvc.perform(put("/api/tasks/" + firstId).contentType("application/json").content("{\"name\":\"Changed task\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(jsonPath("$.name").value("Release"))
                .andExpect(jsonPath("$.steps.length()").value(2));
        mvc.perform(put(path).contentType("application/json").content("{\"name\":\"Changed template\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.steps").isEmpty());
        mvc.perform(delete(path)).andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(post(path + "/tasks")).andExpect(status().isNotFound());
        mvc.perform(put(path).contentType("application/json").content("{\"name\":\"Missing\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path)).andExpect(status().isNotFound());
    }

    @Test
    void sharesTaskValidationDefaultsAndPagination() throws Exception {
        var result = mvc.perform(post("/api/task-templates").contentType("application/json").content("{\"name\":\"Keep\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.steps").isEmpty()).andExpect(jsonPath("$.metadata").isEmpty()).andReturn();
        String path = "/api/task-templates/" + json.readTree(result.getResponse().getContentAsString()).get("id").asString();
        for (String body : new String[]{"{}", "{\"name\":\" \"}",
                "{\"name\":\"" + "x".repeat(201) + "\"}",
                "{\"name\":\"Bad\",\"status\":\"unknown\"}",
                "{\"name\":\"Bad\",\"notifyMeOn\":\"tomorrow\"}",
                "{\"name\":\"Bad\",\"links\":{\"X\":\"/relative\"}}",
                "{\"name\":\"Bad\",\"steps\":[null]}",
                "{\"name\":\"Bad\",\"steps\":[{\"name\":\"Step\",\"status\":\"WAITING\"}]}"}) {
            mvc.perform(post("/api/task-templates").contentType("application/json").content(body)).andExpect(status().isBadRequest());
            mvc.perform(put(path).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path)).andExpect(jsonPath("$.name").value("Keep"));
        mvc.perform(get("/api/task-templates").param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/task-templates").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/task-templates").param("search", "missing")).andExpect(jsonPath("$.items").isEmpty());
    }
}
