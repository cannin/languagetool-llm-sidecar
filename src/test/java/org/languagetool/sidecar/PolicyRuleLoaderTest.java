package org.languagetool.sidecar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PolicyRuleLoaderTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void loadsAnExternalRuleWithoutJavaChanges() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath();
    Path rulesDirectory = Files.createDirectory(directory.resolve("rules"));
    Files.writeString(rulesDirectory.resolve("astronomy-prompt.txt"), """
        Classify references to astronomy and return the required JSON object.
        """);
    Files.writeString(rulesDirectory.resolve("astronomy.properties"), """
        id = ASTRONOMY_LLM
        shortMessage = Astronomy reference
        description = Astronomy reference policy
        promptFile = astronomy-prompt.txt
        """);
    Path config = directory.resolve("sidecar.properties");
    Files.writeString(config, """
        llm.enabled = false
        llm.disabledRules = CATS_LLM, FLOWERS_LLM
        llm.rulesDirectory = rules
        """);

    Settings settings = Settings.load(config);

    assertEquals(1, settings.policies().size());
    PolicyDefinition rule = settings.policies().get(0);
    assertEquals("ASTRONOMY_LLM", rule.ruleId());
    assertEquals("Astronomy reference", rule.shortMessage());
    assertEquals("LLM_POLICY", rule.categoryId());
    assertTrue(rule.prompt().contains("references to astronomy"));
  }

  @Test
  public void rejectsDuplicateRuleIds() throws Exception {
    Path directory = temporaryFolder.newFolder().toPath();
    Path rulesDirectory = Files.createDirectory(directory.resolve("rules"));
    Files.writeString(rulesDirectory.resolve("duplicate-prompt.txt"), "prompt");
    Files.writeString(rulesDirectory.resolve("duplicate.properties"), """
        id = CATS_LLM
        shortMessage = Duplicate
        description = Duplicate rule
        promptFile = duplicate-prompt.txt
        """);
    Path config = directory.resolve("sidecar.properties");
    Files.writeString(config, """
        llm.enabled = false
        llm.rulesDirectory = rules
        """);

    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class, () -> Settings.load(config));

    assertTrue(error.getMessage().contains("Duplicate LLM rule id"));
  }
}
