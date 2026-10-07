package dev.rakorth.windlass.task;

import dev.rakorth.windlass.notification.NotificationRequest;
import dev.rakorth.windlass.notification.NotificationService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TaskWebhookService {
    private final ObjectMapper json;
    private final NotificationService notifications;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public TaskWebhookService(ObjectMapper json, NotificationService notifications) {
        this.json = json;
        this.notifications = notifications;
    }

    public void statusChanged(Task previous, Task current) {
        if (previous.status() != current.status()) {
            deliver(current.webhooks(), current, null, previous.status().name(), current.status().name());
        }
        for (int i = 0; i < Math.min(previous.steps().size(), current.steps().size()); i++) {
            var before = previous.steps().get(i);
            var after = current.steps().get(i);
            if (before.name().equals(after.name()) && before.status() != after.status()) {
                deliver(after.webhooks(), current, i, before.status().name(), after.status().name());
            }
        }
    }

    private void deliver(List<String> urls, Task task, Integer stepIndex, String previousStatus, String status) {
        var event = new LinkedHashMap<String, Object>();
        event.put("event", stepIndex == null ? "task.status_changed" : "task_step.status_changed");
        event.put("occurredOn", Instant.now().toString());
        event.put("taskId", task.id());
        event.put("previousStatus", previousStatus);
        event.put("status", status);
        event.put("task", task);
        if (stepIndex != null) {
            event.put("stepIndex", stepIndex);
            event.put("step", task.steps().get(stepIndex));
        }
        String body = json.writeValueAsString(event);
        for (String url : urls) {
            String failure = null;
            Integer responseStatus = null;
            try {
                var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.discarding());
                responseStatus = response.statusCode();
                if (responseStatus < 200 || responseStatus >= 300) failure = "HTTP " + responseStatus;
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                failure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            }
            if (failure != null) {
                var details = new LinkedHashMap<String, Object>(event);
                details.put("webhookUrl", url);
                details.put("error", failure);
                if (responseStatus != null) details.put("httpStatus", responseStatus);
                notifications.create(new NotificationRequest("Task webhook delivery failed",
                        "Webhook " + url + " failed: " + failure, "task-webhooks", null,
                        details, Map.of("Webhook", url)));
            }
        }
    }
}
