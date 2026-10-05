package dev.rakorth.windlass.task;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record TaskRequest(
        @NotBlank @Size(max = 200) String name,
        @JsonProperty(required = false) @Size(max = 10000) String description,
        @JsonProperty(required = false) Map<@NotBlank String, @NotBlank String> links,
        @JsonProperty(required = false) Map<String, Object> metadata,
        @JsonProperty(required = false) List<@NotNull @Valid Step> steps,
        @JsonProperty(required = false) TaskStatus status,
        @JsonProperty(required = false) Instant notifyMeOn) {}
