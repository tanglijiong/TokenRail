// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { AccessControl } from "@openzeppelin/contracts/access/AccessControl.sol";
import { Pausable } from "@openzeppelin/contracts/utils/Pausable.sol";
import { ReentrancyGuard } from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import { IERC20 } from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import { SafeERC20 } from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import { IdentityRegistry } from "../identity/IdentityRegistry.sol";

/// @notice Synthetic attested payments; an attestation does not establish real-world delivery.
contract PaymentEscrow is AccessControl, Pausable, ReentrancyGuard {
    using SafeERC20 for IERC20;

    error InvalidTerms();
    error InvalidState(bytes32 paymentId);
    error UnauthorizedParty();
    error InvalidDeadline();
    error IneligibleParticipant(address account);

    bytes32 public constant ATTESTOR_ROLE = keccak256("ATTESTOR_ROLE");
    enum State {
        NONE,
        FUNDED,
        RELEASED,
        REFUNDED
    }

    struct Payment {
        address payer;
        address payee;
        address attestor;
        uint256 amount;
        uint64 deadline;
        State state;
    }

    IERC20 public immutable cashToken;
    IdentityRegistry public immutable identityRegistry;
    uint256 public outstanding;
    mapping(bytes32 => Payment) public payments;

    event PaymentFunded(
        bytes32 indexed paymentId,
        address indexed payer,
        address indexed payee,
        address attestor,
        uint256 amount,
        uint64 deadline
    );
    event PaymentReleased(bytes32 indexed paymentId, address indexed payee, uint256 amount);
    event PaymentRefunded(bytes32 indexed paymentId, address indexed payer, uint256 amount);

    constructor(IERC20 cash, IdentityRegistry registry, address admin, address attestor) {
        if (
            address(cash).code.length == 0 || address(registry).code.length == 0
                || admin == address(0) || attestor == address(0)
        ) revert InvalidTerms();
        cashToken = cash;
        identityRegistry = registry;
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(ATTESTOR_ROLE, attestor);
    }

    function fund(bytes32 id, address payee, address attestor, uint256 amount, uint64 deadline)
        external
        whenNotPaused
        nonReentrant
    {
        if (
            id == bytes32(0) || payee == address(0) || payee == msg.sender || amount == 0
                || !hasRole(ATTESTOR_ROLE, attestor)
        ) revert InvalidTerms();
        if (payments[id].state != State.NONE) revert InvalidState(id);
        // forge-lint: disable-next-line(block-timestamp)
        if (deadline <= block.timestamp) revert InvalidDeadline();
        if (!identityRegistry.isEligibleForCash(msg.sender)) {
            revert IneligibleParticipant(msg.sender);
        }
        if (!identityRegistry.isEligibleForCash(payee)) revert IneligibleParticipant(payee);
        payments[id] = Payment(msg.sender, payee, attestor, amount, deadline, State.FUNDED);
        outstanding += amount;
        cashToken.safeTransferFrom(msg.sender, address(this), amount);
        emit PaymentFunded(id, msg.sender, payee, attestor, amount, deadline);
    }

    function release(bytes32 id) external whenNotPaused nonReentrant {
        Payment storage payment = payments[id];
        if (payment.state != State.FUNDED) revert InvalidState(id);
        if (msg.sender != payment.attestor || !hasRole(ATTESTOR_ROLE, msg.sender)) {
            revert UnauthorizedParty();
        }
        // forge-lint: disable-next-line(block-timestamp)
        if (block.timestamp >= payment.deadline) revert InvalidDeadline();
        payment.state = State.RELEASED;
        outstanding -= payment.amount;
        cashToken.safeTransfer(payment.payee, payment.amount);
        emit PaymentReleased(id, payment.payee, payment.amount);
    }

    function refund(bytes32 id) external nonReentrant {
        Payment storage payment = payments[id];
        if (payment.state != State.FUNDED) revert InvalidState(id);
        if (msg.sender != payment.payer) revert UnauthorizedParty();
        // forge-lint: disable-next-line(block-timestamp)
        if (block.timestamp < payment.deadline) revert InvalidDeadline();
        payment.state = State.REFUNDED;
        outstanding -= payment.amount;
        // Token eligibility and pause rules still apply. Revert leaves funds in escrow (HOLD).
        cashToken.safeTransfer(payment.payer, payment.amount);
        emit PaymentRefunded(id, payment.payer, payment.amount);
    }

    function pause() external onlyRole(DEFAULT_ADMIN_ROLE) {
        _pause();
    }

    function unpause() external onlyRole(DEFAULT_ADMIN_ROLE) {
        _unpause();
    }
}
