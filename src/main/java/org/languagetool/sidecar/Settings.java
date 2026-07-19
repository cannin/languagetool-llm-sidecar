package org.languagetool.sidecar;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Immutable runtime settings loaded from embedded defaults, an optional file, and environment variables. */
public record Settings(
    Path configPath,
    String host,
    int port,
    boolean llmEnabled,
    URI llmApiBase,
    String llmApiKey,
    String llmModel,
    List<PolicyDefinition> policies,
    int minimumSentenceCharacters,
    Duration requestTimeout,
    int maxConcurrentRequests,
    Path cacheDirectory,
    long cacheTtlSeconds,
    long errorCacheTtlSeconds,
    boolean failOpen) {

  private static final Map<String, String> ENVIRONMENT_OVERRIDES = Map.ofEntries(
      Map.entry("LT_SIDECAR_HOST", "sidecar.host"),
      Map.entry("LT_SIDECAR_PORT", "sidecar.port"),
      Map.entry("LT_LLM_ENABLED", "llm.enabled"),
      Map.entry("LT_LLM_DISABLED_RULES", "llm.disabledRules"),
      Map.entry("LT_LLM_API_BASE", "llm.apiBase"),
      Map.entry("LT_LLM_RULES_DIRECTORY", "llm.rulesDirectory"),
      Map.entry("LT_LLM_MINIMUM_SENTENCE_CHARACTERS", "llm.minimumSentenceCharacters"),
      Map.entry("LT_LLM_REQUEST_TIMEOUT_SECONDS", "llm.requestTimeoutSeconds"),
      Map.entry("LT_LLM_MAX_CONCURRENT_REQUESTS", "llm.maxConcurrentRequests"),
      Map.entry("LT_LLM_CACHE_DIRECTORY", "llm.cacheDirectory"),
      Map.entry("LT_LLM_CACHE_TTL_SECONDS", "llm.cacheTtlSeconds"),
      Map.entry("LT_LLM_ERROR_CACHE_TTL_SECONDS", "llm.errorCacheTtlSeconds"),
      Map.entry("LT_LLM_FAIL_OPEN", "llm.failOpen"));

  /** Load settings. A null path means embedded defaults plus environment variables. */
  public static Settings load(Path requestedConfigPath) throws IOException {
    Path configPath = findConfigPath(requestedConfigPath);
    Path configDirectory = configPath == null
        ? Path.of("").toAbsolutePath().normalize()
        : configPath.toAbsolutePath().normalize().getParent();
    Properties properties = defaultProperties();
    if (configPath != null) {
      try (InputStream input = Files.newInputStream(configPath)) {
        properties.load(input);
      }
    }

    Map<String, String> dotenv = readDotenv(configDirectory.resolve(".env"));
    for (Map.Entry<String, String> override : ENVIRONMENT_OVERRIDES.entrySet()) {
      String value = environmentValue(override.getKey(), dotenv);
      if (value != null && !value.isBlank()) {
        properties.setProperty(override.getValue(), value.trim());
      }
    }

    String apiKeyVariable = properties.getProperty("llm.apiKeyEnv", "LT_LLM_API_KEY").trim();
    String apiKey = environmentValue(apiKeyVariable, dotenv);
    String llmModel = environmentValue("LT_LLM_MODEL", dotenv);
    boolean enabled = booleanValue(properties, "llm.enabled");
    List<PolicyDefinition> policies = PolicyRuleLoader.load(properties, configDirectory);
    if (enabled && !policies.isEmpty() && (apiKey == null || apiKey.isBlank())) {
      throw new IllegalArgumentException(
          "llm.enabled is true but " + apiKeyVariable + " is not set");
    }
    if (enabled && !policies.isEmpty() && (llmModel == null || llmModel.isBlank())) {
      throw new IllegalArgumentException(
          "llm.enabled is true but LT_LLM_MODEL is not set");
    }

    Path cacheDirectory = resolvePath(
        configDirectory, properties.getProperty("llm.cacheDirectory"));
    return new Settings(
        configPath,
        required(properties, "sidecar.host"),
        positiveInteger(properties, "sidecar.port"),
        enabled,
        URI.create(required(properties, "llm.apiBase").replaceAll("/+$", "")),
        apiKey == null ? "" : apiKey.trim(),
        llmModel == null ? "" : llmModel.trim(),
        policies,
        positiveInteger(properties, "llm.minimumSentenceCharacters"),
        Duration.ofSeconds(positiveInteger(properties, "llm.requestTimeoutSeconds")),
        positiveInteger(properties, "llm.maxConcurrentRequests"),
        cacheDirectory,
        positiveLong(properties, "llm.cacheTtlSeconds"),
        positiveLong(properties, "llm.errorCacheTtlSeconds"),
        booleanValue(properties, "llm.failOpen"));
  }

  private static Properties defaultProperties() {
    Properties properties = new Properties();
    properties.setProperty("sidecar.host", "0.0.0.0");
    properties.setProperty("sidecar.port", "50051");
    properties.setProperty("llm.enabled", "true");
    properties.setProperty("llm.disabledRules", "");
    properties.setProperty("llm.apiBase", "http://127.0.0.1:4000/v1");
    properties.setProperty("llm.apiKeyEnv", "LT_LLM_API_KEY");
    properties.setProperty("llm.rulesDirectory", "rules");
    properties.setProperty("llm.minimumSentenceCharacters", "20");
    properties.setProperty("llm.requestTimeoutSeconds", "30");
    properties.setProperty("llm.maxConcurrentRequests", "3");
    properties.setProperty(
        "llm.cacheDirectory",
        Path.of(System.getProperty("java.io.tmpdir"), "languagetool-llm-sidecar-cache").toString());
    properties.setProperty("llm.cacheTtlSeconds", "86400");
    properties.setProperty("llm.errorCacheTtlSeconds", "30");
    properties.setProperty("llm.failOpen", "true");
    return properties;
  }

  private static Path findConfigPath(Path requestedPath) {
    if (requestedPath != null) {
      Path resolved = requestedPath.toAbsolutePath().normalize();
      if (!Files.isRegularFile(resolved)) {
        throw new IllegalArgumentException("Configuration file does not exist: " + resolved);
      }
      return resolved;
    }
    String environmentPath = System.getenv("LT_SIDECAR_CONFIG");
    if (environmentPath != null && !environmentPath.isBlank()) {
      return findConfigPath(Path.of(environmentPath));
    }
    Path conventionalPath = Path.of("sidecar.properties").toAbsolutePath().normalize();
    return Files.isRegularFile(conventionalPath) ? conventionalPath : null;
  }

  private static Map<String, String> readDotenv(Path path) throws IOException {
    Map<String, String> values = new HashMap<>();
    if (!Files.isRegularFile(path)) {
      return values;
    }
    for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      if (line.startsWith("export ")) {
        line = line.substring(7).trim();
      }
      int separator = line.indexOf('=');
      if (separator <= 0) {
        continue;
      }
      String value = line.substring(separator + 1).trim();
      if (value.length() >= 2
          && ((value.startsWith("\"") && value.endsWith("\""))
              || (value.startsWith("'") && value.endsWith("'")))) {
        value = value.substring(1, value.length() - 1);
      }
      values.put(line.substring(0, separator).trim(), value);
    }
    return values;
  }

  private static String environmentValue(String name, Map<String, String> dotenv) {
    String value = System.getenv(name);
    return value != null ? value : dotenv.get(name);
  }

  private static String required(Properties properties, String name) {
    String value = properties.getProperty(name, "").trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Required setting is empty: " + name);
    }
    return value;
  }

  private static int positiveInteger(Properties properties, String name) {
    long value = positiveLong(properties, name);
    if (value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(name + " is too large");
    }
    return (int) value;
  }

  private static long positiveLong(Properties properties, String name) {
    try {
      long value = Long.parseLong(required(properties, name));
      if (value < 1) {
        throw new IllegalArgumentException(name + " must be at least 1");
      }
      return value;
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be an integer", exception);
    }
  }

  private static boolean booleanValue(Properties properties, String name) {
    String value = required(properties, name).toLowerCase();
    if (!value.equals("true") && !value.equals("false")) {
      throw new IllegalArgumentException(name + " must be true or false");
    }
    return Boolean.parseBoolean(value);
  }

  private static Path resolvePath(Path base, String value) {
    Path path = Path.of(value);
    return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
  }
}
