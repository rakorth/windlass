package dev.rakorth.windlass;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
@AutoConfigureMockMvc
class N8nBackupTests {
    private static final String TOKEN = "test-only-manager-token";
    private static final byte[] ARCHIVE = new byte[]{80, 75, 3, 4, 0, 1, 2, 3};
    private static final AtomicInteger requests = new AtomicInteger();
    private static final AtomicInteger upstreamStatus = new AtomicInteger(200);
    private static final AtomicReference<String> upstreamPath = new AtomicReference<>();
    private static final AtomicReference<byte[]> uploaded = new AtomicReference<>();
    private static HttpServer helper;
    private static Path tokenFile;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        tokenFile = Files.createTempFile("windlass-manager-token-test-", ".txt");
        Files.writeString(tokenFile, TOKEN);
        helper = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        helper.createContext("/", exchange -> {
            requests.incrementAndGet();
            upstreamPath.set(exchange.getRequestURI().getPath());
            uploaded.set(exchange.getRequestBody().readAllBytes());
            int status = upstreamStatus.get();
            if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) status = 403;
            byte[] body;
            String path = exchange.getRequestURI().getPath();
            if (status != 200) body = "{\"message\":\"The backup uses a different n8n version.\"}".getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/backup") || path.equals("/recovery")) body = ARCHIVE;
            else if (path.equals("/restore")) body = "{\"message\":\"Backup restored. n8n is ready.\",\"recoveryAvailable\":true}".getBytes(StandardCharsets.UTF_8);
            else body = "{\"available\":true,\"phase\":\"ready\",\"n8nVersion\":\"2.41.3\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        helper.start();
        registry.add("windlass.n8n-backup-url", () -> "http://127.0.0.1:" + helper.getAddress().getPort());
        registry.add("windlass.n8n-backup-token-file", tokenFile::toString);
    }

    @BeforeEach
    void resetHelper() {
        upstreamStatus.set(200);
        requests.set(0);
        upstreamPath.set(null);
        uploaded.set(null);
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (helper != null) helper.stop(0);
        if (tokenFile != null) Files.deleteIfExists(tokenFile);
    }

    @Test
    void statusUsesPrivateTokenAndIsNotCached() throws Exception {
        mvc.perform(get("/api/automations/backups/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals("/status", upstreamPath.get());
    }

    @Test
    void backupStreamsDownloadWithoutExposingManagerToken() throws Exception {
        var result = mvc.perform(post("/api/automations/backups").header("X-Windlass-Backup", "1"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(content().bytes(ARCHIVE))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Authorization"))
                .andExpect(header().exists("Content-Disposition"));
        assertEquals("/backup", upstreamPath.get());
    }

    @Test
    void restoreRequiresConfirmationAndForwardsExactFile() throws Exception {
        var file = new MockMultipartFile("file", "backup.zip", "application/zip", ARCHIVE);
        mvc.perform(multipart("/api/automations/backups/restore").file(file)
                        .header("X-Windlass-Backup", "1"))
                .andExpect(status().isBadRequest());
        assertEquals(0, requests.get());
        mvc.perform(multipart("/api/automations/backups/restore").file(file)
                        .param("confirm", "RESTORE").header("X-Windlass-Backup", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recoveryAvailable").value(true));
        assertArrayEquals(ARCHIVE, uploaded.get());
        assertEquals("/restore", upstreamPath.get());
    }

    @Test
    void crossOriginAndUnmarkedRequestsCannotAccessBackups() throws Exception {
        mvc.perform(post("/api/automations/backups")).andExpect(status().isForbidden());
        mvc.perform(get("/api/automations/backups/recovery")).andExpect(status().isForbidden());
        mvc.perform(post("/api/automations/backups").header("X-Windlass-Backup", "1")
                        .header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/automations/backups/restore")
                        .file(new MockMultipartFile("file", ARCHIVE)).param("confirm", "RESTORE"))
                .andExpect(status().isForbidden());
        assertEquals(0, requests.get());
    }

    @Test
    void emptyUploadsNeverReachManager() throws Exception {
        mvc.perform(multipart("/api/automations/backups/restore")
                        .file(new MockMultipartFile("file", new byte[0]))
                        .param("confirm", "RESTORE").header("X-Windlass-Backup", "1"))
                .andExpect(status().isPayloadTooLarge());
        assertEquals(0, requests.get());
    }

    @Test
    void managerValidationAndBusyErrorsReachTheUi() throws Exception {
        upstreamStatus.set(409);
        mvc.perform(multipart("/api/automations/backups/restore")
                        .file(new MockMultipartFile("file", ARCHIVE))
                        .param("confirm", "RESTORE").header("X-Windlass-Backup", "1"))
                .andExpect(status().isConflict());
        upstreamStatus.set(503);
        mvc.perform(get("/api/automations/backups/status")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void recoveryIsADownloadAndBackupsPageIsServed() throws Exception {
        var result = mvc.perform(get("/api/automations/backups/recovery").header("X-Windlass-Backup", "1"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().bytes(ARCHIVE));
        assertEquals("/recovery", upstreamPath.get());
        mvc.perform(get("/backups.html")).andExpect(status().isOk());
        mvc.perform(get("/backups.js")).andExpect(status().isOk());
    }
}
