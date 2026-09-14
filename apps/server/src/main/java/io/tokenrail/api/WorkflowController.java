package io.tokenrail.api;

import io.tokenrail.ledger.DepositOperation;
import io.tokenrail.ledger.DepositWorkflowService;
import io.tokenrail.ledger.WorkflowRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigInteger;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WorkflowController {
    public record WorkflowRequest(
            @NotBlank @Pattern(regexp = "0x[0-9a-fA-F]{64}") String businessId,
            @Positive BigInteger amount) {
    }

    public record Submission(UUID operationId, String resourceId, String submissionStatus) {
    }

    public record OperationView(
            UUID operationId, String deploymentId, String resourceId, String kind, @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigInteger amount,
            String status, String transactionHash) {
    }

    private final DepositWorkflowService workflows;
    private final WorkflowRepository repository;
    private final AuthenticationService authentication;
    @org.springframework.beans.factory.annotation.Value("${tokenrail.deployment-id}") private String deploymentId;

    public WorkflowController(
            DepositWorkflowService workflows,
            WorkflowRepository repository,
            AuthenticationService authentication) {
        this.workflows = workflows;
        this.repository = repository;
        this.authentication = authentication;
    }

    @PostMapping("/deposit-conversions")
    public ResponseEntity<Submission> convert(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WorkflowRequest request) {
        String actor = authentication.authenticate(authorization);
        WorkflowRepository.RequestResult result = workflows.requestConversion(
                actor, idempotencyKey, request.businessId(), request.amount());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Submission(
                result.operationId(), result.resourceId(), result.status().name()));
    }

    @PostMapping("/redemptions")
    public ResponseEntity<Submission> redeem(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WorkflowRequest request) {
        String actor = authentication.authenticate(authorization);
        WorkflowRepository.RequestResult result = workflows.requestRedemption(
                actor, idempotencyKey, request.businessId(), request.amount());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Submission(
                result.operationId(), result.resourceId(), result.status().name()));
    }

    @GetMapping("/operations/{operationId}")
    public OperationView operation(
            @RequestHeader("Authorization") String authorization,
            @PathVariable UUID operationId) {
        String actor = authentication.authenticate(authorization);
        DepositOperation operation = repository.getOperation(operationId);
        if (!operation.participantId().equals(actor) || !operation.deploymentId().equals(deploymentId)) {
            throw new ForbiddenException("Operation belongs to another participant");
        }
        return new OperationView(operation.operationId(), operation.deploymentId(), repository.resourceId(operation),
                operation.kind().name(), operation.amount(), operation.status().name(),
                operation.transactionHash());
    }

}
