package io.tokenrail;

import io.tokenrail.gateway.WorkflowFaults;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only subprocess. Parent sends SIGKILL after a persisted boundary marker. */
public class CrashWorkerMain {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{TokenRailApplication.class, CrashConfiguration.class}, args);
    }

    @TestConfiguration
    static class CrashConfiguration {
        @Bean @Primary io.tokenrail.projection.IndexerFaults crashIndexer() {
            return new io.tokenrail.projection.IndexerFaults() {
                @Override public void beforeBlockCommit() {
                    if (!"REBUILD".equals(System.getProperty("crash.stage"))) return;
                    try {
                        Files.writeString(Path.of(System.getProperty("crash.marker")), "REBUILD");
                        while (true) Thread.sleep(1000);
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }
            };
        }
        @Bean @Primary WorkflowFaults crashBoundary() {
            return new WorkflowFaults() {
                private void boundary(String stage) {
                    if (!stage.equals(System.getProperty("crash.stage"))) return;
                    try {
                        Files.writeString(Path.of(System.getProperty("crash.marker")), stage);
                        while (true) Thread.sleep(1000);
                    } catch (Exception exception) { throw new IllegalStateException(exception); }
                }
                @Override public void afterClaim() { boundary("CLAIMED"); }
                @Override public void afterSigned() { boundary("SIGNED"); }
                @Override public void afterBroadcast() { boundary("BROADCAST"); }
            };
        }
    }
}
