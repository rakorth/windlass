package dev.rakorth.windlass.refdocs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

@Service
public class RefDocsService {
    private final Path directory;

    public RefDocsService(@Value("${windlass.ref-docs-directory}") String directory) throws IOException {
        this.directory = Files.createDirectories(Path.of(directory).toAbsolutePath().normalize()).toRealPath();
    }

    public record Document(String name, String content) {}

    private Path path(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,195}\\.md")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use a .md filename starting with a letter or digit, containing only letters, digits, spaces, dots, underscores or hyphens (maximum 199 characters).");
        }
        return directory.resolve(name);
    }

    public synchronized List<String> list() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .map(file -> file.getFileName().toString())
                    .filter(name -> name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]{0,195}\\.md"))
                    .sorted().toList();
        }
    }

    private void requireFile(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reference document not found.");
        }
    }

    public synchronized Document get(String name) throws IOException {
        Path file = path(name);
        requireFile(file);
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return new Document(name, new String(java.nio.channels.Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public synchronized Document save(String name, String content, boolean create) throws IOException {
        Path file = path(name);
        if (content == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content is required; use an empty string for an empty file.");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (create) {
            try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
            } catch (FileAlreadyExistsException exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A file with that name already exists.");
            }
        } else {
            requireFile(file);
            Path temporary = Files.createTempFile(directory, ".windlass-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return new Document(name, content);
    }

    public synchronized void delete(String name) throws IOException {
        Path file = path(name);
        requireFile(file);
        Files.delete(file);
    }
}
