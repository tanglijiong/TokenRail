// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { IERC20 } from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import { IdentityRegistry } from "../src/identity/IdentityRegistry.sol";
import { DvPSettlement } from "../src/settlement/DvPSettlement.sol";
import { BondToken } from "../src/token/BondToken.sol";
import { DepositToken } from "../src/token/DepositToken.sol";
import { InvariantTargeting } from "./support/InvariantTargeting.sol";

interface Vm {
    struct Log {
        bytes32[] topics;
        bytes data;
        address emitter;
    }

    function getRecordedLogs() external returns (Log[] memory);
    function recordLogs() external;
    function warp(uint256 newTimestamp) external;
}

contract DvPActor {
    function create(
        DvPSettlement settlement,
        bytes32 instructionId,
        address buyer,
        uint256 bondQuantity,
        uint256 cashAmount,
        uint64 expiry
    ) external {
        settlement.createInstruction(instructionId, buyer, bondQuantity, cashAmount, expiry);
    }

    function confirm(DvPSettlement settlement, bytes32 instructionId) external {
        settlement.confirmInstruction(instructionId);
    }

    function cancel(DvPSettlement settlement, bytes32 instructionId) external {
        settlement.cancelInstruction(instructionId);
    }

    function execute(DvPSettlement settlement, bytes32 instructionId) external {
        settlement.execute(instructionId);
    }

    function approve(IERC20 token, address spender, uint256 amount) external {
        require(token.approve(spender, amount), "approval returned false");
    }
}

