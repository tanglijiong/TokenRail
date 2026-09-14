// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { IERC20 } from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import { IdentityRegistry } from "../../src/identity/IdentityRegistry.sol";
import { DvPSettlement } from "../../src/settlement/DvPSettlement.sol";
import { BondToken } from "../../src/token/BondToken.sol";

contract ReceiptActor {
    function createAndApprove(
        DvPSettlement settlement,
        IERC20 bond,
        bytes32 instructionId,
        address buyer,
        uint256 quantity,
        uint256 amount
    ) external {
        settlement.createInstruction(instructionId, buyer, quantity, amount, type(uint64).max);
        require(bond.approve(address(settlement), quantity), "bond approval failed");
    }

    function confirmAndApprove(
        DvPSettlement settlement,
        IERC20 cash,
        bytes32 instructionId,
        uint256 amount
    ) external {
        settlement.confirmInstruction(instructionId);
        require(cash.approve(address(settlement), amount), "cash approval failed");
    }
}

contract ReceiptRevertingCashToken is IERC20 {
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

    /// @notice Deploys a ready instruction whose cash leg always reverts.
    /// @dev Used only by scripts/m2-anvil-atomicity.sh to inspect a real failed receipt.
    contract M2AtomicityReceiptHarness {
        bytes32 public constant INSTRUCTION_ID = keccak256("M2_RECEIPT_ROLLBACK");
        uint256 public constant BOND_QUANTITY = 1_000;
        uint256 public constant CASH_AMOUNT = 100_000_000;

        IdentityRegistry public immutable registry;
        BondToken public immutable bond;
        ReceiptRevertingCashToken public immutable cash;
        DvPSettlement public immutable settlement;
        ReceiptActor public immutable seller;
        ReceiptActor public immutable buyer;

        constructor() {
            registry = new IdentityRegistry(address(this), address(this));
            bond = new BondToken(
                registry,
                address(this),
                address(this),
                0x4142433230323900000000000000000000000000000000000000000000000000,
                0x4142435f42414e4b000000000000000000000000000000000000000000000000,
                0x434e59,
                100_000,
                320,
                type(uint64).max,
                100_000
            );
            cash = new ReceiptRevertingCashToken();
            settlement = new DvPSettlement(cash, bond, registry, address(this), address(this));
            seller = new ReceiptActor();
            buyer = new ReceiptActor();

            registry.setIdentity(
                address(seller),
                keccak256("RECEIPT_SELLER"),
                type(uint64).max,
                true,
                false,
                true,
                true
            );
            registry.setIdentity(
                address(buyer),
                keccak256("RECEIPT_BUYER"),
                type(uint64).max,
                true,
                false,
                true,
                true
            );
            bond.issue(keccak256("RECEIPT_BONDS"), address(seller), BOND_QUANTITY);
            cash.mint(address(buyer), CASH_AMOUNT);
            seller.createAndApprove(
                settlement, bond, INSTRUCTION_ID, address(buyer), BOND_QUANTITY, CASH_AMOUNT
            );
            buyer.confirmAndApprove(settlement, cash, INSTRUCTION_ID, CASH_AMOUNT);
        }

        function executeFailure() external {
            settlement.execute(INSTRUCTION_ID);
        }

        function sellerBondBalance() external view returns (uint256) {
            return bond.balanceOf(address(seller));
        }

        function buyerBondBalance() external view returns (uint256) {
            return bond.balanceOf(address(buyer));
        }

        function sellerBondAllowance() external view returns (uint256) {
            return bond.allowance(address(seller), address(settlement));
        }

        function buyerCashBalance() external view returns (uint256) {
            return cash.balanceOf(address(buyer));
        }

        function instructionState() external view returns (DvPSettlement.State state) {
            (,,,,, state) = settlement.instructions(INSTRUCTION_ID);
        }
    }
