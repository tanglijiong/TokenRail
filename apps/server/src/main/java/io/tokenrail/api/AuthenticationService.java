package io.tokenrail.api;

import io.tokenrail.ledger.WorkflowRepository;
import io.tokenrail.support.Hashes;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
    private final WorkflowRepository repository;

    public AuthenticationService(WorkflowRepository repository) {
        this.repository = repository;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${tokenrail.deployment-id}")
    private String deployment;

    public boolean isReconciler(String actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select role = 'RECONCILER' or exists(
                    select 1 from participant_role r where r.deployment_id = p.deployment_id
                    and r.participant_id = p.participant_id and r.role = 'RECONCILER')
                from deployment_participant p where deployment_id = ? and participant_id = ?
                """, Boolean.class, deployment, actor));
    }

    public boolean hasRole(String actor, String role) {
        if ("RECONCILER".equals(role) && isReconciler(actor)) return true;
        Integer count = jdbc.queryForObject("""
                select count(*) from participant_role
                where deployment_id = ? and participant_id = ? and role = ?
                """, Integer.class, deployment, actor, role);
        return count != null && count > 0;
    }

    public String requireRole(String authorization, String role) {
        String actor = authenticate(authorization);
        if (!hasRole(actor, role)) throw new ForbiddenException(role + " role is required");
        return actor;
    }

    public String requireReconciler(String authorization) {
        String actor = authenticate(authorization);
        if (!isReconciler(actor)) throw new ForbiddenException("Reconciler role is required");
        return actor;
    }

    public String wallet(String actor) {
        return jdbc.queryForObject("select wallet_address from deployment_participant where deployment_id = ? and participant_id = ?", String.class, deployment, actor);
    }

    public String authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ForbiddenException("Bearer token is required");
        }
        return repository.findParticipantByTokenHash(Hashes.sha256(authorization.substring(7)))
                .orElseThrow(() -> new ForbiddenException("Invalid bearer token"));
    }
}
