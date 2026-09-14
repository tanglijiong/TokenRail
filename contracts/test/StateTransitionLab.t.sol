// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { StateTransitionLab } from "../src/lab/StateTransitionLab.sol";

contract UnauthorisedCaller {
    function increase(StateTransitionLab lab, uint256 amount) external {
        lab.increaseBy(amount);
    }
}

contract StateTransitionLabTest {
    StateTransitionLab private lab;

    function setUp() public {
        lab = new StateTransitionLab(10, 100);
    }

    function testSuccessfulCallCommitsState() public {
        lab.increaseBy(15);

        require(lab.counter() == 25, "successful state transition was not committed");
    }

    function testLimitFailureLeavesStateUnchanged() public {
        (bool success,) = address(lab).call(abi.encodeCall(lab.increaseBy, (91)));

        require(!success, "call should revert above the limit");
        require(lab.counter() == 10, "failed call changed storage");
    }

    function testWriteBeforeRevertIsRolledBack() public {
        (bool success,) = address(lab).call(abi.encodeCall(lab.increaseThenRevert, (20)));

        require(!success, "call should intentionally revert");
        require(lab.counter() == 10, "storage write before revert was not rolled back");
    }

    function testUnauthorisedCallerCannotChangeState() public {
        UnauthorisedCaller caller = new UnauthorisedCaller();

        (bool success,) = address(caller).call(abi.encodeCall(caller.increase, (lab, 1)));

        require(!success, "non-owner call should revert");
        require(lab.counter() == 10, "unauthorised call changed storage");
    }

    function testConstructorRejectsInitialValueAboveLimit() public {
        try new StateTransitionLab(101, 100) returns (StateTransitionLab) {
            revert("constructor should revert");
        } catch {
            // Expected: contract creation is also an EVM transaction that can revert.
        }
    }
}
