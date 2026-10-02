package dev.rakorth.windlass.mcp;

import dev.rakorth.windlass.notification.Notification;
import dev.rakorth.windlass.notification.NotificationPage;
import dev.rakorth.windlass.notification.NotificationRequest;
import dev.rakorth.windlass.notification.NotificationService;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationTools {
    private static final Logger log = LoggerFactory.getLogger(NotificationTools.class);
    private final NotificationService service;
    private final Validator validator;

    public NotificationTools(NotificationService service, Validator validator) {
        this.service = service;
        this.validator = validator;
    }

    @McpTool(name = "list_notifications", description = "List notifications newest first. Page is zero-based; size is 1–100. Omit unread for all notifications. Reading does not mark seen.",
            generateOutputSchema = true, annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public NotificationPage list(
            @McpToolParam(description = "Zero-based page; defaults to 0", required = false) Integer page,
            @McpToolParam(description = "Page size from 1 to 100; defaults to 20", required = false) Integer size,
            @McpToolParam(description = "True for unread, false for seen; omit for both", required = false) Boolean unread) {
        return invoke(() -> service.list(page == null ? 0 : page, size == null ? 20 : size, unread));
    }

    @McpTool(name = "get_notification", description = "Get a notification by its actual ID. Reading does not mark it seen.",
            generateOutputSchema = true, annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public Notification get(@McpToolParam(description = "Notification ID", required = true) String id) {
        return invoke(() -> service.get(id));
    }

    @McpTool(name = "create_notification", description = "Create an unread notification. Title and notification_source are required. Create is not idempotent; inspect state before retrying an uncertain outcome.",
            generateOutputSchema = true, annotations = @McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public Notification create(@McpToolParam(description = "Writable notification fields; exclude id and unread", required = true) NotificationRequest request) {
        return invoke(() -> service.create(validate(request)));
    }

    @McpTool(name = "update_notification", description = "Replace editable notification content. Fetch current values first to preserve fields. Omitted description and maps are cleared; omitted received_on is preserved. Unread state is preserved.",
            generateOutputSchema = true, annotations = @McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public Notification update(
            @McpToolParam(description = "Notification ID", required = true) String id,
            @McpToolParam(description = "Complete replacement of writable fields; title and notification_source are required", required = true) NotificationRequest request) {
        return invoke(() -> service.update(id, validate(request)));
    }

    @McpTool(name = "mark_notification_seen", description = "Explicitly mark a notification seen. Repeated calls on an existing notification succeed.",
            generateOutputSchema = true, annotations = @McpAnnotations(destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Notification markSeen(@McpToolParam(description = "Notification ID", required = true) String id) {
        return invoke(() -> service.markSeen(id));
    }

    @McpTool(name = "delete_notification", description = "Permanently delete a notification by its actual ID. Repeating deletion of a missing record returns an error.",
            generateOutputSchema = true, annotations = @McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public DeleteResult delete(@McpToolParam(description = "Notification ID", required = true) String id) {
        return invoke(() -> {
            service.delete(id);
            return new DeleteResult(id, true);
        });
    }

    private NotificationRequest validate(NotificationRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                    .sorted().collect(Collectors.joining("; ")));
        }
        return request;
    }

    private <T> T invoke(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ResponseStatusException exception) {
            throw new IllegalArgumentException(exception.getReason() == null ? "Notification operation failed" : exception.getReason());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error("MCP notification operation failed", exception);
            throw new IllegalStateException("Notification operation failed; inspect state before retrying a mutation");
        }
    }

    public record DeleteResult(String id, boolean deleted) {}
}
