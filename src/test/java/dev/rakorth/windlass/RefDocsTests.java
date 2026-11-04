package dev.rakorth.windlass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;

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
        Files.writeString(directory.resolve(".ignored.txt"), "Hidden file");
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
    void binaryUploadPreservesBytesSupportsDownloadAndNeverOverwrites() throws Exception {
        byte[] bytes = new byte[]{0, 1, (byte) 255, (byte) 128, 13, 10};
        var file = new MockMultipartFile("file", "reference file.bin", "application/octet-stream", bytes);
        try {
            mvc.perform(multipart("/api/ref-docs/upload").file(file))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/ref-docs/reference%20file.bin/download"));
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("reference file.bin")));
            mvc.perform(get("/api/ref-docs")).andExpect(jsonPath("$[?(@ == 'reference file.bin')]").exists());
            mvc.perform(get("/api/ref-docs/reference file.bin/download"))
                    .andExpect(status().isOk()).andExpect(content().bytes(bytes))
                    .andExpect(content().contentType("application/octet-stream"))
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"reference file.bin\""));
            mvc.perform(multipart("/api/ref-docs/upload").file(
                    new MockMultipartFile("file", "reference file.bin", "text/plain", new byte[]{42})))
                    .andExpect(status().isConflict());
            assertArrayEquals(bytes, Files.readAllBytes(directory.resolve("reference file.bin")));
            mvc.perform(put("/api/ref-docs/reference file.bin").contentType("application/json")
                    .content("{\"content\":\"do not overwrite\"}")).andExpect(status().isBadRequest());
            mvc.perform(delete("/api/ref-docs/reference file.bin")).andExpect(status().isNoContent());
            mvc.perform(get("/api/ref-docs/reference file.bin/download")).andExpect(status().isNotFound());
        } finally { Files.deleteIfExists(directory.resolve("reference file.bin")); }
    }

    @Test
    void uploadAndDownloadRejectUnsafeNamesAndSymlinks() throws Exception {
        for (String name : new String[]{"../escape.bin", "/tmp/escape.bin", "nested/file.bin", "bad\\file.bin", ".hidden", "a".repeat(200), ""}) {
            mvc.perform(multipart("/api/ref-docs/upload").file(
                    new MockMultipartFile("file", name, "application/octet-stream", new byte[]{42})))
                    .andExpect(status().isBadRequest());
        }
        Path outside = Files.createTempFile("windlass-binary-", ".bin");
        Path link = directory.resolve("link.bin");
        try {
            Files.write(outside, new byte[]{7});
            Files.createSymbolicLink(link, outside);
            mvc.perform(multipart("/api/ref-docs/upload").file(
                    new MockMultipartFile("file", "link.bin", "application/octet-stream", new byte[]{42})))
                    .andExpect(status().isConflict());
            mvc.perform(get("/api/ref-docs/link.bin/download")).andExpect(status().isNotFound());
            mvc.perform(delete("/api/ref-docs/link.bin")).andExpect(status().isNotFound());
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(outside));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
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
