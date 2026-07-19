package org.languagetool.sidecar;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Loads built-in and user-supplied LLM rule descriptors without Java code changes. */
final class PolicyRuleLoader {

  private static final String BUILT_IN_DIRECTORY = "/llm-rules/";
  private static final String BUILT_IN_INDEX = BUILT_IN_DIRECTORY + "index.txt";
  private static final Pattern RULE_ID = Pattern.compile("[A-Z][A-Z0-9_]*_LLM");

  private PolicyRuleLoader() {}

  static List<PolicyDefinition> load(Properties settings, Path configDirectory)
      throws IOException {
    List<PolicyDefinition> rules = new ArrayList<>();
    Set<String> disabledRuleIds = disabledRuleIds(settings);
    loadBuiltInRules(disabledRuleIds, rules);
    loadExternalRules(settings, configDirectory, disabledRuleIds, rules);
    validateUniqueIds(rules);
    return List.copyOf(rules);
  }

  private static void loadBuiltInRules(
      Set<String> disabledRuleIds,
      List<PolicyDefinition> rules) throws IOException {
    String index = readResource(BUILT_IN_INDEX);
    for (String rawLine : index.split("\\R")) {
      String descriptorName = rawLine.trim();
      if (descriptorName.isEmpty() || descriptorName.startsWith("#")) {
        continue;
      }
      String descriptorResource = BUILT_IN_DIRECTORY + descriptorName;
      Properties descriptor = readResourceProperties(descriptorResource);
      if (!booleanValue(descriptor, "enabled", true, descriptorResource)
          || disabledRuleIds.contains(required(descriptor, "id", descriptorResource))) {
        continue;
      }
      String prompt = readBuiltInPrompt(descriptor, descriptorResource);
      rules.add(toDefinition(descriptor, prompt, descriptorResource));
    }
  }

  private static void loadExternalRules(
      Properties settings,
      Path configDirectory,
      Set<String> disabledRuleIds,
      List<PolicyDefinition> rules) throws IOException {
    String configuredDirectory = settings.getProperty("llm.rulesDirectory", "rules").trim();
    if (configuredDirectory.isEmpty()) {
      return;
    }
    Path rulesDirectory = resolvePath(configDirectory, configuredDirectory);
    if (!Files.exists(rulesDirectory)) {
      return;
    }
    if (!Files.isDirectory(rulesDirectory)) {
      throw new IllegalArgumentException("LLM rules path is not a directory: " + rulesDirectory);
    }

    List<Path> descriptors;
    try (Stream<Path> files = Files.list(rulesDirectory)) {
      descriptors = files
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".properties"))
          .sorted()
          .toList();
    }
    for (Path descriptorPath : descriptors) {
      Properties descriptor = new Properties();
      try (Reader reader = Files.newBufferedReader(descriptorPath, StandardCharsets.UTF_8)) {
        descriptor.load(reader);
      }
      if (!booleanValue(descriptor, "enabled", true, descriptorPath.toString())) {
        continue;
      }
      if (disabledRuleIds.contains(required(descriptor, "id", descriptorPath.toString()))) {
        continue;
      }
      String promptFile = required(descriptor, "promptFile", descriptorPath.toString());
      Path promptPath = resolvePath(descriptorPath.getParent(), promptFile);
      String prompt = Files.readString(promptPath, StandardCharsets.UTF_8).trim();
      rules.add(toDefinition(descriptor, prompt, descriptorPath.toString()));
    }
  }

  private static PolicyDefinition toDefinition(
      Properties descriptor, String prompt, String source) {
    String ruleId = required(descriptor, "id", source);
    if (!RULE_ID.matcher(ruleId).matches()) {
      throw new IllegalArgumentException(
          "Rule id must match " + RULE_ID.pattern() + " in " + source + ": " + ruleId);
    }
    if (prompt.isBlank()) {
      throw new IllegalArgumentException("Rule prompt is empty in " + source);
    }
    return new PolicyDefinition(
        ruleId,
        required(descriptor, "shortMessage", source),
        required(descriptor, "description", source),
        descriptor.getProperty("categoryId", "LLM_POLICY").trim(),
        descriptor.getProperty("categoryName", "LLM policy").trim(),
        prompt);
  }

  private static String readBuiltInPrompt(Properties descriptor, String descriptorResource)
      throws IOException {
    String promptFile = required(descriptor, "promptFile", descriptorResource);
    String promptResource = promptFile.startsWith("/")
        ? promptFile
        : BUILT_IN_DIRECTORY + promptFile;
    return readResource(promptResource).trim();
  }

  private static Properties readResourceProperties(String resource) throws IOException {
    Properties properties = new Properties();
    try (InputStream input = PolicyRuleLoader.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IOException("Embedded LLM rule descriptor is missing: " + resource);
      }
      properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
    }
    return properties;
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream input = PolicyRuleLoader.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IOException("Embedded LLM rule resource is missing: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void validateUniqueIds(List<PolicyDefinition> rules) {
    Set<String> ids = new HashSet<>();
    for (PolicyDefinition rule : rules) {
      if (!ids.add(rule.ruleId())) {
        throw new IllegalArgumentException("Duplicate LLM rule id: " + rule.ruleId());
      }
    }
  }

  private static Set<String> disabledRuleIds(Properties settings) {
    Set<String> ruleIds = new HashSet<>();
    String configured = settings.getProperty("llm.disabledRules", "").trim();
    if (configured.isEmpty()) {
      return ruleIds;
    }
    for (String ruleId : configured.split("[,\\s]+")) {
      if (!RULE_ID.matcher(ruleId).matches()) {
        throw new IllegalArgumentException(
            "Disabled rule id must match " + RULE_ID.pattern() + ": " + ruleId);
      }
      ruleIds.add(ruleId);
    }
    return ruleIds;
  }

  private static String required(Properties properties, String name, String source) {
    String value = properties.getProperty(name, "").trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Required rule setting " + name + " is empty in " + source);
    }
    return value;
  }

  private static boolean booleanValue(
      Properties properties, String name, boolean defaultValue, String source) {
    String value = properties.getProperty(name, Boolean.toString(defaultValue)).trim().toLowerCase();
    if (!value.equals("true") && !value.equals("false")) {
      throw new IllegalArgumentException(
          "Rule setting " + name + " must be true or false in " + source);
    }
    return Boolean.parseBoolean(value);
  }

  private static Path resolvePath(Path base, String value) {
    Path path = Path.of(value);
    return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
  }
}