contract RevertingCashToken is IERC20 {
    error SecondLegRejected();

    uint256 public override totalSupply;
    mapping(address account => uint256 balance) public override balanceOf;
    mapping(address owner => mapping(address spender => uint256 amount)) public override allowance;

    function mint(address recipient, uint256 amount) external {
        totalSupply += amount;
        balanceOf[recipient] += amount;
        emit Transfer(address(0), recipient, amount);
    }

    function approve(address spender, uint256 amount) external override returns (bool) {
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    function transfer(address recipient, uint256 amount) external override returns (bool) {
        balanceOf[msg.sender] -= amount;
        balanceOf[recipient] += amount;
        emit Transfer(msg.sender, recipient, amount);
        return true;
    }

    function transferFrom(address, address, uint256) external pure override returns (bool) {
        revert SecondLegRejected();
    }
}

    abstract contract DvPFixture {
        Vm internal constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));

        bytes32 internal constant INSTRUCTION_ID = keccak256("TR202609130001");
        bytes32 internal constant ASSET_REF =
            0x4142433230323900000000000000000000000000000000000000000000000000;
        bytes32 internal constant ISSUER_REF =
            0x4142435f42414e4b000000000000000000000000000000000000000000000000;
        bytes3 internal constant CNY = 0x434e59;
        uint256 internal constant BOND_QUANTITY = 1_000;
        uint256 internal constant CASH_AMOUNT = 100_000_000;

        IdentityRegistry internal registry;
        DepositToken internal cash;
        BondToken internal bond;
        DvPSettlement internal settlement;
        DvPActor internal seller;
        DvPActor internal buyer;
        DvPActor internal outsider;

        function setUp() public virtual {
            registry = new IdentityRegistry(address(this), address(this));
            cash = new DepositToken(registry, address(this), address(this));
            bond = new BondToken(
                registry,
                address(this),
                address(this),
                ASSET_REF,
                ISSUER_REF,
                CNY,
                100_000,
                320,
                uint64(block.timestamp + 365 days),
                100_000
            );
            settlement = new DvPSettlement(cash, bond, registry, address(this), address(this));
            seller = new DvPActor();
            buyer = new DvPActor();
            outsider = new DvPActor();

            _register(address(seller), keccak256("SELLER"));
            _register(address(buyer), keccak256("BUYER"));
            _register(address(outsider), keccak256("OUTSIDER"));

            cash.mintForConversion(keccak256("BUYER_CASH"), address(buyer), 150_000_000);
            bond.issue(keccak256("SELLER_BONDS"), address(seller), BOND_QUANTITY);
        }

        function _register(address account, bytes32 participantRef) internal {
            registry.setIdentity(account, participantRef, type(uint64).max, true, false, true, true);
        }

        function _create(bytes32 instructionId, uint256 quantity, uint256 amount, uint64 expiry)
            internal
        {
            seller.create(settlement, instructionId, address(buyer), quantity, amount, expiry);
        }

        function _makeReady(bytes32 instructionId, uint256 quantity, uint256 amount, uint64 expiry)
            internal
        {
            _create(instructionId, quantity, amount, expiry);
            buyer.confirm(settlement, instructionId);
            seller.approve(bond, address(settlement), quantity);
            buyer.approve(cash, address(settlement), amount);
        }

        function _futureExpiry() internal view returns (uint64) {
            return uint64(block.timestamp + 1 hours);
        }

        function _state(bytes32 instructionId) internal view returns (DvPSettlement.State state) {
            (,,,,, state) = settlement.instructions(instructionId);
        }

        function _callFails(address target, bytes memory callData) internal returns (bool) {
            (bool success,) = target.call(callData);
            return !success;
        }
    }

    contract DvPSettlementTest is DvPFixture {
        bytes32 private constant TRANSFER_TOPIC = keccak256("Transfer(address,address,uint256)");
        bytes32 private constant SETTLED_TOPIC =
            keccak256("SettlementExecuted(bytes32,address,address,uint256,uint256)");

        function testAtomicSettlementMovesExactCashAndBondAndEmitsEvidence() public {
            _makeReady(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, _futureExpiry());
            uint256 cashSupply = cash.totalSupply();
            uint256 bondSupply = bond.totalSupply();

            vm.recordLogs();
            settlement.execute(INSTRUCTION_ID);
            Vm.Log[] memory logs = vm.getRecordedLogs();

            require(_state(INSTRUCTION_ID) == DvPSettlement.State.SETTLED, "state not settled");
            require(bond.balanceOf(address(seller)) == 0, "seller bond not delivered");
            require(bond.balanceOf(address(buyer)) == BOND_QUANTITY, "buyer bond not received");
            require(cash.balanceOf(address(buyer)) == 50_000_000, "buyer cash mismatch");
            require(cash.balanceOf(address(seller)) == CASH_AMOUNT, "seller cash not received");
            require(cash.totalSupply() == cashSupply, "cash supply changed");
            require(bond.totalSupply() == bondSupply, "bond supply changed");
            require(logs.length == 3, "unexpected settlement log count");
            require(
                logs[0].emitter == address(bond) && logs[0].topics[0] == TRANSFER_TOPIC,
                "bond transfer event missing"
            );
            require(
                logs[1].emitter == address(cash) && logs[1].topics[0] == TRANSFER_TOPIC,
                "cash transfer event missing"
            );
            require(
                logs[2].emitter == address(settlement) && logs[2].topics[0] == SETTLED_TOPIC,
                "settlement event missing"
            );
        }

        function testSecondLegRevertRollsBackFirstLegAndState() public {
            RevertingCashToken rejectingCash = new RevertingCashToken();
            DvPSettlement rejectingSettlement =
                new DvPSettlement(rejectingCash, bond, registry, address(this), address(this));
            rejectingCash.mint(address(buyer), CASH_AMOUNT);

            seller.create(
                rejectingSettlement,
                INSTRUCTION_ID,
                address(buyer),
                BOND_QUANTITY,
                CASH_AMOUNT,
                _futureExpiry()
            );
            buyer.confirm(rejectingSettlement, INSTRUCTION_ID);
            seller.approve(bond, address(rejectingSettlement), BOND_QUANTITY);
            buyer.approve(rejectingCash, address(rejectingSettlement), CASH_AMOUNT);

            bool failed = _callFails(
                address(rejectingSettlement),
                abi.encodeCall(rejectingSettlement.execute, (INSTRUCTION_ID))
            );
            (,,,,, DvPSettlement.State state) = rejectingSettlement.instructions(INSTRUCTION_ID);

            require(failed, "second leg unexpectedly succeeded");
            require(state == DvPSettlement.State.READY, "failed DvP changed state");
            require(
                bond.balanceOf(address(seller)) == BOND_QUANTITY, "first leg was not rolled back"
            );
            require(bond.balanceOf(address(buyer)) == 0, "buyer retained rolled-back bond");
            require(
                bond.allowance(address(seller), address(rejectingSettlement)) == BOND_QUANTITY,
                "first-leg allowance was not rolled back"
            );
            require(rejectingCash.balanceOf(address(buyer)) == CASH_AMOUNT, "cash balance changed");
        }

        function testAllowanceDoesNotReplaceBuyerConfirmation() public {
            _create(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, _futureExpiry());
            seller.approve(bond, address(settlement), BOND_QUANTITY);
            buyer.approve(cash, address(settlement), CASH_AMOUNT);

            bool failed =
                _callFails(
                address(settlement), abi.encodeCall(settlement.execute, (INSTRUCTION_ID))
            );

            require(failed, "allowances replaced buyer confirmation");
            require(_state(INSTRUCTION_ID) == DvPSettlement.State.CREATED, "state changed");
        }

        function testOnlyNamedBuyerCanConfirmAndOnlyPartyCanCancel() public {
            _create(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, _futureExpiry());

            bool confirmationFailed = _callFails(
                address(outsider), abi.encodeCall(outsider.confirm, (settlement, INSTRUCTION_ID))
            );
            bool cancellationFailed = _callFails(
                address(outsider), abi.encodeCall(outsider.cancel, (settlement, INSTRUCTION_ID))
            );

            require(confirmationFailed && cancellationFailed, "outsider changed instruction");
            require(_state(INSTRUCTION_ID) == DvPSettlement.State.CREATED, "state changed");
        }

        function testInvalidTermsAndIneligibleBuyerAreRejected() public {
            DvPActor ineligibleBuyer = new DvPActor();
            uint64 expiry = _futureExpiry();

            bool zeroIdFailed = _callFails(
                address(seller),
                abi.encodeCall(
                    seller.create, (settlement, bytes32(0), address(buyer), 1, 1, expiry)
                )
            );
            bool samePartyFailed = _callFails(
                address(seller),
                abi.encodeCall(
                    seller.create,
                    (settlement, keccak256("SAME_PARTY"), address(seller), 1, 1, expiry)
                )
            );
            bool zeroAmountFailed = _callFails(
                address(seller),
                abi.encodeCall(
                    seller.create,
                    (settlement, keccak256("ZERO_AMOUNT"), address(buyer), 1, 0, expiry)
                )
            );
            bool expiredFailed = _callFails(
                address(seller),
                abi.encodeCall(
                    seller.create,
                    (
                        settlement,
                        keccak256("PAST_EXPIRY"),
                        address(buyer),
                        1,
                        1,
                        uint64(block.timestamp)
                    )
                )
            );
            bool eligibilityFailed = _callFails(
                address(seller),
                abi.encodeCall(
                    seller.create,
                    (settlement, keccak256("INELIGIBLE"), address(ineligibleBuyer), 1, 1, expiry)
                )
            );

            require(
                zeroIdFailed && samePartyFailed && zeroAmountFailed && expiredFailed
                    && eligibilityFailed,
                "invalid instruction was accepted"
            );
        }

        function testCreatedTermsCannotBeReplaced() public {
            uint64 expiry = _futureExpiry();
            _create(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, expiry);

            bool failed = _callFails(
                address(seller),
                abi.encodeCall(
                    seller.create,
                    (
                        settlement,
                        INSTRUCTION_ID,
                        address(outsider),
                        BOND_QUANTITY - 1,
                        CASH_AMOUNT - 1,
                        expiry + 1
                    )
                )
            );
            (
                address storedSeller,
                address storedBuyer,
                uint256 storedQuantity,
                uint256 storedCash,
                uint64 storedExpiry,
                DvPSettlement.State state
            ) = settlement.instructions(INSTRUCTION_ID);

            require(failed, "existing instruction was replaced");
            require(storedSeller == address(seller), "seller changed");
            require(storedBuyer == address(buyer), "buyer changed");
            require(storedQuantity == BOND_QUANTITY, "quantity changed");
            require(storedCash == CASH_AMOUNT, "cash changed");
            require(storedExpiry == expiry, "expiry changed");
            require(state == DvPSettlement.State.CREATED, "state changed");
        }

        function testOnlySettlementOperatorCanExecute() public {
            _makeReady(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, _futureExpiry());

            bool failed = _callFails(
                address(outsider), abi.encodeCall(outsider.execute, (settlement, INSTRUCTION_ID))
            );

            require(failed, "non-operator settled instruction");
            require(_state(INSTRUCTION_ID) == DvPSettlement.State.READY, "state changed");
        }

        function testInsufficientBalanceAndAllowanceLeaveInstructionReady() public {
            bytes32 balanceId = keccak256("BALANCE_SHORTAGE");
            _makeReady(balanceId, BOND_QUANTITY, 150_000_001, _futureExpiry());
            bool balanceFailed =
                _callFails(address(settlement), abi.encodeCall(settlement.execute, (balanceId)));
            require(balanceFailed, "insufficient cash settled");
            require(_state(balanceId) == DvPSettlement.State.READY, "balance failure changed state");

            bytes32 allowanceId = keccak256("ALLOWANCE_SHORTAGE");
            _create(allowanceId, BOND_QUANTITY, CASH_AMOUNT, _futureExpiry());
            buyer.confirm(settlement, allowanceId);
            seller.approve(bond, address(settlement), BOND_QUANTITY - 1);
            buyer.approve(cash, address(settlement), CASH_AMOUNT);
            bool allowanceFailed =
                _callFails(address(settlement), abi.encodeCall(settlement.execute, (allowanceId)));

            require(allowanceFailed, "insufficient allowance settled");
            require(
                _state(allowanceId) == DvPSettlement.State.READY, "allowance failure changed state"
            );
            require(bond.balanceOf(address(seller)) == BOND_QUANTITY, "failure moved bonds");
            require(cash.balanceOf(address(buyer)) == 150_000_000, "failure moved cash");
        }

        function testEitherPartyCanCancelBeforeExpiryAndSettlementCannotFollow() public {
            bytes32 sellerCancelId = keccak256("SELLER_CANCEL");
            _create(sellerCancelId, BOND_QUANTITY, CASH_AMOUNT, _futureExpiry());
            seller.cancel(settlement, sellerCancelId);
            require(
                _state(sellerCancelId) == DvPSettlement.State.CANCELLED, "seller could not cancel"
            );

            _makeReady(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, _futureExpiry());
            buyer.cancel(settlement, INSTRUCTION_ID);

            bool failed =
                _callFails(
                address(settlement), abi.encodeCall(settlement.execute, (INSTRUCTION_ID))
            );

            require(failed, "cancelled instruction settled");
            require(_state(INSTRUCTION_ID) == DvPSettlement.State.CANCELLED, "not cancelled");
        }

        function testExpiryBoundaryBlocksExecutionAndCanBeFinalised() public {
            uint64 expiry = uint64(block.timestamp + 100);
            _makeReady(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, expiry);
            vm.warp(expiry);

            bool executeFailed =
                _callFails(
                address(settlement), abi.encodeCall(settlement.execute, (INSTRUCTION_ID))
            );
            bool cancelFailed = _callFails(
                address(buyer), abi.encodeCall(buyer.cancel, (settlement, INSTRUCTION_ID))
            );
            settlement.expireInstruction(INSTRUCTION_ID);

            require(executeFailed && cancelFailed, "expired instruction changed through live path");
            require(_state(INSTRUCTION_ID) == DvPSettlement.State.EXPIRED, "not expired");
        }

        function testExecutionOneSecondBeforeExpirySucceeds() public {
            uint64 expiry = uint64(block.timestamp + 100);
            _makeReady(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, expiry);
            vm.warp(expiry - 1);

            settlement.execute(INSTRUCTION_ID);

            require(
                _state(INSTRUCTION_ID) == DvPSettlement.State.SETTLED, "boundary settlement failed"
            );
        }

        function testFreezeAfterConfirmationBlocksThenAllowsRetry() public {
            _makeReady(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, _futureExpiry());
            registry.setFrozen(address(buyer), true);

            bool failed =
                _callFails(
                address(settlement), abi.encodeCall(settlement.execute, (INSTRUCTION_ID))
            );
            require(failed, "frozen buyer settled");
            require(_state(INSTRUCTION_ID) == DvPSettlement.State.READY, "freeze changed state");

            registry.setFrozen(address(buyer), false);
            settlement.execute(INSTRUCTION_ID);
            require(_state(INSTRUCTION_ID) == DvPSettlement.State.SETTLED, "retry did not settle");
        }

        function testPausedSettlementCanBeCancelledButNotExecutedOrCreated() public {
            bytes32 waitingConfirmationId = keccak256("WAITING_CONFIRMATION");
            _create(waitingConfirmationId, 1, 1, _futureExpiry());
            _makeReady(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, _futureExpiry());
            settlement.pause();

            bool executeFailed =
                _callFails(
                address(settlement), abi.encodeCall(settlement.execute, (INSTRUCTION_ID))
            );
            bool createFailed = _callFails(
                address(seller),
                abi.encodeCall(
                    seller.create,
                    (settlement, keccak256("WHILE_PAUSED"), address(buyer), 1, 1, _futureExpiry())
                )
            );
            bool confirmationFailed = _callFails(
                address(buyer), abi.encodeCall(buyer.confirm, (settlement, waitingConfirmationId))
            );
            buyer.cancel(settlement, INSTRUCTION_ID);
            seller.cancel(settlement, waitingConfirmationId);

            require(
                executeFailed && createFailed && confirmationFailed, "paused operation succeeded"
            );
            require(_state(INSTRUCTION_ID) == DvPSettlement.State.CANCELLED, "cancel was blocked");
        }

        function testInstructionIdAndSettlementCannotBeReplayed() public {
            _makeReady(INSTRUCTION_ID, BOND_QUANTITY, CASH_AMOUNT, _futureExpiry());
            settlement.execute(INSTRUCTION_ID);

            bool executeFailed =
                _callFails(
                address(settlement), abi.encodeCall(settlement.execute, (INSTRUCTION_ID))
            );
            bool createFailed = _callFails(
                address(seller),
                abi.encodeCall(
                    seller.create,
                    (
                        settlement,
                        INSTRUCTION_ID,
                        address(buyer),
                        BOND_QUANTITY,
                        CASH_AMOUNT,
                        _futureExpiry()
                    )
                )
            );

            require(executeFailed && createFailed, "settlement replay succeeded");
            require(bond.balanceOf(address(buyer)) == BOND_QUANTITY, "replay moved bonds");
            require(cash.balanceOf(address(seller)) == CASH_AMOUNT, "replay moved cash");
        }

        function testFuzzSettlementMovesExactAmounts(uint96 quantitySeed, uint96 cashSeed) public {
            uint256 quantity = (uint256(quantitySeed) % BOND_QUANTITY) + 1;
            uint256 amount = (uint256(cashSeed) % 150_000_000) + 1;
            bytes32 instructionId = keccak256(abi.encode(quantity, amount));
            uint256 buyerCashBefore = cash.balanceOf(address(buyer));
            uint256 sellerBondBefore = bond.balanceOf(address(seller));
            uint256 cashSupply = cash.totalSupply();
            uint256 bondSupply = bond.totalSupply();

            _makeReady(instructionId, quantity, amount, _futureExpiry());
            settlement.execute(instructionId);

            require(
                bond.balanceOf(address(seller)) == sellerBondBefore - quantity,
                "fuzz bond debit mismatch"
            );
            require(bond.balanceOf(address(buyer)) == quantity, "fuzz bond credit mismatch");
            require(
                cash.balanceOf(address(buyer)) == buyerCashBefore - amount,
                "fuzz cash debit mismatch"
            );
            require(cash.balanceOf(address(seller)) == amount, "fuzz cash credit mismatch");
            require(cash.totalSupply() == cashSupply, "fuzz changed cash supply");
            require(bond.totalSupply() == bondSupply, "fuzz changed bond supply");
        }
    }

    contract DvPInvariantHandler {
        IdentityRegistry public registry;
        DepositToken public cash;
        BondToken public bond;
        DvPSettlement public settlement;
        DvPActor public seller;
        DvPActor public buyer;

        uint256 public successfulSettlements;
        uint256 public illegalActionSuccesses;
        bytes32 public lastSettlementId;

        bytes32 private constant ASSET_REF =
            0x4142433230323900000000000000000000000000000000000000000000000000;
        bytes32 private constant ISSUER_REF =
            0x4142435f42414e4b000000000000000000000000000000000000000000000000;

        constructor() {
            registry = new IdentityRegistry(address(this), address(this));
            cash = new DepositToken(registry, address(this), address(this));
            bond = new BondToken(
                registry,
                address(this),
                address(this),
                ASSET_REF,
                ISSUER_REF,
                0x434e59,
                100_000,
                320,
                uint64(block.timestamp + 365 days),
                100_000
            );
            settlement = new DvPSettlement(cash, bond, registry, address(this), address(this));
            seller = new DvPActor();
            buyer = new DvPActor();
            registry.setIdentity(
                address(seller),
                keccak256("INVARIANT_SELLER"),
                type(uint64).max,
                true,
                false,
                true,
                true
            );
            registry.setIdentity(
                address(buyer),
                keccak256("INVARIANT_BUYER"),
                type(uint64).max,
                true,
                false,
                true,
                true
            );
            cash.mintForConversion(keccak256("INVARIANT_CASH"), address(buyer), 1_500_000);
            bond.issue(keccak256("INVARIANT_BONDS"), address(seller), 1_000);

            settle(1, 1);
        }

        function settle(uint96 quantitySeed, uint96 cashSeed) public {
            uint256 availableBond = bond.balanceOf(address(seller));
            uint256 availableCash = cash.balanceOf(address(buyer));
            if (availableBond == 0 || availableCash == 0) {
                return;
            }

            uint256 quantity = (uint256(quantitySeed) % availableBond) + 1;
            uint256 amount = (uint256(cashSeed) % availableCash) + 1;
            bytes32 instructionId =
                keccak256(abi.encode("INVARIANT", successfulSettlements, quantity, amount));

            seller.create(
                settlement, instructionId, address(buyer), quantity, amount, type(uint64).max
            );
            buyer.confirm(settlement, instructionId);
            seller.approve(bond, address(settlement), quantity);
            buyer.approve(cash, address(settlement), amount);
            settlement.execute(instructionId);

            lastSettlementId = instructionId;
            successfulSettlements++;
        }

        function attemptReplay() external {
            if (lastSettlementId == bytes32(0)) {
                return;
            }
            (bool success,) =
                address(settlement).call(abi.encodeCall(settlement.execute, (lastSettlementId)));
            if (success) {
                illegalActionSuccesses++;
            }
        }

        function attemptCancelSettled() external {
            if (lastSettlementId == bytes32(0)) {
                return;
            }
            (bool success,) =
                address(seller).call(abi.encodeCall(seller.cancel, (settlement, lastSettlementId)));
            if (success) {
                illegalActionSuccesses++;
            }
        }
    }

    contract DvPSettlementInvariantTest is InvariantTargeting {
        DvPInvariantHandler private handler;

        function setUp() public {
            handler = new DvPInvariantHandler();
            _targetContract(address(handler));
        }

        function invariantAssetSupplyIsConservedAcrossParticipants() public view {
            DepositToken cash = handler.cash();
            BondToken bond = handler.bond();
            address seller = address(handler.seller());
            address buyer = address(handler.buyer());

            require(cash.totalSupply() == 1_500_000, "cash supply changed");
            require(bond.totalSupply() == 1_000, "bond supply changed");
            require(
                cash.balanceOf(seller) + cash.balanceOf(buyer) == cash.totalSupply(),
                "cash escaped participants"
            );
            require(
                bond.balanceOf(seller) + bond.balanceOf(buyer) == bond.totalSupply(),
                "bond escaped participants"
            );
        }

        function invariantAtLeastOneRealSettlementSucceeded() public view {
            require(handler.successfulSettlements() > 0, "invariant is vacuous");
            (,,,,, DvPSettlement.State state) =
                handler.settlement().instructions(handler.lastSettlementId());
            require(state == DvPSettlement.State.SETTLED, "last successful settlement not terminal");
        }

        function invariantIllegalTerminalActionsNeverSucceed() public view {
            require(handler.illegalActionSuccesses() == 0, "terminal instruction changed");
        }
    }
