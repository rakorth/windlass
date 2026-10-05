package dev.rakorth.windlass.task;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/task-templates")
public class TaskTemplateController {
    private final TaskTemplateService service;
    private final TaskService tasks;

    public TaskTemplateController(TaskTemplateService service,
            @org.springframework.beans.factory.annotation.Qualifier("taskService") TaskService tasks) {
        this.service = service;
        this.tasks = tasks;
    }

    @PostMapping("/{id}/tasks")
    public ResponseEntity<Task> createTask(@PathVariable String id) {
        var template = service.get(id);
        var task = tasks.create(new TaskRequest(template.name(), template.description(), template.links(),
                template.metadata(), template.steps(), template.status(), template.notifyMeOn()));
        return ResponseEntity.created(URI.create("/api/tasks/" + task.id())).body(task);
    }

    @GetMapping
    public TaskPage list(@RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size,
                         @RequestParam(required = false) String search,
                         @RequestParam(required = false) TaskStatus status) {
        return service.list(page, size, search, status);
    }

    @GetMapping("/{id}")
    public Task get(@PathVariable String id) { return service.get(id); }

    @PostMapping
    public ResponseEntity<Task> create(@Valid @RequestBody TaskRequest request) {
        var task = service.create(request);
        return ResponseEntity.created(URI.create("/api/task-templates/" + task.id())).body(task);
    }

    @PutMapping("/{id}")
    public Task update(@PathVariable String id, @Valid @RequestBody TaskRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
