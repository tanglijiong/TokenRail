# TokenRail v0.1.0

TokenRail v0.1.0 is a local, educational settlement lab for tokenized banking. It demonstrates restricted deposit and bond tokens, atomic delivery-versus-payment, attested escrow payments, durable EVM transaction submission, event-sourced position projection, and same-block reconciliation.

## Included

- Five Solidity contracts: IdentityRegistry, DepositToken, BondToken, DvPSettlement, and PaymentEscrow.
- Four-validator Hyperledger Besu QBFT network with node and account permissioning.
- Java 21 / Spring Boot service with PostgreSQL, idempotent APIs, durable outbox, signer nonce allocation, receipt recovery, deployment isolation, and projection rebuilds.
- Four-page Chinese workbench for overview, DvP, conditional payments, operations, and reconciliation.
- Automated contract, Besu, Java, reliability, and full-stack acceptance workflows.
- Reproducible demonstrations for escrow release/refund, successful and rejected DvP, and compliance freeze/retry.

## Run

```bash
cd contracts
npm ci
npm run check

cd ..
scripts/test-m7-release.sh
scripts/record-m7-demo.sh
```

For an interactive workbench without regenerating the recording:

```bash
scripts/demo-m7-scenarios.sh --keep-running
```

Then open `http://127.0.0.1:18081/`.

## Verification summary

- 51 Solidity tests, including 256-case fuzzing and three state invariants.
- Four-validator QBFT stop/recovery acceptance.
- 8 M4–M5 Besu integration tests.
- 8 M5.1 process-crash, chain-reset, accounting, and authorization tests.
- 2 M6 API and escrow integration tests.
- Three M7 demonstrator scenarios with recorded chain and projection evidence.

The exact M7 clean-run results and recording metadata are stored under `docs/evidence/m7-release-20260914/`.

## Scope and limitations

All identities, tokens, bank balances, and attestations are synthetic. An attestor transaction is a trusted test declaration, not proof of real-world delivery. The project does not implement production KYC, legal finality, real funds, HSM custody, privacy transactions, cross-chain settlement, or a complete bank ledger.

## Compatibility

- Java 21
- Node.js 20 or later
- Docker with Compose
- Maven 3.9+
- macOS or Linux host with `bash`, `curl`, `jq`, and standard Unix utilities

Released under the Apache License 2.0.
