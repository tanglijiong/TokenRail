// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { AccessControl } from "@openzeppelin/contracts/access/AccessControl.sol";
import { IERC20 } from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import { SafeERC20 } from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import { Pausable } from "@openzeppelin/contracts/utils/Pausable.sol";
import { ReentrancyGuard } from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import { IdentityRegistry } from "../identity/IdentityRegistry.sol";

/// @title DvPSettlement
/// @notice Atomically exchanges a fixed bond token and deposit token on one EVM chain.
contract DvPSettlement is AccessControl, Pausable, ReentrancyGuard {
    using SafeERC20 for IERC20;

    error IdenticalAssets();
    error IneligibleParticipant(address participant);
    error InsufficientAllowance(
        address token, address owner, uint256 requiredAmount, uint256 actualAllowance
    );
    error InsufficientBalance(
        address token, address owner, uint256 requiredAmount, uint256 actualBalance
    );
    error InstructionAlreadyExists(bytes32 instructionId);
    error InstructionPastDeadline(bytes32 instructionId);
    error InvalidAccount();
    error InvalidAsset(address asset);
    error InvalidInstructionId();
    error InvalidInstructionState(bytes32 instructionId, State actualState);
    error InvalidTerms();
    error NotExpired(bytes32 instructionId);
    error UnauthorizedParty(address caller);

    bytes32 public constant SETTLEMENT_OPERATOR_ROLE = keccak256("SETTLEMENT_OPERATOR_ROLE");
    bytes32 public constant PAUSER_ROLE = keccak256("PAUSER_ROLE");

    enum State {
        NONE,
        CREATED,
        READY,
        SETTLED,
        CANCELLED,
        EXPIRED
    }

    struct Instruction {
        address seller;
        address buyer;
        uint256 bondQuantity;
        uint256 cashAmount;
        uint64 expiry;
        State state;
    }

    event InstructionCreated(
        bytes32 indexed instructionId,
        address indexed seller,
        address indexed buyer,
        uint256 bondQuantity,
        uint256 cashAmount,
        uint64 expiry
    );
    event InstructionConfirmed(bytes32 indexed instructionId, address indexed buyer);
    event InstructionCancelled(bytes32 indexed instructionId, address indexed cancelledBy);
    event InstructionExpired(bytes32 indexed instructionId);
    event SettlementExecuted(
        bytes32 indexed instructionId,
        address indexed seller,
        address indexed buyer,
        uint256 bondQuantity,
        uint256 cashAmount
    );

    IERC20 public immutable cashToken;
    IERC20 public immutable bondToken;
    IdentityRegistry public immutable identityRegistry;

    mapping(bytes32 instructionId => Instruction instruction) public instructions;

    constructor(
        IERC20 cash,
        IERC20 bond,
        IdentityRegistry registry,
        address admin,
        address settlementOperator
    ) {
        if (admin == address(0) || settlementOperator == address(0)) {
            revert InvalidAccount();
        }
        if (address(cash) == address(0) || address(cash).code.length == 0) {
            revert InvalidAsset(address(cash));
        }
        if (address(bond) == address(0) || address(bond).code.length == 0) {
            revert InvalidAsset(address(bond));
        }
        if (address(cash) == address(bond)) {
            revert IdenticalAssets();
        }
        if (address(registry) == address(0) || address(registry).code.length == 0) {
            revert InvalidAsset(address(registry));
        }

        cashToken = cash;
        bondToken = bond;
        identityRegistry = registry;
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(PAUSER_ROLE, admin);
        _grantRole(SETTLEMENT_OPERATOR_ROLE, settlementOperator);
    }

    function createInstruction(
        bytes32 instructionId,
        address buyer,
        uint256 bondQuantity,
        uint256 cashAmount,
        uint64 expiry
    ) external whenNotPaused {
        if (instructionId == bytes32(0)) {
            revert InvalidInstructionId();
        }
        if (instructions[instructionId].state != State.NONE) {
            revert InstructionAlreadyExists(instructionId);
        }
        if (buyer == address(0) || buyer == msg.sender || bondQuantity == 0 || cashAmount == 0) {
            revert InvalidTerms();
        }
        // Settlement deadlines are business-scale boundaries; second-level validator drift is accepted.
        // forge-lint: disable-next-line(block-timestamp)
        if (expiry <= block.timestamp) {
            revert InvalidTerms();
        }

        _requireEligible(msg.sender);
        _requireEligible(buyer);

        instructions[instructionId] = Instruction({
            seller: msg.sender,
            buyer: buyer,
            bondQuantity: bondQuantity,
            cashAmount: cashAmount,
            expiry: expiry,
            state: State.CREATED
        });

        emit InstructionCreated(instructionId, msg.sender, buyer, bondQuantity, cashAmount, expiry);
    }

    function confirmInstruction(bytes32 instructionId) external whenNotPaused {
        Instruction storage instruction = _requireState(instructionId, State.CREATED);
        if (msg.sender != instruction.buyer) {
            revert UnauthorizedParty(msg.sender);
        }
        _requireNotExpired(instructionId, instruction);
        _requireEligible(instruction.seller);
        _requireEligible(instruction.buyer);

        instruction.state = State.READY;
        emit InstructionConfirmed(instructionId, msg.sender);
    }

    function cancelInstruction(bytes32 instructionId) external {
        Instruction storage instruction = instructions[instructionId];
        if (instruction.state != State.CREATED && instruction.state != State.READY) {
            revert InvalidInstructionState(instructionId, instruction.state);
        }
        if (msg.sender != instruction.seller && msg.sender != instruction.buyer) {
            revert UnauthorizedParty(msg.sender);
        }
        _requireNotExpired(instructionId, instruction);

        instruction.state = State.CANCELLED;
        emit InstructionCancelled(instructionId, msg.sender);
    }

    function expireInstruction(bytes32 instructionId) external {
        Instruction storage instruction = instructions[instructionId];
        if (instruction.state != State.CREATED && instruction.state != State.READY) {
            revert InvalidInstructionState(instructionId, instruction.state);
        }
        // forge-lint: disable-next-line(block-timestamp)
        if (block.timestamp < instruction.expiry) {
            revert NotExpired(instructionId);
        }

        instruction.state = State.EXPIRED;
        emit InstructionExpired(instructionId);
    }

    function execute(bytes32 instructionId)
        external
        onlyRole(SETTLEMENT_OPERATOR_ROLE)
        whenNotPaused
        nonReentrant
    {
        Instruction storage instruction = _requireState(instructionId, State.READY);
        _requireNotExpired(instructionId, instruction);
        _requireEligible(instruction.seller);
        _requireEligible(instruction.buyer);
        _requireBalanceAndAllowance(bondToken, instruction.seller, instruction.bondQuantity);
        _requireBalanceAndAllowance(cashToken, instruction.buyer, instruction.cashAmount);

        instruction.state = State.SETTLED;
        bondToken.safeTransferFrom(instruction.seller, instruction.buyer, instruction.bondQuantity);
        cashToken.safeTransferFrom(instruction.buyer, instruction.seller, instruction.cashAmount);

        emit SettlementExecuted(
            instructionId,
            instruction.seller,
            instruction.buyer,
            instruction.bondQuantity,
            instruction.cashAmount
        );
    }

    function pause() external onlyRole(PAUSER_ROLE) {
        _pause();
    }

    function unpause() external onlyRole(PAUSER_ROLE) {
        _unpause();
    }

    function _requireState(bytes32 instructionId, State expected)
        private
        view
        returns (Instruction storage instruction)
    {
        instruction = instructions[instructionId];
        if (instruction.state != expected) {
            revert InvalidInstructionState(instructionId, instruction.state);
        }
    }

    function _requireEligible(address participant) private view {
        if (
            !identityRegistry.isEligibleForCash(participant)
                || !identityRegistry.isEligibleForBond(participant)
        ) {
            revert IneligibleParticipant(participant);
        }
    }

    function _requireNotExpired(bytes32 instructionId, Instruction storage instruction)
        private
        view
    {
        // forge-lint: disable-next-line(block-timestamp)
        if (block.timestamp >= instruction.expiry) {
            revert InstructionPastDeadline(instructionId);
        }
    }

    function _requireBalanceAndAllowance(IERC20 token, address owner, uint256 amount) private view {
        uint256 balance = token.balanceOf(owner);
        if (balance < amount) {
            revert InsufficientBalance(address(token), owner, amount, balance);
        }

        uint256 currentAllowance = token.allowance(owner, address(this));
        if (currentAllowance < amount) {
            revert InsufficientAllowance(address(token), owner, amount, currentAllowance);
        }
    }
}
