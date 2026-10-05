package dev.rakorth.windlass.task;

import java.time.Instant;
import java.util.Map;
import java.util.List;

public record Task(String id, String name, String description, TaskStatus status,
                   Map<String, String> links, Map<String, Object> metadata, List<Step> steps, Instant notifyMeOn) {}
