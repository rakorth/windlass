package dev.rakorth.windlass.automation;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AutomationController {
    private final URI editorUrl;

    public AutomationController(@Value("${windlass.automations-url}") URI editorUrl) {
        this.editorUrl = editorUrl;
    }

    @GetMapping("/automations")
    public ResponseEntity<Void> openEditor() {
        return ResponseEntity.status(HttpStatus.FOUND).location(editorUrl).build();
    }
}
