package org.languagetool.sidecar;

import java.nio.file.Path;
import java.util.logging.Logger;

/** Command-line entry point for the standalone Java sidecar. */
public final class Main {

  private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

  private Main() {}

  public static void main(String[] args) throws Exception {
    Path configPath = parseConfigPath(args);
    Settings settings = Settings.load(configPath);
    GrpcSidecarServer server = new GrpcSidecarServer(settings);
    Runtime.getRuntime().addShutdownHook(new Thread(server::close, "sidecar-shutdown"));
    server.start();
    LOGGER.info(() -> "LanguageTool LLM gRPC sidecar started on "
        + settings.host() + ":" + server.port()
        + " with rules " + settings.policies().stream()
            .map(PolicyDefinition::ruleId)
            .toList());
    server.awaitTermination();
  }

  private static Path parseConfigPath(String[] args) {
    if (args.length == 0) {
      return null;
    }
    if (args.length == 1 && (args[0].equals("-h") || args[0].equals("--help"))) {
      System.out.println("Usage: java -jar languagetool-llm-sidecar.jar [-c FILE]");
      System.out.println("All settings are optional and may be supplied through environment variables.");
      System.exit(0);
    }
    if (args.length == 2 && (args[0].equals("-c") || args[0].equals("--config"))) {
      return Path.of(args[1]);
    }
    throw new IllegalArgumentException(
        "Usage: java -jar languagetool-llm-sidecar.jar [-c FILE]");
  }
}
