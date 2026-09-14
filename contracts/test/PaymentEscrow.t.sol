// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { IdentityRegistry } from "../src/identity/IdentityRegistry.sol";
import { DepositToken } from "../src/token/DepositToken.sol";
import { PaymentEscrow } from "../src/payment/PaymentEscrow.sol";

interface Vm {
    function warp(uint256 timestamp) external;
}

contract PaymentActor {
    function approve(DepositToken token, address spender, uint256 amount) external {
        token.approve(spender, amount);
    }

    function fund(
        PaymentEscrow escrow,
        bytes32 id,
        address payee,
        address attestor,
        uint256 amount,
        uint64 deadline
    ) external {
        escrow.fund(id, payee, attestor, amount, deadline);
    }

    function release(PaymentEscrow escrow, bytes32 id) external {
        escrow.release(id);
    }

    function refund(PaymentEscrow escrow, bytes32 id) external {
        escrow.refund(id);
    }

    function transfer(DepositToken token, address recipient, uint256 amount) external {
        require(token.transfer(recipient, amount), "transfer failed");
    }
}

contract PaymentEscrowTest {
    Vm private constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));
    IdentityRegistry private registry;
    DepositToken private cash;
    PaymentEscrow private escrow;
    PaymentActor private payer;
    PaymentActor private payee;
    PaymentActor private attestor;
    PaymentActor private outsider;
    uint64 private deadline;

    function setUp() public {
        payer = new PaymentActor();
        payee = new PaymentActor();
        attestor = new PaymentActor();
        outsider = new PaymentActor();
        registry = new IdentityRegistry(address(this), address(this));
        cash = new DepositToken(registry, address(this), address(this));
        escrow = new PaymentEscrow(cash, registry, address(this), address(attestor));
        registry.setSystemAccount(address(escrow), true);
        _identity(address(payer));
        _identity(address(payee));
        _identity(address(attestor));
        _identity(address(outsider));
        cash.mintForConversion(keccak256("opening"), address(payer), 10_000);
        payer.approve(cash, address(escrow), type(uint256).max);
        deadline = uint64(block.timestamp + 1 days);
    }

    function testFundAndReleaseOnlyByNamedAttestor() public {
        bytes32 id = keccak256("release");
        payer.fund(escrow, id, address(payee), address(attestor), 1_000, deadline);
        require(cash.balanceOf(address(payee)) == 0, "paid before attestation");
        require(escrow.outstanding() == 1_000, "liability missing");

        (bool unauthorized,) =
            address(outsider).call(abi.encodeCall(outsider.release, (escrow, id)));
        require(!unauthorized, "outsider released payment");
        attestor.release(escrow, id);
        require(cash.balanceOf(address(payee)) == 1_000, "payee not paid");
        require(escrow.outstanding() == 0, "liability not cleared");
    }

    function testRefundOnlyAfterDeadlineAndTerminalStateCannotRepeat() public {
        bytes32 id = keccak256("refund");
        payer.fund(escrow, id, address(payee), address(attestor), 500, deadline);
        (bool early,) = address(payer).call(abi.encodeCall(payer.refund, (escrow, id)));
        require(!early, "early refund succeeded");
        vm.warp(deadline);
        payer.refund(escrow, id);
        require(cash.balanceOf(address(payer)) == 10_000, "payer not refunded");
        (bool repeat,) = address(payer).call(abi.encodeCall(payer.refund, (escrow, id)));
        require(!repeat, "terminal payment repeated");
        (bool releaseAfterRefund,) =
            address(attestor).call(abi.encodeCall(attestor.release, (escrow, id)));
        require(!releaseAfterRefund, "released after refund");
    }

    function testFrozenPayeeKeepsFundsUntilRefundIsAllowed() public {
        bytes32 id = keccak256("hold");
        payer.fund(escrow, id, address(payee), address(attestor), 700, deadline);
        registry.setFrozen(address(payee), true);
        (bool released,) = address(attestor).call(abi.encodeCall(attestor.release, (escrow, id)));
        require(!released, "paid frozen recipient");
        require(escrow.outstanding() == 700, "failed release changed liability");
        vm.warp(deadline);
        payer.refund(escrow, id);
        require(escrow.outstanding() == 0, "refund did not clear liability");
    }

    function testPauseBlocksFundAndReleaseButDoesNotBypassRefundRules() public {
        bytes32 id = keccak256("paused");
        payer.fund(escrow, id, address(payee), address(attestor), 300, deadline);
        escrow.pause();
        (bool releaseWhilePaused,) =
            address(attestor).call(abi.encodeCall(attestor.release, (escrow, id)));
        require(!releaseWhilePaused, "release ignored pause");
        vm.warp(deadline);
        payer.refund(escrow, id);
        require(cash.balanceOf(address(payer)) == 10_000, "refund blocked by escrow pause");
    }

    function testDirectDonationDoesNotBecomePaymentLiability() public {
        payer.transfer(cash, address(escrow), 200);
        require(cash.balanceOf(address(escrow)) == 200, "donation missing");
        require(escrow.outstanding() == 0, "donation counted as liability");
    }

    function testInvalidTermsAndDuplicateIdAreRejected() public {
        bytes32 id = keccak256("terms");
        payer.fund(escrow, id, address(payee), address(attestor), 100, deadline);
        (bool duplicate,) = address(payer)
            .call(
                abi.encodeCall(
                    payer.fund, (escrow, id, address(payee), address(attestor), 100, deadline)
                )
            );
        require(!duplicate, "duplicate id accepted");
        (bool sameParty,) = address(payer)
            .call(
                abi.encodeCall(
                    payer.fund,
                    (escrow, keccak256("same"), address(payer), address(attestor), 100, deadline)
                )
            );
        require(!sameParty, "identical payer/payee accepted");
    }

    function _identity(address account) private {
        registry.setIdentity(
            account,
            keccak256(abi.encode(account)),
            uint64(block.timestamp + 30 days),
            true,
            false,
            true,
            true
        );
    }
}
