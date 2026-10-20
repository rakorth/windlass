package dev.rakorth.windlass.notification;

import java.util.List;

public record NotificationDashboard(long total, long unread, List<SourceCount> sources,
                                    List<DayCount> activity, List<Notification> recentUnread) {
    public record SourceCount(String source, long total, long unread) {}
    public record DayCount(String day, long total) {}
}
