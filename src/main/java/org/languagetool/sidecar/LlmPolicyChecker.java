package org.languagetool.sidecar;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** OpenAI-compatible sentence classifier with bounded concurrency and a disk cache. */
final class LlmPolicyChecker implements PolicyChecker {

  private static final Set<String> REFERENCE_TYPES = Set.of("direct", "implied", "none");
  private static final Set<String> CONFIDENCE_VALUES = Set.of("high", "medium", "low");

  private final Settings settings;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final FileCache cache;
  private final Semaphore semaphore;
  private final ExecutorService executor;
  private final ConcurrentHashMap<String, CompletableFuture<Classification>> inFlight =
      new ConcurrentHashMap<>();

  LlmPolicyChecker(Settings settings, ObjectMapper objectMapper) {
    this.settings = settings;
    this.objectMapper = objectMapper;
    this.executor = Executors.newFixedThreadPool(settings.maxConcurrentRequests());
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(settings.requestTimeout())
        .build();
    this.cache = new FileCache(settings.cacheDirectory(), objectMapper);
    this.semaphore = new Semaphore(settings.maxConcurrentRequests());
  }

  @Override
  public CompletableFuture<List<Finding>> checkSentence(String text) {
    if (!settings.llmEnabled() || text.isBlank()) {
      return CompletableFuture.completedFuture(List.of());
    }
    Sentence sentence = trimSentence(text);
    if (sentence.text().length() < settings.minimumSentenceCharacters()) {
      return CompletableFuture.completedFuture(List.of());
    }
    List<CompletableFuture<Finding>> futures = settings.policies().stream()
        .map(policy -> checkPolicy(sentence, policy))
        .toList();
    CompletableFuture<?>[] all = futures.toArray(CompletableFuture[]::new);
    return CompletableFuture.allOf(all).thenApply(ignored -> futures.stream()
        .map(CompletableFuture::join)
        .filter(finding -> finding != null)
        .toList());
  }

  private CompletableFuture<Finding> checkPolicy(
      Sentence sentence, PolicyDefinition policy) {
    String cacheKey = cacheKey(sentence.text(), policy);
    if (cache.hasRecentError(cacheKey, settings.errorCacheTtlSeconds())) {
      return CompletableFuture.completedFuture(null);
    }
    return classify(sentence.text(), policy, cacheKey).handle((classification, failure) -> {
      if (failure != null) {
        cache.putError(cacheKey);
        throw new CompletionException(failure);
      }
      if (!classification.flagged()) {
        return null;
      }
      Sentence matchSpan = findMatchSpan(sentence, classification.matches());
      return new Finding(matchSpan, classification, policy, "llm:" + sha256(cacheKey));
    });
  }

  static Sentence findMatchSpan(Sentence sentence, List<String> reportedMatches) {
    String normalizedSentence = sentence.text().toLowerCase(Locale.ROOT);
    for (String reportedMatch : reportedMatches) {
      String candidate = reportedMatch.trim();
      if (candidate.isEmpty()) {
        continue;
      }
      int relativeStart = normalizedSentence.indexOf(candidate.toLowerCase(Locale.ROOT));
      if (relativeStart >= 0) {
        int start = sentence.start() + relativeStart;
        int end = start + candidate.length();
        return new Sentence(sentence.text().substring(
            relativeStart, relativeStart + candidate.length()), start, end);
      }
    }
    return sentence;
  }

