package dev.rakorth.windlass;

import dev.rakorth.windlass.mcp.NotificationTools;
import dev.rakorth.windlass.notification.NotificationService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationToolsTests {
    @Test
    void unexpectedPersistenceFailuresDoNotExposeDatabaseDetails() {
        var service = mock(NotificationService.class);
        when(service.get("test")).thenThrow(new DataAccessResourceFailureException("private database path and SQL"));
        var tools = new NotificationTools(service, mock(Validator.class));
        var error = assertThrows(IllegalStateException.class, () -> tools.get("test"));
        assertEquals("Notification operation failed; inspect state before retrying a mutation", error.getMessage());
        assertNull(error.getCause());
    }
}
