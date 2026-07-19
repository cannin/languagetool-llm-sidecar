package org.languagetool.sidecar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SettingsTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void loadsOptionalPropertiesOverEmbeddedDefaults() throws Exception {
    Path temporaryDirectory = temporaryFolder.newFolder().toPath();
    Path config = temporaryDirectory.resolve("custom.properties");
    Files.writeString(config, """
        sidecar.host = 127.0.0.1
        sidecar.port = 50052
        llm.enabled = false
        llm.failOpen = false
        """);

    Settings settings = Settings.load(config);

    assertEquals("127.0.0.1", settings.host());
    assertEquals(50052, settings.port());
    assertFalse(settings.llmEnabled());
    assertFalse(settings.failOpen());
    assertEquals(2, settings.policies().size());
    assertEquals("CATS_LLM", settings.policies().get(0).ruleId());
    assertEquals("FLOWERS_LLM", settings.policies().get(1).ruleId());
    assertTrue(settings.policies().get(1).prompt().contains("reference to flowers"));
  }
}
