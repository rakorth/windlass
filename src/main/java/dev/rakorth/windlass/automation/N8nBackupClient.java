package dev.rakorth.windlass.automation;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class N8nBackupClient {
    public static final long MAX_UPLOAD = 512L * 1024 * 1024;
    private final String baseUrl;
    private final Path tokenFile;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public N8nBackupClient(@Value("${windlass.n8n-backup-url:}") String baseUrl,
                          @Value("${windlass.n8n-backup-token-file:/n8n-control/token}") String tokenFile,
                          ObjectMapper json) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.tokenFile = Path.of(tokenFile);
        this.json = json;
    }

    public Map<String, Object> status() {
        if (baseUrl.isBlank()) return Map.of("available", false,
                "message", "n8n backups are available when Windlass runs with the backup-enabled Docker Compose setup.");
        return readJson(send("/status", "GET", HttpRequest.BodyPublishers.noBody()));
    }

    public HttpResponse<InputStream> backup() {
        return send("/backup", "POST", HttpRequest.BodyPublishers.noBody());
    }

    public HttpResponse<InputStream> recovery() {
        return send("/recovery", "GET", HttpRequest.BodyPublishers.noBody());
    }

    public Map<String, Object> restore(Path file) throws IOException {
        return readJson(send("/restore", "POST", HttpRequest.BodyPublishers.ofFile(file)));
    }

    private HttpResponse<InputStream> send(String path, String method, HttpRequest.BodyPublisher body) {
        if (baseUrl.isBlank()) throw unavailable();
        try {
            String token = Files.readString(tokenFile).strip();
            if (token.isEmpty()) throw unavailable();
            var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/zip")
                    .timeout(Duration.ofSeconds(path.equals("/status") ? 10 : 600))
                    .method(method, body).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String message = "The n8n backup manager could not complete the request.";
                try (var input = response.body()) {
                    var error = json.readTree(input.readNBytes(16384));
                    if (error.has("message")) message = error.get("message").asString();
                } catch (Exception ignored) { /* Keep the bounded, generic error message. */ }
                int status = response.statusCode();
                if (status < 400 || status > 599 || status == 401 || status == 403) status = 503;
                throw new ResponseStatusException(HttpStatusCode.valueOf(status), message);
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    private Map<String, Object> readJson(HttpResponse<InputStream> response) {
        try (var input = response.body()) {
            return json.readValue(input.readNBytes(16384), new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "The n8n backup manager is unavailable. Check the Compose services and refresh before retrying.");
    }
}
