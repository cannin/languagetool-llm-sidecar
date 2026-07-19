package org.languagetool.sidecar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.Test;

public class LlmPolicyCheckerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  public void trimsNativeSentenceAndPreservesRelativeOffsets() {
    String text = "  The tabby cat slept beside roses.  ";

    PolicyChecker.Sentence sentence = LlmPolicyChecker.trimSentence(text);

    assertEquals("The tabby cat slept beside roses.", sentence.text());
    assertEquals(2, sentence.start());
    assertEquals(text.length() - 2, sentence.end());
  }

  @Test
  public void usesReportedPhraseAsMatchSpan() {
    PolicyChecker.Sentence sentence = new PolicyChecker.Sentence(
        "The tabby cat slept beside roses.", 2, 35);

    PolicyChecker.Sentence match = LlmPolicyChecker.findMatchSpan(
        sentence, List.of("TABBY CAT"));

    assertEquals("tabby cat", match.text());
    assertEquals(6, match.start());
    assertEquals(15, match.end());
  }

  @Test
  public void normalizesClassifierJson() throws Exception {
    PolicyChecker.Classification classification = LlmPolicyChecker.parseClassification(
        """
        ```json
        {"flagged":true,"reference_type":"unexpected","matches":["tabby"],"reason":"Cat reference."}
        ```
        """,
        objectMapper);

    assertTrue(classification.flagged());
    assertEquals("implied", classification.referenceType());
    assertEquals("low", classification.confidence());
    assertEquals(List.of("tabby"), classification.matches());
  }

  @Test
  public void parsesUnflaggedResponse() throws Exception {
    PolicyChecker.Classification classification = LlmPolicyChecker.parseClassification(
        """
        {"flagged":false,"reference_type":"none","confidence":"high","matches":[],"reason":"None."}
        """,
        objectMapper);

    assertFalse(classification.flagged());
    assertEquals("none", classification.referenceType());
  }
}
