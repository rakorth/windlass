package dev.rakorth.windlass.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

public record NotificationRequest(
        @NotBlank @Size(max = 200) String title,
        @JsonProperty(required = false) @Size(max = 10000) String description,
        @NotBlank @Size(max = 200) String notification_source,
        @JsonProperty(required = false) Instant received_on,
        @JsonProperty(required = false) Map<String, Object> metadata_map,
        @JsonProperty(required = false) Map<@NotBlank String, @NotBlank String> external_links) {}
