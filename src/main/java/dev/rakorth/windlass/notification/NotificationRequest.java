package dev.rakorth.windlass.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public record NotificationRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 10000) String description,
        @NotBlank @Size(max = 200) String notification_source,
        Instant received_on,
        Map<String, Object> metadata_map,
        Map<@NotBlank String, @NotBlank String> external_links) {}
