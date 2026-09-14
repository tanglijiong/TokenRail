package io.tokenrail.api;

import io.tokenrail.config.DeploymentGuard;
import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.projection.TransferIndexer;
import io.tokenrail.reconciliation.ReconciliationService;
import io.tokenrail.support.Hashes;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminJobs {
    public record Job(UUID jobId, String deploymentId, String actorId, String kind, long targetBlock,
                      String status, long claimVersion, String resultId, String error) { }
    @org.springframework.beans.factory.annotation.Value("${tokenrail.admin-lease-seconds:30}") private int leaseSeconds;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TokenRailProperties properties;
    private final TransferIndexer indexer;
    private final ReconciliationService reconciliations;
    private final DeploymentGuard guard;

    public AdminJobs(JdbcTemplate jdbc, PlatformTransactionManager manager, TokenRailProperties properties,
            TransferIndexer indexer, ReconciliationService reconciliations, DeploymentGuard guard) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.properties = properties;
        this.indexer = indexer; this.reconciliations = reconciliations; this.guard = guard;
    }

    public Job submit(String actor, String kind, String key, Long requestedBlock, long defaultBlock) {
        if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("Idempotency-Key is required (max 128 characters)");
        String hash = Hashes.sha256(kind + "|" + requestedBlock);
        long target = requestedBlock == null ? defaultBlock : requestedBlock;
        if (target < properties.deploymentBlock() || target > defaultBlock) throw new IllegalArgumentException("Invalid target block");
        guard.verify();
        return tx.execute(ignored -> {
            jdbc.update("""
                    insert into admin_job(job_id, deployment_id, actor_id, kind, idempotency_key, request_hash, target_block)
                    values (?, ?, ?, ?, ?, ?, ?) on conflict(deployment_id, actor_id, kind, idempotency_key) do nothing
                    """, UUID.randomUUID(), properties.deploymentId(), actor, kind, key, hash, target);
            var request = jdbc.queryForMap("select job_id, request_hash from admin_job where deployment_id = ? and actor_id = ? and kind = ? and idempotency_key = ?",
                    properties.deploymentId(), actor, kind, key);
            if (!hash.equals(request.get("request_hash"))) throw new ConflictException("Idempotency key payload conflict");
            return get((UUID) request.get("job_id"), actor);
        });
    }

    public Job get(UUID id, String actor) {
        Job job = jdbc.query("select * from admin_job where job_id = ? and deployment_id = ?", (r, n) -> new Job(
                r.getObject("job_id", UUID.class), r.getString("deployment_id"), r.getString("actor_id"), r.getString("kind"),
                r.getLong("target_block"), r.getString("status"), r.getLong("claim_version"), r.getString("result_id"), r.getString("error")),
                id, properties.deploymentId()).stream().findFirst().orElseThrow(() -> new NotFoundException("Job not found"));
        if (!job.actorId().equals(actor)) throw new ForbiddenException("Job belongs to another actor");
        return job;
    }

    public Job retry(UUID id, String actor) {
        get(id, actor);
        jdbc.update("update admin_job set status = 'PENDING', error = null where job_id = ? and status = 'FAILED'", id);
        return get(id, actor);
    }

    @Scheduled(fixedDelayString = "${tokenrail.admin-delay:1000}", initialDelay = 3000)
    public void scheduled() {
        if (!properties.workerEnabled()) return;
        try { processOne(); } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(AdminJobs.class).warn("Admin job pass stopped: {}", exception.getMessage());
        }
    }

    public boolean processOne() {
        guard.verify();
        Job job = tx.execute(ignored -> {
            var candidates = jdbc.query("""
                    select job_id, actor_id from admin_job where deployment_id = ?
                    and (status = 'PENDING' or (status = 'RUNNING' and lease_until <= clock_timestamp()))
                    order by created_at for update skip locked limit 1
                    """, (r, n) -> new Object[] {r.getObject(1, UUID.class), r.getString(2)}, properties.deploymentId());
            if (candidates.isEmpty()) return null;
            Object[] candidate = candidates.getFirst();
            jdbc.update("update admin_job set status = 'RUNNING', claim_version = claim_version + 1, lease_until = clock_timestamp() + (? * interval '1 second') where job_id = ?", Math.max(2, leaseSeconds), candidate[0]);
            return get((UUID) candidate[0], (String) candidate[1]);
        });
        if (job == null) return false;
        try (var heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()) {
            heartbeat.scheduleAtFixedRate(() -> jdbc.update("""
                    update admin_job set lease_until = clock_timestamp() + (? * interval '1 second')
                    where job_id = ? and claim_version = ? and status = 'RUNNING' and lease_until > clock_timestamp()
                    """, Math.max(2, leaseSeconds), job.jobId(), job.claimVersion()), 1, 1, java.util.concurrent.TimeUnit.SECONDS);
            try {
                String result = "REBUILD".equals(job.kind())
                        ? Integer.toString(indexer.rebuildTo(job.targetBlock(), job.jobId()).projectionVersion())
                        : reconciliations.reconcileAt(job.targetBlock(), job.jobId()).reconciliationId().toString();
                finish(job, "COMPLETED", result, null);
            } catch (RuntimeException exception) { finish(job, "FAILED", null, exception.getMessage()); }
            finally { heartbeat.shutdownNow(); }
        }
        return true;
    }

    private void finish(Job job, String status, String result, String error) {
        jdbc.update("""
                update admin_job set status = ?, result_id = ?, error = ? where job_id = ?
                  and status = 'RUNNING' and claim_version = ? and lease_until > clock_timestamp()
                """, status, result, error, job.jobId(), job.claimVersion());
    }
}
