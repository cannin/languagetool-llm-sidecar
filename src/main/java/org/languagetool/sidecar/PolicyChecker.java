package org.languagetool.sidecar;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Asynchronously checks one LanguageTool-segmented sentence for policy findings. */
interface PolicyChecker extends AutoCloseable {

  CompletableFuture<List<Finding>> checkSentence(String sentence);

  @Override
  default void close() {
    // Most test or embedded checkers do not own resources.
  }

  record Sentence(String text, int start, int end) {}

  record Classification(
      boolean flagged,
      String referenceType,
      String confidence,
      List<String> matches,
      String reason) {}

  record Finding(
      Sentence sentence,
      Classification classification,
      PolicyDefinition policy,
      String contextHash) {}
}
