package dev.rakorth.windlass.notification;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping
    public NotificationPage list(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 @RequestParam(required = false) Boolean unread) {
        return service.list(page, size, unread);
    }

    @GetMapping("/{id}")
    public Notification get(@PathVariable String id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<Notification> create(@Valid @RequestBody NotificationRequest request) {
        var notification = service.create(request);
        return ResponseEntity.created(URI.create("/api/notifications/" + notification.id())).body(notification);
    }

    @PutMapping("/{id}")
    public Notification update(@PathVariable String id, @Valid @RequestBody NotificationRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/seen")
    public Notification markSeen(@PathVariable String id) {
        return service.markSeen(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
