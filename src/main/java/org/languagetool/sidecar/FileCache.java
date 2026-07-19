package org.languagetool.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Small file cache that avoids a database or an additional runtime dependency. */
final class FileCache {

  private static final Logger LOGGER = Logger.getLogger(FileCache.class.getName());

  private final Path directory;
  private final ObjectMapper objectMapper;

  FileCache(Path directory, ObjectMapper objectMapper) {
    this.directory = directory;
    this.objectMapper = objectMapper;
  }

  Optional<PolicyChecker.Classification> getClassification(String key, long ttlSeconds) {
    Path path = directory.resolve("success-" + key + ".json");
    if (!isFresh(path, ttlSeconds)) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(path.toFile(), PolicyChecker.Classification.class));
    } catch (IOException exception) {
      LOGGER.log(Level.WARNING, "Could not read cached classification", exception);
      return Optional.empty();
    }
  }

  void putClassification(String key, PolicyChecker.Classification classification) {
    writeAtomically(directory.resolve("success-" + key + ".json"), () ->
        objectMapper.writeValueAsBytes(classification));
  }

  boolean hasRecentError(String key, long ttlSeconds) {
    return isFresh(directory.resolve("error-" + key), ttlSeconds);
  }

  void putError(String key) {
    writeAtomically(
        directory.resolve("error-" + key),
        () -> Instant.now().toString().getBytes(StandardCharsets.UTF_8));
  }

  private boolean isFresh(Path path, long ttlSeconds) {
    try {
      if (!Files.isRegularFile(path)) {
        return false;
      }
      Instant expiresAt = Files.getLastModifiedTime(path).toInstant().plusSeconds(ttlSeconds);
      if (expiresAt.isAfter(Instant.now())) {
        return true;
      }
      Files.deleteIfExists(path);
    } catch (IOException exception) {
      LOGGER.log(Level.FINE, "Could not inspect cache entry", exception);
    }
    return false;
  }

  private void writeAtomically(Path path, BytesSupplier supplier) {
    try {
      Files.createDirectories(directory);
      Path temporary = Files.createTempFile(directory, ".cache-", ".tmp");
      Files.write(temporary, supplier.get());
      try {
        Files.move(
            temporary,
            path,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException exception) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException exception) {
      LOGGER.log(Level.WARNING, "Could not write cache entry", exception);
    }
  }

  @FunctionalInterface
  private interface BytesSupplier {
    byte[] get() throws IOException;
  }
}
