package io.tokenrail.gateway;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class WorkflowFaults {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;
    private void requireTestProfile() {
        if (!environment.matchesProfiles("reliability-test", "local-demo"))
            throw new IllegalStateException("Fault tools require an explicit local-demo or reliability-test profile");
    }

    private final AtomicBoolean afterBroadcast = new AtomicBoolean();
    private final AtomicBoolean beforeConversionCommit = new AtomicBoolean();
    private final AtomicBoolean beforeRedemptionCredit = new AtomicBoolean();

    public void afterClaim() { }
    public void afterSigned() { }

    public void afterBroadcast() {
        trip(afterBroadcast, "Injected failure after broadcast");
    }

    public void beforeConversionCommit() {
        trip(beforeConversionCommit, "Injected conversion database failure");
    }

    public void beforeRedemptionCredit() {
        trip(beforeRedemptionCredit, "Injected redemption database failure");
    }

    public void failAfterNextBroadcast() {
        requireTestProfile();
        afterBroadcast.set(true);
    }

    public void failBeforeNextConversionCommit() {
        requireTestProfile();
        beforeConversionCommit.set(true);
    }

    public void failBeforeNextRedemptionCredit() {
        requireTestProfile();
        beforeRedemptionCredit.set(true);
    }

    private static void trip(AtomicBoolean fault, String message) {
        if (fault.compareAndSet(true, false)) {
            throw new InjectedFailure(message);
        }
    }

    public static final class InjectedFailure extends RuntimeException {
        public InjectedFailure(String message) {
            super(message);
        }
    }
}
