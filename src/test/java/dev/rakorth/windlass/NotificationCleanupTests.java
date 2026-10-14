package dev.rakorth.windlass;

import dev.rakorth.windlass.notification.NotificationCleanup;
import dev.rakorth.windlass.notification.NotificationRequest;
import dev.rakorth.windlass.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite::memory:")
class NotificationCleanupTests {
    @Autowired NotificationService notifications;
    @Autowired NotificationCleanup cleanup;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM notifications");
    }

    @Test
    void defaultCleanupDeletesOldReadNotificationsAndRetainsUnreadAndRecentRecords() {
        Instant now = Instant.now();
        String oldRead = create(now.minus(31, ChronoUnit.DAYS), true);
        String oldUnread = create(now.minus(60, ChronoUnit.DAYS), false);
        String recentRead = create(now.minus(29, ChronoUnit.DAYS), true);
        String restoredUnread = create(now.minus(60, ChronoUnit.DAYS), true);
        notifications.markUnread(restoredUnread);
        cleanup.cleanup();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE id = ?", Integer.class, oldRead));
        assertTrue(notifications.get(oldUnread).unread());
        assertFalse(notifications.get(recentRead).unread());
        assertTrue(notifications.get(restoredUnread).unread());
    }

    @Test
    void customRetentionAndRepeatedCleanupWork() {
        Instant now = Instant.now();
        create(now.minus(8, ChronoUnit.DAYS), true);
        String recent = create(now.minus(6, ChronoUnit.DAYS), true);
        var custom = new NotificationCleanup(notifications, 7);
        custom.cleanup();
        custom.cleanup();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
        assertFalse(notifications.get(recent).unread());
    }

    @Test
    void cutoffIsStrictAndHandlesFractionalSeconds() {
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        create(cutoff.minusMillis(1), true);
        String exact = create(cutoff, true);
        String after = create(cutoff.plusMillis(1), true);
        assertEquals(1, notifications.deleteReadBefore(cutoff));
        assertNotNull(notifications.get(exact));
        assertNotNull(notifications.get(after));
    }

    @Test
    void rejectsNonpositiveRetention() {
        for (int days : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> new NotificationCleanup(notifications, days));
        }
    }

    private String create(Instant receivedOn, boolean read) {
        String id = notifications.create(new NotificationRequest("Test", "", "cleanup-tests", receivedOn, null, null)).id();
        if (read) notifications.markSeen(id);
        return id;
    }
}