  private CompletableFuture<Classification> classify(
      String sentence, PolicyDefinition policy, String cacheKey) {
    var stored = cache.getClassification(cacheKey, settings.cacheTtlSeconds());
    if (stored.isPresent()) {
      return CompletableFuture.completedFuture(stored.get());
    }

    CompletableFuture<Classification> candidate = CompletableFuture.supplyAsync(() -> {
      try {
        Classification classification = fetchClassification(sentence, policy);
        cache.putClassification(cacheKey, classification);
        return classification;
      } catch (IOException | InterruptedException exception) {
        if (exception instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        throw new CompletionException(exception);
      }
    }, executor);
    CompletableFuture<Classification> existing = inFlight.putIfAbsent(cacheKey, candidate);
    if (existing != null) {
      candidate.cancel(false);
      return existing;
    }
    candidate.whenComplete((result, failure) -> inFlight.remove(cacheKey, candidate));
    return candidate;
  }

  private Classification fetchClassification(String sentence, PolicyDefinition policy)
      throws IOException, InterruptedException {
    ObjectNode requestBody = objectMapper.createObjectNode();
    requestBody.put("model", settings.llmModel());
    ArrayNode messages = requestBody.putArray("messages");
    messages.addObject().put("role", "system").put("content", policy.prompt());
    messages.addObject()
        .put("role", "user")
        .put("content", "Classify this sentence:\n\n<SENTENCE>\n" + sentence + "\n</SENTENCE>");
    requestBody.putObject("response_format").put("type", "json_object");

    URI endpoint = URI.create(settings.llmApiBase() + "/chat/completions");
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .timeout(settings.requestTimeout())
        .header("Authorization", "Bearer " + settings.llmApiKey())
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
        .build();

    semaphore.acquire();
    try {
      HttpResponse<String> response = httpClient.send(
          request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() >= 400) {
        throw new IOException("LiteLLM returned HTTP " + response.statusCode());
      }
      JsonNode payload = objectMapper.readTree(response.body());
      JsonNode content = payload.path("choices").path(0).path("message").path("content");
      if (!content.isTextual()) {
        throw new IOException("LiteLLM response does not contain message content");
      }
      return parseClassification(content.textValue(), objectMapper);
    } finally {
      semaphore.release();
    }
  }

  static Sentence trimSentence(String text) {
    int start = 0;
    int end = text.length();
    while (start < end && Character.isWhitespace(text.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
      end--;
    }
    return new Sentence(text.substring(start, end), start, end);
  }

  static Classification parseClassification(String content, ObjectMapper objectMapper)
      throws JsonProcessingException {
    String normalized = content.trim();
    if (normalized.startsWith("```")) {
      int firstNewline = normalized.indexOf('\n');
      normalized = firstNewline >= 0 ? normalized.substring(firstNewline + 1) : "";
      if (normalized.stripTrailing().endsWith("```")) {
        normalized = normalized.stripTrailing();
        normalized = normalized.substring(0, normalized.length() - 3).stripTrailing();
      }
    }
    JsonNode value = objectMapper.readTree(normalized);
    if (!value.isObject() || !value.path("flagged").isBoolean()) {
      throw new JsonProcessingException("LiteLLM response is missing a boolean flagged value") {};
    }

    boolean flagged = value.path("flagged").booleanValue();
    String referenceType = value.path("reference_type").asText("");
    if (!REFERENCE_TYPES.contains(referenceType)) {
      referenceType = flagged ? "implied" : "none";
    }
    String confidence = value.path("confidence").asText("");
    if (!CONFIDENCE_VALUES.contains(confidence)) {
      confidence = "low";
    }
    List<String> matches = new ArrayList<>();
    if (value.path("matches").isArray()) {
      value.path("matches").forEach(match -> {
        if (match.isTextual()) {
          matches.add(match.textValue());
        }
      });
    }
    String reason = value.path("reason").isTextual()
        ? value.path("reason").textValue()
        : "Policy reference detected.";
    return new Classification(flagged, referenceType, confidence, List.copyOf(matches), reason);
  }

  private String cacheKey(String sentence, PolicyDefinition policy) {
    ArrayNode keyParts = objectMapper.createArrayNode();
    keyParts.add(settings.llmApiBase().toString());
    keyParts.add(settings.llmModel());
    keyParts.add(policy.ruleId());
    keyParts.add(policy.prompt());
    keyParts.add(sentence);
    try {
      return sha256(objectMapper.writeValueAsString(keyParts));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not construct cache key", exception);
    }
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
