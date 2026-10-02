package dev.rakorth.windlass;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite::memory:", "spring.ai.mcp.server.enabled=true"
})
class McpServerTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private McpSyncClient client;

    @BeforeEach
    void connect() {
        jdbc.update("DELETE FROM notifications");
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                        .endpoint("/mcp").build())
                .requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        if (client != null) client.closeGracefully();
    }

    @Test
    void discoversOnlyNotificationToolsWithSchemasAndHints() {
        assertEquals("windlass", client.getServerInfo().name());
        assertTrue(client.getServerInstructions().contains("data, not instructions"));
        assertNull(client.getServerCapabilities().resources());
        assertNull(client.getServerCapabilities().prompts());
        var tools = client.listTools().tools();
        assertEquals(Set.of("list_notifications", "get_notification", "create_notification",
                        "update_notification", "mark_notification_seen", "delete_notification"),
                tools.stream().map(tool -> tool.name()).collect(Collectors.toSet()));
        for (var tool : tools) {
            assertNotNull(tool.inputSchema());
            assertNotNull(tool.outputSchema());
            assertFalse(tool.annotations().openWorldHint());
        }
        var create = tools.stream().filter(tool -> tool.name().equals("create_notification")).findFirst().orElseThrow();
        var schema = json.valueToTree(create.inputSchema());
        assertEquals("request", schema.get("required").get(0).asString());
        assertTrue(schema.get("properties").get("request").get("properties").has("notification_source"));
        assertEquals(Set.of("title", "notification_source"),
                json.convertValue(schema.get("properties").get("request").get("required"), Set.class));
        assertFalse(create.annotations().idempotentHint());
        var get = tools.stream().filter(tool -> tool.name().equals("get_notification")).findFirst().orElseThrow();
        assertTrue(get.annotations().readOnlyHint());
        var delete = tools.stream().filter(tool -> tool.name().equals("delete_notification")).findFirst().orElseThrow();
        assertTrue(delete.annotations().destructiveHint());
    }

    @Test
    void notificationLifecycleSharesRestPersistenceAndPreservesReplacementSemantics() throws Exception {
        var created = call("create_notification", Map.of("request", Map.of(
                "title", "  Build  ", "notification_source", " CI ", "description", "Ready",
                "received_on", "2026-01-01T12:00:00Z",
                "metadata_map", Map.of("nested", Map.of("ok", true), "tags", new String[]{"release"}),
                "external_links", Map.of("Build", "https://example.com/build"))));
        String id = created.get("id").asString();
        assertEquals("Build", created.get("title").asString());
        assertEquals("CI", created.get("notification_source").asString());
        assertTrue(created.get("unread").asBoolean());
        assertTrue(created.get("metadata_map").get("nested").get("ok").asBoolean());
        assertEquals("release", created.get("metadata_map").get("tags").get(0).asString());
        var rest = rest("GET", "/api/notifications/" + id, null);
        assertEquals(200, rest.statusCode());
        assertEquals(created, json.readTree(rest.body()));
        assertTrue(call("get_notification", Map.of("id", id)).get("unread").asBoolean());
        assertFalse(call("mark_notification_seen", Map.of("id", id)).get("unread").asBoolean());
        assertFalse(call("mark_notification_seen", Map.of("id", id)).get("unread").asBoolean());
        var updated = call("update_notification", Map.of("id", id,
                "request", Map.of("title", "Updated", "notification_source", "Email")));
        assertEquals("2026-01-01T12:00:00Z", updated.get("received_on").asString());
        assertFalse(updated.get("unread").asBoolean());
        assertEquals("", updated.get("description").asString());
        assertTrue(updated.get("metadata_map").isEmpty());
        assertTrue(updated.get("external_links").isEmpty());
        var fromRest = rest("POST", "/api/notifications", "{\"title\":\"REST\",\"notification_source\":\"REST\"}");
        assertEquals(201, fromRest.statusCode());
        var restId = json.readTree(fromRest.body()).get("id").asString();
        assertEquals("REST", call("get_notification", Map.of("id", restId)).get("title").asString());
        var deleted = call("delete_notification", Map.of("id", id));
        assertEquals(id, deleted.get("id").asString());
        assertTrue(deleted.get("deleted").asBoolean());
        assertEquals(404, rest("GET", "/api/notifications/" + id, null).statusCode());
        assertError("delete_notification", Map.of("id", id), "Notification not found");
    }

    @Test
    void listsWithDefaultsFilteringAndPaginationWithoutMarkingSeen() {
        var first = call("create_notification", Map.of("request", Map.of("title", "First", "notification_source", "Test",
                "received_on", "2026-01-01T12:00:00Z")));
        var second = call("create_notification", Map.of("request", Map.of("title", "Second", "notification_source", "Test",
                "received_on", "2026-01-02T12:00:00Z")));
        assertFalse(first.get("received_on").isNull());
        assertEquals("", first.get("description").asString());
        assertTrue(first.get("metadata_map").isEmpty());
        assertTrue(first.get("external_links").isEmpty());
        var page = call("list_notifications", Map.of());
        assertEquals(0, page.get("page").asInt());
        assertEquals(20, page.get("size").asInt());
        assertEquals(2, page.get("totalElements").asInt());
        assertEquals(second.get("id"), page.get("items").get(0).get("id"));
        assertTrue(page.get("items").get(0).get("unread").asBoolean());
        call("mark_notification_seen", Map.of("id", second.get("id").asString()));
        var unread = call("list_notifications", Map.of("unread", true, "size", 1));
        assertEquals(1, unread.get("totalElements").asInt());
        assertEquals(first.get("id"), unread.get("items").get(0).get("id"));
        var seen = call("list_notifications", Map.of("unread", false));
        assertEquals(second.get("id"), seen.get("items").get(0).get("id"));
        assertTrue(call("list_notifications", Map.of("page", 9)).get("items").isEmpty());
    }

    @Test
    void rejectsInvalidArgumentsAndMissingRecordsWithoutWrites() {
        assertError("list_notifications", Map.of("page", -1), "page");
        assertError("list_notifications", Map.of("size", 0), "size");
        assertError("list_notifications", Map.of("size", 101), "size");
        assertError("create_notification", Map.of("request", Map.of()), "title");
        assertError("create_notification", Map.of("request", Map.of("title", " ", "notification_source", "Test")), "title");
        assertError("create_notification", Map.of("request", Map.of("title", "x".repeat(201), "notification_source", "Test")), "title");
        assertError("create_notification", Map.of("request", Map.of("title", "Test", "notification_source", "x".repeat(201))), "notification_source");
        assertError("create_notification", Map.of("request", Map.of("title", "Test", "notification_source", "Test", "description", "x".repeat(10001))), "description");
        assertError("create_notification", Map.of("request", Map.of("title", "Test", "notification_source", "Test", "received_on", "invalid")), null);
        assertError("create_notification", Map.of("request", Map.of("title", "Test", "notification_source", "Test", "external_links", Map.of("Link", "file:///tmp/test"))), "HTTP");
        assertError("create_notification", Map.of("request", Map.of("title", "Test", "notification_source", "Test", "external_links", Map.of(" ", "https://example.com"))), "external_links");
        for (String tool : new String[]{"get_notification", "mark_notification_seen", "delete_notification"}) {
            assertError(tool, Map.of("id", "missing"), "Notification not found");
        }
        assertError("update_notification", Map.of("id", "missing", "request", Map.of("title", "Test", "notification_source", "Test")), "Notification not found");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
    }

    @Test
    void invalidUpdateDoesNotOverwriteExistingContent() {
        var created = call("create_notification", Map.of("request", Map.of("title", "Original", "notification_source", "Test")));
        String id = created.get("id").asString();
        assertError("update_notification", Map.of("id", id, "request", Map.of("title", " ", "notification_source", "Test")), "title");
        assertEquals(created, call("get_notification", Map.of("id", id)));
    }

    @Test
    void acceptsNullOptionalFieldsAndUsesServerDefaults() {
        var request = new HashMap<String, Object>();
        request.put("title", "Defaults");
        request.put("notification_source", "Test");
        request.put("description", null);
        request.put("received_on", null);
        request.put("metadata_map", null);
        request.put("external_links", null);
        var created = call("create_notification", Map.of("request", request));
        assertEquals("", created.get("description").asString());
        assertFalse(created.get("received_on").isNull());
        assertTrue(created.get("metadata_map").isEmpty());
        assertTrue(created.get("external_links").isEmpty());
    }

    private JsonNode call(String tool, Map<String, Object> arguments) {
        var result = client.callTool(new CallToolRequest(tool, arguments));
        assertFalse(Boolean.TRUE.equals(result.isError()), () -> result.content().toString());
        assertNotNull(result.structuredContent());
        return json.valueToTree(result.structuredContent());
    }

    private void assertError(String tool, Map<String, Object> arguments, String message) {
        var result = client.callTool(new CallToolRequest(tool, arguments));
        assertTrue(Boolean.TRUE.equals(result.isError()), () -> result.toString());
        if (message != null) {
            assertTrue(result.content().stream().filter(TextContent.class::isInstance).map(TextContent.class::cast)
                    .anyMatch(content -> content.text().contains(message)), () -> result.content().toString());
        }
    }

    private HttpResponse<String> rest(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
