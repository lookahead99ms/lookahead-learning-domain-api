package com.lookahead.domain.review.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Local store on a private directory (a named Docker volume in Local). Versions are SHA-256 digests,
 * conditional writes are serialized by one lock (a single Domain process owns the directory), and
 * every write replaces the file atomically so a crash never leaves a half-written day file.
 */
public class FileSystemPlatformReviewObjects implements PlatformReviewObjects {
    private final Path root;
    private final ReentrantLock lock = new ReentrantLock();

    public FileSystemPlatformReviewObjects(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override public Optional<Stored> read(String key) {
        Path file = resolve(key);
        try {
            byte[] bytes = Files.readAllBytes(file);
            return Optional.of(new Stored(bytes, version(bytes)));
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override public boolean write(String key, byte[] bytes, String expectedVersion) {
        lock.lock();
        try {
            String current = read(key).map(Stored::version).orElse(null);
            if (!Objects.equals(current, expectedVersion)) return false;
            replace(resolve(key), bytes);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override public void put(String key, byte[] bytes) {
        replace(resolve(key), bytes);
    }

    @Override public List<String> list(String prefix) {
        Path directory = resolve(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix);
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> Files.isRegularFile(file) && !file.getFileName().toString().startsWith(".")).map(file -> root.relativize(file).toString().replace('\\', '/')).toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Recursive; a file that vanishes while listing (another writer's temporary file being moved into place) is skipped. */
    @Override public List<String> listAll(String prefix) {
        Path directory = resolve(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix);
        if (!Files.isDirectory(directory)) return List.of();
        var keys = new ArrayList<String>();
        walk(directory, keys);
        return List.copyOf(keys);
    }

    private void walk(Path directory, List<String> keys) {
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                if (entry.getFileName().toString().startsWith(".")) continue;
                if (Files.isDirectory(entry)) walk(entry, keys);
                else if (Files.isRegularFile(entry)) keys.add(root.relativize(entry).toString().replace('\\', '/'));
            }
        } catch (NoSuchFileException vanished) {
            // A folder removed between list and read; nothing to add.
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override public void delete(String key) {
        Path file = resolve(key);
        try {
            Files.deleteIfExists(file);
            Path parent = file.getParent();
            // Image folders are per item; remove an emptied one so deleted items leave nothing behind.
            if (parent != null && !parent.equals(root) && parent.getParent() != null && !parent.getParent().equals(root)) {
                try (Stream<Path> rest = Files.list(parent)) {
                    if (rest.findAny().isEmpty()) Files.deleteIfExists(parent);
                }
            }
        } catch (DirectoryNotEmptyException raced) {
            // Another write added a file; the folder stays.
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private Path resolve(String key) {
        if (key == null || !key.matches(PlatformReviewObjects.KEY)) throw new IllegalArgumentException("Invalid storage key");
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return file;
    }

    private static void replace(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".write-", ".tmp");
            try {
                restrict(temporary);
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void restrict(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // Non-POSIX development file systems keep their default permissions.
        }
    }

    static String version(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
