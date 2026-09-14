package io.tokenrail.reconciliation;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationFaults {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;
    private void requireTestProfile() {
        if (!environment.matchesProfiles("reliability-test", "local-demo"))
            throw new IllegalStateException("Fault tools require an explicit local-demo or reliability-test profile");
    }

    private final AtomicBoolean snapshotUnavailable = new AtomicBoolean();

    public void beforeHistoricalRead() {
        if (snapshotUnavailable.compareAndSet(true, false)) {
            throw new SnapshotFault("Injected historical snapshot unavailability");
        }
    }

    public void failNextHistoricalRead() {
        requireTestProfile();
        snapshotUnavailable.set(true);
    }

    public static final class SnapshotFault extends RuntimeException {
        public SnapshotFault(String message) {
            super(message);
        }
    }
}
