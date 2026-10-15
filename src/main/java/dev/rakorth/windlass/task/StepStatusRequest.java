package dev.rakorth.windlass.task;

import jakarta.validation.constraints.NotNull;

public record StepStatusRequest(@NotNull StepStatus status) {}
