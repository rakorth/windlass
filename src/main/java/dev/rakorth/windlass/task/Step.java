package dev.rakorth.windlass.task;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.List;

public record Step(
        @NotBlank @Size(max = 200) String name,
        @NotNull StepStatus status,
        @JsonProperty(required = false) Map<String, Object> metadata,
        @JsonProperty(required = false) Map<@NotBlank String, @NotBlank String> links,
        @JsonProperty(required = false) List<@NotBlank String> webhooks) {
    public Step {
        webhooks = webhooks == null ? List.of() : webhooks;
    }
}
