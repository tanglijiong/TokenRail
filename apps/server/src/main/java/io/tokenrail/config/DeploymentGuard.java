package io.tokenrail.config;

import io.tokenrail.gateway.BlockchainGateway;
import java.math.BigInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** RPC checks run outside database transactions. An identity mismatch is latched. */
@Component
public class DeploymentGuard {
    private final JdbcTemplate jdbc;
    private final BlockchainGateway chain;
    private final TokenRailProperties properties;

    public DeploymentGuard(JdbcTemplate jdbc, BlockchainGateway chain, TokenRailProperties properties) {
        this.jdbc = jdbc; this.chain = chain; this.properties = properties;
    }

    public void initialize() {
        long block = properties.deploymentBlock();
        String hash = chain.blockHash(block);
        chain.totalSupplyAt(properties.depositTokenAddress(), chain.headBlockNumber());
        jdbc.update("""
                update deployment set anchor_block = ?, anchor_hash = ?
                where deployment_id = ? and anchor_hash is null
                """, block, hash, properties.deploymentId());
        verify();
    }

    public void verify() {
        var identities = jdbc.query("""
                select active, anchor_block, anchor_hash from deployment where deployment_id = ?
                """, (r, n) -> new Identity(r.getBoolean(1), r.getLong(2), r.getString(3)), properties.deploymentId());
        if (identities.isEmpty() || !identities.getFirst().active() || identities.getFirst().hash() == null)
            throw new IllegalStateException("Deployment is uninitialized or suspended");
        Identity identity = identities.getFirst();
        boolean matches = BigInteger.valueOf(properties.chainId()).equals(chain.chainId())
                && properties.genesisHash().equalsIgnoreCase(chain.genesisHash());
        try {
            matches &= identity.hash().equalsIgnoreCase(chain.blockHash(identity.block()));
            var checkpoints = jdbc.query("""
                    select block_number, block_hash from indexer_checkpoint
                    where deployment_id = ? and status = 'ACTIVE'
                    """, (r, n) -> new Identity(true, r.getLong(1), r.getString(2)), properties.deploymentId());
            for (Identity checkpoint : checkpoints)
                matches &= checkpoint.hash().equalsIgnoreCase(chain.blockHash(checkpoint.block()));
        } catch (BlockchainGateway.SnapshotUnavailableException unavailable) {
            // Fail closed for this pass; an RPC outage alone must not permanently suspend a deployment.
            throw new IllegalStateException("Cannot verify deployment anchor/checkpoint", unavailable);
        }
        if (!matches) {
            jdbc.update("update deployment set active = false where deployment_id = ?", properties.deploymentId());
            throw new IllegalStateException("Chain identity changed; deployment suspended");
        }
    }

    private record Identity(boolean active, long block, String hash) { }
}
