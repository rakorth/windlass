package dev.rakorth.windlass.task;

import java.util.List;

public record TaskPage(List<Task> items, int page, int size,
                       long totalElements, long totalPages) {}
