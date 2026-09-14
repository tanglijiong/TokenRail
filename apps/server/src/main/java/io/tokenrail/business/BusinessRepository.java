package io.tokenrail.business;

import io.tokenrail.api.ConflictException;
import io.tokenrail.api.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessRepository {
    public record Resource(String deploymentId, String id, String kind, String creatorId,
                           String terms, UUID latestOperationId, String operationStatus,
                           String transactionHash, Long minedBlock, String minedHash) { }

    private final JdbcTemplate jdbc;

    public BusinessRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insertResource(String deployment, String id, String kind, String creator, String terms) {
        jdbc.update("""
                insert into business_resource(deployment_id, resource_id, resource_kind, creator_id, terms)
                values (?, ?, ?, ?, ?::jsonb)
                """, deployment, normalize(id), kind, creator, terms);
    }

    public Resource get(String deployment, String kind, String id) {
        List<Resource> resources = jdbc.query("""
                select r.deployment_id, r.resource_id, r.resource_kind, r.creator_id, r.terms::text,
                    o.operation_id, o.status, o.tx_hash, t.mined_block, t.mined_hash
                from business_resource r
                left join lateral (
                    select o.* from business_command c join deposit_operation o on o.operation_id = c.operation_id
                    where c.deployment_id = r.deployment_id and c.resource_id = r.resource_id
                    order by o.created_at desc limit 1
                ) o on true
                left join chain_transaction t on t.operation_id = o.operation_id
                where r.deployment_id = ? and r.resource_kind = ? and r.resource_id = ?
                """, (result, row) -> new Resource(
                result.getString(1), result.getString(2), result.getString(3), result.getString(4),
                result.getString(5), result.getObject(6, UUID.class), result.getString(7),
                result.getString(8), result.getObject(9) == null ? null : result.getLong(9),
                result.getString(10)), deployment, kind, normalize(id));
        if (resources.isEmpty()) throw new NotFoundException(kind + " not found");
        return resources.getFirst();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> terms(String deployment, String kind, String id) {
        try {
            return new tools.jackson.databind.ObjectMapper().readValue(
                    get(deployment, kind, id).terms(), Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored resource terms are invalid", exception);
        }
    }

    public void assertNoPending(String deployment, String resource, String action) {
        Integer count = jdbc.queryForObject("""
                select count(*) from business_command where deployment_id = ? and resource_id = ?
                    and action = ? and pending
                """, Integer.class, deployment, normalize(resource), action);
        if (count != null && count > 0) throw new ConflictException("The action is already pending");
    }

    public List<Map<String, Object>> recentOperations(String deployment, String actor, int limit) {
        return jdbc.queryForList("""
                select o.operation_id, coalesce(c.resource_id, o.business_id) resource_id,
                    o.kind, o.status, o.tx_hash, o.error_code, o.created_at,
                    c.action, c.resource_kind, t.mined_block, t.mined_hash
                from deposit_operation o left join business_command c on c.operation_id = o.operation_id
                left join chain_transaction t on t.operation_id = o.operation_id
                where o.deployment_id = ? and o.participant_id = ?
                order by o.created_at desc limit ?
                """, deployment, actor, Math.min(Math.max(limit, 1), 100));
    }

    private static String normalize(String value) {
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
