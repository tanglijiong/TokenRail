package io.tokenrail.config;

import io.tokenrail.gateway.BlockchainGateway;
import io.tokenrail.ledger.WorkflowRepository;
import io.tokenrail.projection.ProjectionRepository;
import java.math.BigInteger;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class RuntimeBootstrap implements ApplicationRunner {
    @org.springframework.beans.factory.annotation.Autowired
    private io.tokenrail.config.DeploymentGuard guard;
    private final TokenRailProperties properties;
    private final BlockchainGateway chain;
    private final WorkflowRepository repository;
    private final ProjectionRepository projections;
    @org.springframework.beans.factory.annotation.Value("${tokenrail.reconciler-participants:}")
    private java.util.List<String> reconcilers;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public RuntimeBootstrap(
            TokenRailProperties properties,
            BlockchainGateway chain,
            WorkflowRepository repository,
            ProjectionRepository projections) {
        this.properties = properties;
        this.chain = chain;
        this.repository = repository;
        this.projections = projections;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (blank(properties.deploymentId()) || blank(properties.genesisHash())
                || blank(properties.depositTokenAddress())) {
            throw new IllegalStateException("Deployment ID, genesis hash, and deposit token address are required");
        }
        if (!properties.genesisHash().equalsIgnoreCase(chain.genesisHash())) {
            throw new IllegalStateException("Configured deployment belongs to a different genesis block");
        }
        if (!BigInteger.valueOf(properties.chainId()).equals(chain.chainId())) {
            throw new IllegalStateException("Configured chain ID does not match the RPC node");
        }
        repository.registerDeployment(properties.deploymentId(), properties.chainId(),
                properties.genesisHash(), properties.depositTokenAddress());
        repository.registerContract(properties.deploymentId(), properties.identityRegistryAddress(), "REGISTRY");
        repository.registerContract(properties.deploymentId(), properties.bondTokenAddress(), "BOND");
        repository.registerContract(properties.deploymentId(), properties.dvpAddress(), "DVP");
        repository.registerContract(properties.deploymentId(), properties.escrowAddress(), "ESCROW");
        guard.initialize();
        projections.registerAsset(properties.deploymentId(), properties.depositTokenAddress(),
                "TRCNY", 2, properties.deploymentBlock());
        projections.registerAsset(properties.deploymentId(), properties.bondTokenAddress(),
                "ABC2029", 0, properties.deploymentBlock());
        if (projections.activeCheckpoint(properties.deploymentId()).isEmpty()) {
            long initialBlock = Math.max(0, properties.deploymentBlock() - 1);
            projections.initializeProjection(properties.deploymentId(), 1, initialBlock,
                    chain.blockHash(initialBlock));
        }
        for (Map.Entry<String, String> entry : properties.demoTokens().entrySet()) {
            String participant = entry.getValue();
            String prefix = "TOKENRAIL_PARTICIPANT_" + participant.replace('-', '_').toUpperCase();
            String wallet = System.getenv(prefix + "_ADDRESS");
            String keyFile = System.getenv(prefix + "_KEY_FILE");
            if (!blank(wallet) && !blank(keyFile)) {
                BigInteger opening = new BigInteger(System.getenv().getOrDefault(
                        prefix + "_OPENING_DEPOSIT", "1000000000"));
                repository.registerParticipant(participant, wallet, entry.getKey(), keyFile, opening);
                for (String role : System.getenv().getOrDefault(prefix + "_ROLES", "").split(",")) {
                    if (!role.isBlank()) jdbc.update("insert into participant_role values (?, ?, ?) on conflict do nothing", properties.deploymentId(), participant, role.trim());
                }
            }
        }
        for (String actor : reconcilers) {
            if (!actor.isBlank()) jdbc.update("update deployment_participant set role = 'RECONCILER' where deployment_id = ? and participant_id = ?",
                    properties.deploymentId(), actor);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
