package io.tokenrail.ledger;

import io.tokenrail.api.ConflictException;
import io.tokenrail.config.TokenRailProperties;
import io.tokenrail.gateway.BlockchainGateway;
import io.tokenrail.gateway.ChainTransaction;
import io.tokenrail.gateway.PreparedTransaction;
import io.tokenrail.gateway.WorkflowFaults;
import io.tokenrail.support.Hashes;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

@Service
public class DepositWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(DepositWorkflowService.class);
    private final WorkflowRepository repository;
    private final BlockchainGateway chain;
    @org.springframework.beans.factory.annotation.Autowired
    private io.tokenrail.config.DeploymentGuard guard;
    private final TokenRailProperties properties;
    private final WorkflowFaults faults;

    public DepositWorkflowService(
            WorkflowRepository repository,
            BlockchainGateway chain,
            TokenRailProperties properties,
            WorkflowFaults faults) {
        this.repository = repository;
        this.chain = chain;
        this.properties = properties;
        this.faults = faults;
    }

    public WorkflowRepository.RequestResult requestConversion(
            String actorId, String idempotencyKey, String businessId, BigInteger amount) {
        guard.verify();
        validate(idempotencyKey, businessId, amount);
        return repository.createConversion(properties.deploymentId(), actorId, idempotencyKey,
                requestHash(OperationKind.CONVERSION, actorId, businessId, amount), businessId, amount);
    }

    public WorkflowRepository.RequestResult requestRedemption(
            String actorId, String idempotencyKey, String businessId, BigInteger amount) {
        guard.verify();
        validate(idempotencyKey, businessId, amount);
        return repository.createRedemption(properties.deploymentId(), actorId, idempotencyKey,
                requestHash(OperationKind.REDEMPTION, actorId, businessId, amount), businessId, amount);
    }

    @Scheduled(fixedDelayString = "${tokenrail.worker-delay:1000}")
    public void scheduledWork() {
        if (!properties.workerEnabled()) {
            return;
        }
        try {
            processOne();
        } catch (RuntimeException exception) {
            log.warn("Durable workflow pass failed and will be retried: {}", exception.getMessage());
        }
    }

    public boolean processOne() {
        guard.verify();
        Optional<DepositOperation> claimed = repository.claimNext();
        if (claimed.isEmpty()) {
            return false;
        }
        DepositOperation operation = claimed.get();
        try {
            faults.afterClaim();
            processClaimed(operation);
            return true;
        } catch (RuntimeException exception) {
            try { repository.markWaiting(operation); } catch (StaleClaimException stale) {
                exception.addSuppressed(stale);
            }
            throw exception;
        }
    }

    private void processClaimed(DepositOperation operation) {
        if (operation.status() == OperationStatus.BURN_CONFIRMED) {
            repository.creditRedemption(operation, faults::beforeRedemptionCredit);
            return;
        }

        repository.assertClaim(operation);
        Credentials signer = chain.signer(operation);
        ChainTransaction transaction = repository.findTransaction(operation.operationId())
                .orElseGet(() -> repository.allocateNonce(
                        operation,
                        operation.kind() == OperationKind.CONVERSION ? "mintForConversion"
                                : operation.kind() == OperationKind.REDEMPTION
                                ? "requestRedemption" : repository.businessAction(operation.operationId()),
                        signer.getAddress(), chain.pendingNonce(signer.getAddress())));

        if (transaction.rawTransaction() == null) {
            PreparedTransaction prepared = chain.prepare(operation, signer, transaction.nonce());
            repository.storeSigned(operation, prepared.rawTransaction(), prepared.transactionHash());
            faults.afterSigned();
            transaction = repository.findTransaction(operation.operationId()).orElseThrow();
        }

        if ("PREPARED".equals(transaction.status())) {
            String hash = transaction.transactionHash();
            try {
                guard.verify();
                repository.assertClaim(operation);
                String returnedHash = chain.receipt(hash).isPresent() ? hash
                        : chain.broadcast(transaction.rawTransaction());
                if (!hash.equalsIgnoreCase(returnedHash)) {
                    throw new IllegalStateException("RPC returned a different transaction hash");
                }
                faults.afterBroadcast();
                repository.markSubmitted(operation, hash, false);
            } catch (RuntimeException exception) {
                repository.markSubmitted(operation, hash, true);
                throw exception;
            }
        } else if ("UNKNOWN".equals(transaction.status()) || "BROADCAST".equals(transaction.status())) {
            if (chain.receipt(transaction.transactionHash()).isEmpty()) {
                guard.verify();
                repository.assertClaim(operation);
                chain.broadcast(transaction.rawTransaction());
            }
        }

        transaction = repository.findTransaction(operation.operationId()).orElseThrow();
        Optional<TransactionReceipt> receipt = chain.receipt(transaction.transactionHash());
        if (receipt.isEmpty()) {
            repository.markWaiting(operation);
            return;
        }
        repository.recordReceipt(operation, receipt.get());
        boolean success = receipt.get().isStatusOK();
        repository.markMined(operation, success, success ? null : "CHAIN_REVERT");
        if (success && operation.kind() == OperationKind.BUSINESS) {
            repository.completeBusiness(operation);
        } else if (success && operation.kind() == OperationKind.CONVERSION) {
            repository.completeConversion(operation, faults::beforeConversionCommit);
        } else if (success) {
            repository.markBurnConfirmed(operation);
            repository.creditRedemption(operation, faults::beforeRedemptionCredit);
        }
    }

    private static String requestHash(
            OperationKind kind, String actorId, String businessId, BigInteger amount) {
        return Hashes.sha256(kind + "|" + actorId + "|"
                + businessId.toLowerCase(Locale.ROOT) + "|" + amount);
    }

    private static void validate(String idempotencyKey, String businessId, BigInteger amount) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("A non-empty Idempotency-Key of at most 128 characters is required");
        }
        if (businessId == null || !businessId.matches("0x[0-9a-fA-F]{64}")
                || BigInteger.ZERO.equals(new BigInteger(businessId.substring(2), 16))) {
            throw new IllegalArgumentException("businessId must be a non-zero bytes32 hex value");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
