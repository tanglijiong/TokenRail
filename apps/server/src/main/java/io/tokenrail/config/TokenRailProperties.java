package io.tokenrail.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tokenrail")
public record TokenRailProperties(
        long chainId,
        String rpcUrl,
        String deploymentId,
        String genesisHash,
        String depositTokenAddress,
        String identityRegistryAddress,
        String bondTokenAddress,
        String dvpAddress,
        String escrowAddress,
        String issuerPrivateKeyFile,
        boolean workerEnabled,
        boolean indexerEnabled,
        long deploymentBlock,
        int indexerBatchSize,
        Map<String, String> demoTokens) {
}
