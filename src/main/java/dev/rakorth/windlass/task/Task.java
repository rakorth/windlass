package dev.rakorth.windlass.task;

import java.util.Map;

public record Task(String id, String name, String description,
                   Map<String, String> links, Map<String, Object> metadata) {}
