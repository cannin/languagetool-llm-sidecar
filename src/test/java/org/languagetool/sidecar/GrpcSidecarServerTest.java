package org.languagetool.sidecar;

import static org.junit.Assert.assertEquals;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.languagetool.rules.ml.MLServerGrpc;
import org.languagetool.rules.ml.MLServerProto;

public class GrpcSidecarServerTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void returnsAlignedDynamicRuleMatches() throws Exception {
    Path temporaryDirectory = temporaryFolder.newFolder().toPath();
    Settings settings = settings(temporaryDirectory);
    PolicyChecker checker = sentence -> {
      if (!sentence.contains("tabby cat")) {
        return CompletableFuture.completedFuture(List.of());
      }
      PolicyChecker.Sentence span = new PolicyChecker.Sentence(sentence, 0, sentence.length());
      PolicyChecker.Classification cats = new PolicyChecker.Classification(
          true, "direct", "high", List.of("tabby cat"), "Cat reference.");
      PolicyChecker.Classification flowers = new PolicyChecker.Classification(
          true, "direct", "high", List.of("roses"), "Flower reference.");
      return CompletableFuture.completedFuture(List.of(
          new PolicyChecker.Finding(
              span, cats, PolicyDefinition.cats("cats-prompt"), "llm:cats"),
          new PolicyChecker.Finding(
              span, flowers, PolicyDefinition.flowers("flowers-prompt"), "llm:flowers")));
    };

    try (GrpcSidecarServer server = new GrpcSidecarServer(settings, checker)) {
      server.start();
      ManagedChannel channel = ManagedChannelBuilder
          .forAddress("127.0.0.1", server.port())
          .usePlaintext()
          .build();
      try {
        MLServerProto.MatchResponse response = MLServerGrpc.newBlockingStub(channel)
            .match(MLServerProto.MatchRequest.newBuilder()
                .addSentences("The tabby cat slept beside fresh roses.")
                .addSentences("Nothing relevant appears here.")
                .build());

        assertEquals(2, response.getSentenceMatchesCount());
        assertEquals(2, response.getSentenceMatches(0).getMatchesCount());
        assertEquals("CATS_LLM", response.getSentenceMatches(0).getMatches(0).getId());
        assertEquals("FLOWERS_LLM", response.getSentenceMatches(0).getMatches(1).getId());
        assertEquals("style", response.getSentenceMatches(0).getMatches(0)
            .getRule().getIssueType());
        assertEquals(0, response.getSentenceMatches(1).getMatchesCount());
      } finally {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  private Settings settings(Path cacheDirectory) {
    return new Settings(
        null,
        "127.0.0.1",
        0,
        true,
        URI.create("http://llm.invalid/v1"),
        "test-key",
        "test-model",
        List.of(
            PolicyDefinition.cats("cats-prompt"),
            PolicyDefinition.flowers("flowers-prompt")),
        1,
        Duration.ofSeconds(5),
        1,
        cacheDirectory,
        60,
        5,
        true);
  }
}
