package org.languagetool.sidecar;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/** Owns the standalone gRPC server and its policy checker resources. */
final class GrpcSidecarServer implements AutoCloseable {

  private final PolicyChecker checker;
  private final Server server;

  GrpcSidecarServer(Settings settings) {
    this(settings, new LlmPolicyChecker(settings, new com.fasterxml.jackson.databind.ObjectMapper()));
  }

  GrpcSidecarServer(Settings settings, PolicyChecker checker) {
    this.checker = checker;
    this.server = NettyServerBuilder
        .forAddress(new InetSocketAddress(settings.host(), settings.port()))
        .addService(new GrpcPolicyService(settings, checker))
        .build();
  }

  void start() throws IOException {
    server.start();
  }

  int port() {
    return server.getPort();
  }

  void awaitTermination() throws InterruptedException {
    server.awaitTermination();
  }

  @Override
  public void close() {
    server.shutdown();
    try {
      if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
        server.shutdownNow();
      }
    } catch (InterruptedException exception) {
      server.shutdownNow();
      Thread.currentThread().interrupt();
    } finally {
      checker.close();
    }
  }
}
