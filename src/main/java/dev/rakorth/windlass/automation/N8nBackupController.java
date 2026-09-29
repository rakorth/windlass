package dev.rakorth.windlass.automation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/automations/backups")
public class N8nBackupController {
    private final N8nBackupClient client;

    public N8nBackupController(N8nBackupClient client) { this.client = client; }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(client.status());
    }

    @PostMapping
    public ResponseEntity<StreamingResponseBody> backup(HttpServletRequest request) {
        checkRequest(request);
        String filename = "n8n-" + Instant.now().toString().replace(":", "-") + ".zip";
        return download(client.backup(), filename);
    }

    @GetMapping("/recovery")
    public ResponseEntity<StreamingResponseBody> recovery(HttpServletRequest request) {
        checkRequest(request);
        return download(client.recovery(), "n8n-recovery.zip");
    }

    @PostMapping(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> restore(HttpServletRequest request,
            @RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "") String confirm) throws IOException {
        checkRequest(request);
        if (!"RESTORE".equals(confirm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirm that restoring will replace the current n8n data.");
        }
        if (file.isEmpty() || file.getSize() > N8nBackupClient.MAX_UPLOAD) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Choose a non-empty backup ZIP no larger than 512 MiB.");
        }
        var temporary = Files.createTempFile("windlass-n8n-restore-", ".zip");
        try {
            file.transferTo(temporary);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(client.restore(temporary));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // Browser cross-origin forms cannot supply this header; no CORS permissions
    // are granted. Require a deliberate same-origin fetch for sensitive backups.
    private void checkRequest(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (!"1".equals(request.getHeader("X-Windlass-Backup"))
                || (site != null && !site.equals("same-origin") && !site.equals("none"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Use the Windlass backups page for this operation.");
        }
    }

    private ResponseEntity<StreamingResponseBody> download(HttpResponse<InputStream> upstream, String filename) {
        StreamingResponseBody body = output -> {
            try (var input = upstream.body()) { input.transferTo(output); }
        };
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header("X-Content-Type-Options", "nosniff");
        upstream.headers().firstValueAsLong("Content-Length").ifPresent(response::contentLength);
        return response.body(body);
    }
}
