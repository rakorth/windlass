package dev.rakorth.windlass.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class NotificationCleanup {
    private static final Logger log = LoggerFactory.getLogger(NotificationCleanup.class);
    private final NotificationService notifications;
    private final int retentionDays;

    public NotificationCleanup(NotificationService notifications,
                               @Value("${windlass.notification-retention-days:30}") int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("NOTIFICATION_RETENTION_DAYS must be a positive integer");
        }
        this.notifications = notifications;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void cleanup() {
        int deleted = notifications.deleteReadBefore(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        if (deleted > 0) log.info("Deleted {} read notifications older than {} days", deleted, retentionDays);
    }
}
