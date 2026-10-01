package dev.rakorth.windlass.notification;

import java.util.List;

public record NotificationPage(List<Notification> items, int page, int size,
                               long totalElements, long totalPages) {}
