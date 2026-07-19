package org.languagetool.sidecar;

/** Metadata and classifier prompt for one independently reported LLM rule. */
record PolicyDefinition(
    String ruleId,
    String shortMessage,
    String description,
    String categoryId,
    String categoryName,
    String prompt) {

  static PolicyDefinition cats(String prompt) {
    return new PolicyDefinition(
        "CATS_LLM",
        "Cat reference",
        "Cat reference policy",
        "LLM_POLICY",
        "LLM policy",
        prompt);
  }

  static PolicyDefinition flowers(String prompt) {
    return new PolicyDefinition(
        "FLOWERS_LLM",
        "Flower reference",
        "Flower reference policy",
        "LLM_POLICY",
        "LLM policy",
        prompt);
  }
}
