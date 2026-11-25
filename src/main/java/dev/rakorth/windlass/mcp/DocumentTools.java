package dev.rakorth.windlass.mcp;

import dev.rakorth.windlass.jsonconf.JsonConfService;
import dev.rakorth.windlass.refdocs.RefDocsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@Component
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentTools {
    private static final Logger log = LoggerFactory.getLogger(DocumentTools.class);
    private final RefDocsService refDocs;
    private final JsonConfService jsonConf;

    public DocumentTools(RefDocsService refDocs, JsonConfService jsonConf) {
        this.refDocs = refDocs;
        this.jsonConf = jsonConf;
    }

    @McpTool(name = "create_ref_doc", description = "Create a Markdown reference document. Fails if the filename already exists.",
            generateOutputSchema = true, annotations = @McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public RefDocsService.Document createRefDoc(
            @McpToolParam(description = "Markdown filename ending in .md", required = true) String name,
            @McpToolParam(description = "Raw Markdown text. An empty string creates an empty document.", required = true) String content) {
        return invoke(() -> refDocs.save(name, content, true));
    }

    @McpTool(name = "read_ref_doc", description = "Read a Markdown reference document by filename.",
            generateOutputSchema = true, annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public RefDocsService.Document readRefDoc(
            @McpToolParam(description = "Markdown filename ending in .md", required = true) String name) {
        return invoke(() -> refDocs.get(name));
    }

    @McpTool(name = "write_ref_doc", description = "Replace the full content of an existing Markdown reference document. Read it first to preserve existing text.",
            generateOutputSchema = true, annotations = @McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public RefDocsService.Document writeRefDoc(
            @McpToolParam(description = "Existing Markdown filename ending in .md", required = true) String name,
            @McpToolParam(description = "Complete replacement Markdown text", required = true) String content) {
        return invoke(() -> refDocs.save(name, content, false));
    }

    @McpTool(name = "create_json_doc", description = "Create a JSON configuration document. Fails if the filename already exists.",
            generateOutputSchema = true, annotations = @McpAnnotations(destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public JsonConfService.Document createJsonDoc(
            @McpToolParam(description = "Configuration filename ending in .json", required = true) String name,
            @McpToolParam(description = "Raw JSON text containing exactly one valid JSON value", required = true) String content) {
        return invoke(() -> jsonConf.save(name, content, true));
    }

    @McpTool(name = "read_json_doc", description = "Read a JSON configuration document as raw text by filename.",
            generateOutputSchema = true, annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public JsonConfService.Document readJsonDoc(
            @McpToolParam(description = "Configuration filename ending in .json", required = true) String name) {
        return invoke(() -> jsonConf.get(name));
    }

    @McpTool(name = "write_json_doc", description = "Replace the full content of an existing JSON configuration document. Read it first to preserve existing values.",
            generateOutputSchema = true, annotations = @McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public JsonConfService.Document writeJsonDoc(
            @McpToolParam(description = "Existing configuration filename ending in .json", required = true) String name,
            @McpToolParam(description = "Complete replacement JSON text containing exactly one valid JSON value", required = true) String content) {
        return invoke(() -> jsonConf.save(name, content, false));
    }

    private <T> T invoke(DocumentOperation<T> operation) {
        try {
            return operation.run();
        } catch (ResponseStatusException exception) {
            throw new IllegalArgumentException(exception.getReason());
        } catch (IOException exception) {
            log.error("MCP document operation failed", exception);
            throw new IllegalStateException("Document operation failed. Check the current content before retrying a write.");
        }
    }

    @FunctionalInterface
    private interface DocumentOperation<T> {
        T run() throws IOException;
    }
}
