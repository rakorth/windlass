package dev.rakorth.windlass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
@AutoConfigureMockMvc
class JsonConfTests {
    @TempDir static Path directory;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("windlass.json-conf-directory", () -> directory.toString());
    }

    private String payload(String name, String content) {
        return json.writeValueAsString(Map.of("name", name, "content", content));
    }

    @Test
    void crudPreservesFormattingAndDiscoversExternalFiles() throws Exception {
        String content = "{\n  \"message\": \"Hello 🌍\",\n  \"enabled\": true\n}\n";
        mvc.perform(post("/api/json-conf").contentType("application/json").content(payload("notes.json", content)))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/json-conf/notes.json"))
                .andExpect(jsonPath("$.content").value(content));
        assertEquals(content, Files.readString(directory.resolve("notes.json")));
        mvc.perform(post("/api/json-conf").contentType("application/json").content(payload("notes.json", "{}")))
                .andExpect(status().isConflict());
        Files.writeString(directory.resolve("external.json"), "invalid external JSON");
        Files.writeString(directory.resolve("ignored.md"), "Ignored");
        mvc.perform(get("/api/json-conf")).andExpect(jsonPath("$[0]").value("external.json"))
                .andExpect(jsonPath("$[1]").value("notes.json")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/json-conf/external.json"))
                .andExpect(jsonPath("$.content").value("invalid external JSON"));
        mvc.perform(put("/api/json-conf/notes.json").contentType("application/json").content(payload("notes.json", "[1, null]")))
                .andExpect(status().isOk());
        assertEquals("[1, null]", Files.readString(directory.resolve("notes.json")));
        mvc.perform(delete("/api/json-conf/notes.json")).andExpect(status().isNoContent());
        assertFalse(Files.exists(directory.resolve("notes.json")));
        mvc.perform(get("/api/json-conf/notes.json")).andExpect(status().isNotFound());
        mvc.perform(put("/api/json-conf/missing.json").contentType("application/json").content(payload("missing.json", "{}")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ref-docs/notes.json")).andExpect(status().isBadRequest());
    }

    @Test
    void invalidJsonNeverCreatesOrOverwritesFilesAndAllJsonValuesAreAccepted() throws Exception {
        mvc.perform(post("/api/json-conf").contentType("application/json").content(payload("valid.json", "{}")))
                .andExpect(status().isCreated());
        for (String content : new String[]{"", "   ", "{", "{\"x\":}", "{} {}", "null true", "// comment\n{}", "{\"x\":1,}"}) {
            mvc.perform(post("/api/json-conf").contentType("application/json").content(payload("invalid.json", content)))
                    .andExpect(status().isBadRequest());
            mvc.perform(put("/api/json-conf/valid.json").contentType("application/json").content(payload("valid.json", content)))
                    .andExpect(status().isBadRequest());
            assertFalse(Files.exists(directory.resolve("invalid.json")));
            assertEquals("{}", Files.readString(directory.resolve("valid.json")));
        }
        for (String content : new String[]{"null", "true", "42", "\"text\"", "[]"}) {
            mvc.perform(put("/api/json-conf/valid.json").contentType("application/json").content(payload("valid.json", content)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void rejectsInvalidNamesMissingContentAndSymlinks() throws Exception {
        for (String name : new String[]{"../escape.json", "/tmp/escape.json", "nested/file.json", "bad.md", "bad\\file.json"}) {
            mvc.perform(post("/api/json-conf").contentType("application/json").content(payload(name, "{}")))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/json-conf").contentType("application/json").content("{\"name\":\"empty.json\"}"))
                .andExpect(status().isBadRequest());
        Path outside = Files.createTempFile("windlass-json-outside-", ".json");
        try {
            Files.writeString(outside, "{}");
            Files.createSymbolicLink(directory.resolve("link.json"), outside);
            mvc.perform(get("/api/json-conf/link.json")).andExpect(status().isNotFound());
            mvc.perform(put("/api/json-conf/link.json").contentType("application/json").content(payload("link.json", "[]")))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/api/json-conf/link.json")).andExpect(status().isNotFound());
            assertEquals("{}", Files.readString(outside));
        } finally { Files.deleteIfExists(outside); }
    }
}
