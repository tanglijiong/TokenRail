package io.tokenrail.api;

import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.projection.Checkpoint;
import io.tokenrail.projection.Position;
import io.tokenrail.projection.ProjectionRepository;
import io.tokenrail.projection.TransferIndexer;
import io.tokenrail.reconciliation.ReconciliationReport;
import io.tokenrail.reconciliation.ReconciliationService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ProjectionController {
    private final AuthenticationService authentication;
    private final ProjectionRepository projections;
    private final TransferIndexer indexer;
    private final ReconciliationService reconciliations;
    private final TokenRailProperties properties;

    public ProjectionController(
            AuthenticationService authentication,
            ProjectionRepository projections,
            TransferIndexer indexer,
            ReconciliationService reconciliations,
            TokenRailProperties properties) {
        this.authentication = authentication;
        this.projections = projections;
        this.indexer = indexer;
        this.reconciliations = reconciliations;
        this.properties = properties;
    }

    @org.springframework.beans.factory.annotation.Autowired private AdminJobs jobs;
    @org.springframework.beans.factory.annotation.Autowired private io.tokenrail.gateway.BlockchainGateway chain;

    public record PositionsView(String deploymentId, long blockNumber, String blockHash, int projectionVersion, List<Position> positions) { }

    @GetMapping("/positions")
    public PositionsView positions(@RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) String wallet) {
        String actor = authentication.authenticate(authorization);
        String selected = wallet;
        if (!authentication.isReconciler(actor)) {
            String own = authentication.wallet(actor);
            if (wallet != null && !wallet.equalsIgnoreCase(own)) throw new ForbiddenException("Wallet belongs to another participant");
            selected = own;
        }
        Checkpoint cp = projections.activeCheckpoint(properties.deploymentId()).orElseThrow();
        final String filter = selected;
        List<Position> positions = projections.snapshotPositions(cp.deploymentId(), cp.projectionVersion(), cp.blockNumber(), cp.blockHash())
                .stream().filter(p -> filter == null || p.walletAddress().equalsIgnoreCase(filter)).toList();
        return new PositionsView(cp.deploymentId(), cp.blockNumber(), cp.blockHash(), cp.projectionVersion(), positions);
    }

    @PostMapping("/reconciliations")
    public ResponseEntity<AdminJobs.Job> reconcile(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key, @RequestParam(required = false) Long block) {
        String actor = authentication.requireReconciler(authorization);
        long indexed = projections.activeCheckpoint(properties.deploymentId()).orElseThrow().blockNumber();
        return ResponseEntity.accepted().body(jobs.submit(actor, "RECONCILE", key, block, indexed));
    }

    @GetMapping("/reconciliations/{id}")
    public ReconciliationReport reconciliation(@RequestHeader("Authorization") String authorization, @PathVariable UUID id) {
        authentication.requireReconciler(authorization);
        ReconciliationReport report = reconciliations.get(id);
        if (!report.deploymentId().equals(properties.deploymentId())) throw new ForbiddenException("Report belongs to another deployment");
        return report;
    }

    @PostMapping("/projections/rebuild")
    public ResponseEntity<AdminJobs.Job> rebuild(@RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String key) {
        String actor = authentication.requireReconciler(authorization);
        return ResponseEntity.accepted().body(jobs.submit(actor, "REBUILD", key, null, chain.headBlockNumber()));
    }

    @GetMapping("/jobs/{id}")
    public AdminJobs.Job job(@RequestHeader("Authorization") String authorization, @PathVariable UUID id) {
        return jobs.get(id, authentication.requireReconciler(authorization));
    }

    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<AdminJobs.Job> retry(@RequestHeader("Authorization") String authorization, @PathVariable UUID id) {
        return ResponseEntity.accepted().body(jobs.retry(id, authentication.requireReconciler(authorization)));
    }
}
