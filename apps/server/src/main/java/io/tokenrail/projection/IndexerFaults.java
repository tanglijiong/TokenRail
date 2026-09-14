package io.tokenrail.projection;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class IndexerFaults {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;
    private void requireTestProfile() {
        if (!environment.matchesProfiles("reliability-test", "local-demo"))
            throw new IllegalStateException("Fault tools require an explicit local-demo or reliability-test profile");
    }

    private final AtomicBoolean beforeBlockCommit = new AtomicBoolean();

    public void beforeBlockCommit() {
        if (beforeBlockCommit.compareAndSet(true, false)) {
            throw new InjectedIndexerFailure("Injected failure before block commit");
        }
    }

    public void failBeforeNextBlockCommit() {
        requireTestProfile();
        beforeBlockCommit.set(true);
    }

    public static final class InjectedIndexerFailure extends RuntimeException {
        public InjectedIndexerFailure(String message) {
            super(message);
        }
    }
}
