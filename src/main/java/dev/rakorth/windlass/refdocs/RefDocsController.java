package dev.rakorth.windlass.refdocs;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/ref-docs")
public class RefDocsController {
    private final RefDocsService service;

    public RefDocsController(RefDocsService service) { this.service = service; }

    public record Request(String name, String content) {}

    @GetMapping
    public List<String> list() throws IOException { return service.list(); }

    @GetMapping("/{name}")
    public RefDocsService.Document get(@PathVariable String name) throws IOException { return service.get(name); }

    @PostMapping
    public ResponseEntity<RefDocsService.Document> create(@RequestBody Request request) throws IOException {
        var document = service.save(request.name(), request.content(), true);
        return ResponseEntity.created(UriComponentsBuilder.fromPath("/api/ref-docs/{name}")
                .buildAndExpand(document.name()).encode().toUri()).body(document);
    }

    @PutMapping("/{name}")
    public RefDocsService.Document update(@PathVariable String name, @RequestBody Request request) throws IOException {
        return service.save(name, request.content(), false);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) throws IOException {
        service.delete(name);
        return ResponseEntity.noContent().build();
    }
}
