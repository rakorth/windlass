package dev.rakorth.windlass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
@AutoConfigureMockMvc
class RefDocsTests {
    @TempDir static Path directory;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("windlass.ref-docs-directory", () -> directory.toString());
    }

    @Test
    void crudUsesRealUtf8FilesAndDiscoversExternalFiles() throws Exception {
        mvc.perform(post("/api/ref-docs").contentType("application/json")
                .content("{\"name\":\"notes.md\",\"content\":\"# Hello 🌍\\n\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/ref-docs/notes.md"));
        assertEquals("# Hello 🌍\n", Files.readString(directory.resolve("notes.md")));
        mvc.perform(post("/api/ref-docs").contentType("application/json")
                .content("{\"name\":\"notes.md\",\"content\":\"overwrite\"}"))
                .andExpect(status().isConflict());
        Files.writeString(directory.resolve("external.md"), "From disk");
        Files.writeString(directory.resolve("ignored.txt"), "Not markdown");
        mvc.perform(get("/api/ref-docs")).andExpect(jsonPath("$[0]").value("external.md"))
                .andExpect(jsonPath("$[1]").value("notes.md")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/ref-docs/external.md")).andExpect(jsonPath("$.content").value("From disk"));
        mvc.perform(put("/api/ref-docs/notes.md").contentType("application/json").content("{\"content\":\"\"}"))
                .andExpect(status().isOk());
        assertEquals("", Files.readString(directory.resolve("notes.md")));
        mvc.perform(delete("/api/ref-docs/notes.md")).andExpect(status().isNoContent());
        assertFalse(Files.exists(directory.resolve("notes.md")));
        mvc.perform(get("/api/ref-docs/notes.md")).andExpect(status().isNotFound());
        mvc.perform(put("/api/ref-docs/missing.md").contentType("application/json").content("{\"content\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsTraversalInvalidNamesMissingContentAndSymlinks() throws Exception {
        for (String name : new String[]{"../escape.md", "/tmp/escape.md", "nested/file.md", "bad.txt", "bad\\\\file.md"}) {
            mvc.perform(post("/api/ref-docs").contentType("application/json")
                    .content("{\"name\":\"" + name + "\",\"content\":\"x\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/ref-docs").contentType("application/json").content("{\"name\":\"empty.md\"}"))
                .andExpect(status().isBadRequest());
        Path outside = Files.createTempFile("windlass-outside-", ".md");
        try {
            Files.writeString(outside, "Protected");
            Files.createSymbolicLink(directory.resolve("link.md"), outside);
            mvc.perform(get("/api/ref-docs/link.md")).andExpect(status().isNotFound());
            mvc.perform(put("/api/ref-docs/link.md").contentType("application/json").content("{\"content\":\"x\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/api/ref-docs/link.md")).andExpect(status().isNotFound());
            assertEquals("Protected", Files.readString(outside));
        } finally { Files.deleteIfExists(outside); }
    }
}
