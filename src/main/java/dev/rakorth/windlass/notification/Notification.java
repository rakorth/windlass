package dev.rakorth.windlass.notification;

import java.time.Instant;
import java.util.Map;

public record Notification(String id, String title, String description,
                           String notification_source, Instant received_on,
                           Map<String, Object> metadata_map, Map<String, String> external_links, boolean unread) {}
