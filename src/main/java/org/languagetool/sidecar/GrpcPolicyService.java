package org.languagetool.sidecar;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.languagetool.rules.ml.MLServerGrpc;
import org.languagetool.rules.ml.MLServerProto;

/** Implements LanguageTool's native remote-rule gRPC protocol. */
final class GrpcPolicyService extends MLServerGrpc.MLServerImplBase {

  private static final Logger LOGGER = Logger.getLogger(GrpcPolicyService.class.getName());

  private final Settings settings;
  private final PolicyChecker checker;

  GrpcPolicyService(Settings settings, PolicyChecker checker) {
    this.settings = settings;
    this.checker = checker;
  }

  @Override
  public void match(
      MLServerProto.MatchRequest request,
      StreamObserver<MLServerProto.MatchResponse> responseObserver) {
    checkSentences(request.getSentencesList(), responseObserver);
  }

  @Override
  public void matchAnalyzed(
      MLServerProto.AnalyzedMatchRequest request,
      StreamObserver<MLServerProto.MatchResponse> responseObserver) {
    checkSentences(
        request.getSentencesList().stream()
            .map(MLServerProto.AnalyzedSentence::getText)
            .toList(),
        responseObserver);
  }

  private void checkSentences(
      List<String> sentences,
      StreamObserver<MLServerProto.MatchResponse> responseObserver) {
    List<CompletableFuture<List<PolicyChecker.Finding>>> futures = sentences.stream()
        .map(checker::checkSentence)
        .toList();
    CompletableFuture<?>[] all = futures.toArray(CompletableFuture[]::new);
    CompletableFuture.allOf(all).whenComplete((ignored, failure) -> {
      if (failure != null) {
        handleFailure(sentences.size(), responseObserver, failure);
        return;
      }
      MLServerProto.MatchResponse.Builder response = MLServerProto.MatchResponse.newBuilder();
      futures.stream()
          .map(CompletableFuture::join)
          .map(this::toMatchList)
          .forEach(response::addSentenceMatches);
      responseObserver.onNext(response.build());
      responseObserver.onCompleted();
    });
  }

  private void handleFailure(
      int sentenceCount,
      StreamObserver<MLServerProto.MatchResponse> responseObserver,
      Throwable failure) {
    LOGGER.log(Level.WARNING, "LLM policy check failed", failure);
    if (!settings.failOpen()) {
      responseObserver.onError(Status.UNAVAILABLE
          .withDescription("LLM policy check failed")
          .withCause(failure)
          .asRuntimeException());
      return;
    }
    MLServerProto.MatchResponse.Builder response = MLServerProto.MatchResponse.newBuilder();
    for (int index = 0; index < sentenceCount; index++) {
      response.addSentenceMatches(MLServerProto.MatchList.getDefaultInstance());
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  private MLServerProto.MatchList toMatchList(List<PolicyChecker.Finding> findings) {
    MLServerProto.MatchList.Builder matches = MLServerProto.MatchList.newBuilder();
    findings.stream().map(this::toMatch).forEach(matches::addMatches);
    return matches.build();
  }

  private MLServerProto.Match toMatch(PolicyChecker.Finding finding) {
    PolicyChecker.Classification classification = finding.classification();
    PolicyDefinition policy = finding.policy();
    String message = classification.reason();
    if (!classification.matches().isEmpty()) {
      message += " Matched: " + String.join(", ", classification.matches()) + ".";
    }
    message += " Confidence: " + classification.confidence() + ".";

    MLServerProto.Rule rule = MLServerProto.Rule.newBuilder()
        .setIssueType("style")
        .setCategory(MLServerProto.RuleCategory.newBuilder()
            .setId(policy.categoryId())
            .setName(policy.categoryName()))
        .build();
    return MLServerProto.Match.newBuilder()
        .setOffset(finding.sentence().start())
        .setLength(finding.sentence().end() - finding.sentence().start())
        .setId(policy.ruleId())
        .setRuleDescription(policy.description())
        .setMatchDescription(message)
        .setMatchShortDescription(policy.shortMessage())
        .setType(MLServerProto.Match.MatchType.Hint)
        .setRule(rule)
        .build();
  }
}
