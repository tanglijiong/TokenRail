// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { IdentityRegistry } from "../src/identity/IdentityRegistry.sol";
import { BondToken } from "../src/token/BondToken.sol";
import { DepositToken } from "../src/token/DepositToken.sol";

contract TokenActor {
    function setIdentity(
        IdentityRegistry registry,
        address account,
        bytes32 participantRef,
        uint64 validUntil,
        bool active,
        bool frozen,
        bool canHoldCash,
        bool canHoldBond
    ) external {
        registry.setIdentity(
            account, participantRef, validUntil, active, frozen, canHoldCash, canHoldBond
        );
    }

    function mintDeposit(
        DepositToken token,
        bytes32 conversionId,
        address recipient,
        uint256 amount
    ) external {
        token.mintForConversion(conversionId, recipient, amount);
    }

    function redeemDeposit(DepositToken token, bytes32 redemptionId, uint256 amount) external {
        token.requestRedemption(redemptionId, amount);
    }

    function transferDeposit(DepositToken token, address recipient, uint256 amount) external {
        require(token.transfer(recipient, amount), "deposit transfer returned false");
    }

    function approveDeposit(DepositToken token, address spender, uint256 amount) external {
        token.approve(spender, amount);
    }

    function transferDepositFrom(
        DepositToken token,
        address sender,
        address recipient,
        uint256 amount
    ) external {
        require(
            token.transferFrom(sender, recipient, amount), "deposit transferFrom returned false"
        );
    }

    function issueBond(BondToken token, bytes32 issueId, address recipient, uint256 quantity)
        external
    {
        token.issue(issueId, recipient, quantity);
    }

    function transferBond(BondToken token, address recipient, uint256 quantity) external {
        require(token.transfer(recipient, quantity), "bond transfer returned false");
    }

    function setSystemAccount(IdentityRegistry registry, address account, bool enabled) external {
        registry.setSystemAccount(account, enabled);
    }
}

abstract contract M1Fixture {
    IdentityRegistry internal registry;
    DepositToken internal deposit;
    BondToken internal bond;
    TokenActor internal investorA;
    TokenActor internal investorB;
    TokenActor internal investorCashOnly;
    TokenActor internal unregistered;

    bytes32 internal constant INVESTOR_A = keccak256("INVESTOR_A");
    bytes32 internal constant INVESTOR_B = keccak256("INVESTOR_B");
    bytes32 internal constant CASH_ONLY = keccak256("CASH_ONLY");
    bytes32 internal constant ASSET_REF =
        0x4142433230323900000000000000000000000000000000000000000000000000;
    bytes32 internal constant ISSUER_REF =
        0x4142435f42414e4b000000000000000000000000000000000000000000000000;
    bytes3 internal constant CNY = 0x434e59;

    function setUp() public virtual {
        registry = new IdentityRegistry(address(this), address(this));
        deposit = new DepositToken(registry, address(this), address(this));
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

        investorA = new TokenActor();
        investorB = new TokenActor();
        investorCashOnly = new TokenActor();
        unregistered = new TokenActor();

        _register(address(investorA), INVESTOR_A, true, true);
        _register(address(investorB), INVESTOR_B, true, true);
        _register(address(investorCashOnly), CASH_ONLY, true, false);
    }

    function _register(address account, bytes32 participantRef, bool canHoldCash, bool canHoldBond)
        internal
    {
        registry.setIdentity(
            account, participantRef, type(uint64).max, true, false, canHoldCash, canHoldBond
        );
    }

    function _callFails(address target, bytes memory callData) internal returns (bool) {
        (bool success,) = target.call(callData);
        return !success;
    }
}

contract IdentityRegistryTest is M1Fixture {
    function testEligibilityReflectsAssetPermissionFreezeAndExpiry() public {
        require(registry.isEligibleForCash(address(investorA)), "cash eligibility missing");
        require(registry.isEligibleForBond(address(investorA)), "bond eligibility missing");
        require(
            !registry.isEligibleForBond(address(investorCashOnly)),
            "cash-only identity can hold bonds"
        );

        registry.setFrozen(address(investorA), true);
        require(!registry.isEligibleForCash(address(investorA)), "frozen identity is eligible");

        registry.setIdentity(
            address(investorA), INVESTOR_A, uint64(block.timestamp), true, false, true, true
        );
        require(!registry.isEligibleForCash(address(investorA)), "expired identity is eligible");

        registry.setIdentity(
            address(investorA), INVESTOR_A, type(uint64).max, false, false, true, true
        );
        require(!registry.isEligibleForCash(address(investorA)), "inactive identity is eligible");
    }

    function testOnlyComplianceRoleCanChangeIdentity() public {
        bool failed = _callFails(
            address(unregistered),
            abi.encodeCall(
                unregistered.setIdentity,
                (
                    registry,
                    address(0xBEEF),
                    keccak256("UNAUTHORISED"),
                    type(uint64).max,
                    true,
                    false,
                    true,
                    true
                )
            )
        );

        require(failed, "non-compliance actor changed an identity");
        require(!registry.isEligibleForCash(address(0xBEEF)), "unauthorised identity was stored");
    }

    function testOnlyDeployedContractsCanBecomeSystemAccounts() public {
        bool eoaRegistrationFailed = _callFails(
            address(registry), abi.encodeCall(registry.setSystemAccount, (address(0xBEEF), true))
        );
        require(eoaRegistrationFailed, "EOA became a system account");

        registry.setSystemAccount(address(unregistered), true);
        require(registry.isEligibleForCash(address(unregistered)), "system cash access missing");
        require(registry.isEligibleForBond(address(unregistered)), "system bond access missing");

        registry.setSystemAccount(address(unregistered), false);
        require(!registry.isEligibleForCash(address(unregistered)), "system access not revoked");
    }

    function testOnlyAdminCanSetSystemAccount() public {
        bool failed = _callFails(
            address(investorA),
            abi.encodeCall(investorA.setSystemAccount, (registry, address(unregistered), true))
        );

        require(failed, "non-admin enabled a system account");
        require(!registry.isSystemAccount(address(unregistered)), "system account flag changed");
    }

    function testCannotFreezeUnknownIdentity() public {
        bool failed = _callFails(
            address(registry), abi.encodeCall(registry.setFrozen, (address(unregistered), true))
        );
        require(failed, "unknown identity was frozen without registration");
    }
}

contract DepositTokenTest is M1Fixture {
    bytes32 private constant CONVERSION_1 = keccak256("CONVERSION_1");
    bytes32 private constant REDEMPTION_1 = keccak256("REDEMPTION_1");

    function testMintTransferAndRedeemUseTwoDecimalMinorUnits() public {
        deposit.mintForConversion(CONVERSION_1, address(investorA), 150_000_000);
        investorA.transferDeposit(deposit, address(investorB), 50_000_000);
        investorB.redeemDeposit(deposit, REDEMPTION_1, 10_000_000);

        require(deposit.decimals() == 2, "deposit decimals changed");
        require(deposit.balanceOf(address(investorA)) == 100_000_000, "sender cash mismatch");
        require(deposit.balanceOf(address(investorB)) == 40_000_000, "recipient cash mismatch");
        require(deposit.totalSupply() == 140_000_000, "redemption did not reduce supply");
        require(deposit.usedConversionIds(CONVERSION_1), "conversion id not recorded");
        require(deposit.usedRedemptionIds(REDEMPTION_1), "redemption id not recorded");
    }

    function testConversionIdCannotMintTwice() public {
        deposit.mintForConversion(CONVERSION_1, address(investorA), 100);

        bool failed = _callFails(
            address(deposit),
            abi.encodeCall(deposit.mintForConversion, (CONVERSION_1, address(investorA), 100))
        );

        require(failed, "duplicate conversion minted twice");
        require(deposit.totalSupply() == 100, "duplicate conversion changed supply");
    }

    function testOnlyIssuerCanMint() public {
        bool failed = _callFails(
            address(unregistered),
            abi.encodeCall(
                unregistered.mintDeposit, (deposit, CONVERSION_1, address(investorA), 100)
            )
        );

        require(failed, "non-issuer minted deposits");
        require(deposit.totalSupply() == 0, "unauthorised mint changed supply");
    }

    function testRejectedMintDoesNotConsumeConversionId() public {
        bool failed = _callFails(
            address(deposit),
            abi.encodeCall(deposit.mintForConversion, (CONVERSION_1, address(unregistered), 100))
        );
        require(failed, "mint reached an ineligible recipient");
        require(!deposit.usedConversionIds(CONVERSION_1), "failed mint consumed its id");

        _register(address(unregistered), keccak256("NOW_ELIGIBLE"), true, false);
        deposit.mintForConversion(CONVERSION_1, address(unregistered), 100);
        require(deposit.balanceOf(address(unregistered)) == 100, "reused conversion did not mint");
    }

    function testZeroIdsAndAmountsAreRejected() public {
        bool zeroIdFailed = _callFails(
            address(deposit),
            abi.encodeCall(deposit.mintForConversion, (bytes32(0), address(investorA), 100))
        );
        bool zeroAmountFailed = _callFails(
            address(deposit),
            abi.encodeCall(deposit.mintForConversion, (CONVERSION_1, address(investorA), 0))
        );

        require(zeroIdFailed && zeroAmountFailed, "invalid conversion was accepted");
        require(deposit.totalSupply() == 0, "invalid conversion changed supply");
    }

    function testDirectTransferCannotBypassRecipientEligibility() public {
        deposit.mintForConversion(CONVERSION_1, address(investorA), 100);

        bool failed = _callFails(
            address(investorA),
            abi.encodeCall(investorA.transferDeposit, (deposit, address(unregistered), 40))
        );

        require(failed, "transfer reached an unregistered recipient");
        require(deposit.balanceOf(address(investorA)) == 100, "failed transfer debited sender");
        require(deposit.balanceOf(address(unregistered)) == 0, "failed transfer credited recipient");
    }

    function testTransferFromCannotBypassFrozenRecipient() public {
        deposit.mintForConversion(CONVERSION_1, address(investorA), 100);
        investorA.approveDeposit(deposit, address(investorB), 60);
        registry.setFrozen(address(investorCashOnly), true);

        bool failed = _callFails(
            address(investorB),
            abi.encodeCall(
                investorB.transferDepositFrom,
                (deposit, address(investorA), address(investorCashOnly), 40)
            )
        );

        require(failed, "transferFrom reached a frozen recipient");
        require(deposit.balanceOf(address(investorA)) == 100, "failed transferFrom debited sender");
        require(
            deposit.allowance(address(investorA), address(investorB)) == 60,
            "failed transferFrom consumed allowance"
        );
    }

    function testFrozenHolderCannotTransferOrRedeem() public {
        deposit.mintForConversion(CONVERSION_1, address(investorA), 100);
        registry.setFrozen(address(investorA), true);

        bool transferFailed = _callFails(
            address(investorA),
            abi.encodeCall(investorA.transferDeposit, (deposit, address(investorB), 10))
        );
        bool redemptionFailed = _callFails(
            address(investorA), abi.encodeCall(investorA.redeemDeposit, (deposit, REDEMPTION_1, 10))
        );

        require(transferFailed && redemptionFailed, "frozen holder changed cash state");
        require(deposit.balanceOf(address(investorA)) == 100, "frozen holder balance changed");
        require(!deposit.usedRedemptionIds(REDEMPTION_1), "failed redemption consumed its id");
    }

    function testRedemptionIdCannotBurnTwice() public {
        deposit.mintForConversion(CONVERSION_1, address(investorA), 100);
        investorA.redeemDeposit(deposit, REDEMPTION_1, 30);

        bool failed = _callFails(
            address(investorA), abi.encodeCall(investorA.redeemDeposit, (deposit, REDEMPTION_1, 30))
        );

        require(failed, "redemption id burned twice");
        require(deposit.balanceOf(address(investorA)) == 70, "duplicate redemption changed balance");
        require(deposit.totalSupply() == 70, "duplicate redemption changed supply");
    }

    function testPauseBlocksEveryBalanceUpdate() public {
        deposit.mintForConversion(CONVERSION_1, address(investorA), 100);
        deposit.pause();

        bool transferFailed = _callFails(
            address(investorA),
            abi.encodeCall(investorA.transferDeposit, (deposit, address(investorB), 10))
        );
        bool mintFailed = _callFails(
            address(deposit),
            abi.encodeCall(
                deposit.mintForConversion, (keccak256("CONVERSION_2"), address(investorA), 10)
            )
        );
        bool redemptionFailed = _callFails(
            address(investorA), abi.encodeCall(investorA.redeemDeposit, (deposit, REDEMPTION_1, 10))
        );

        require(transferFailed && mintFailed && redemptionFailed, "paused token changed a balance");
        require(deposit.totalSupply() == 100, "paused operation changed supply");
        require(!deposit.usedRedemptionIds(REDEMPTION_1), "paused redemption consumed its id");

        deposit.unpause();
        investorA.transferDeposit(deposit, address(investorB), 10);
        require(deposit.balanceOf(address(investorB)) == 10, "unpause did not restore transfers");
    }
}

contract BondTokenTest is M1Fixture {
    bytes32 private constant ISSUE_1 = keccak256("ISSUE_1");

    function testBondMetadataAndIssuance() public {
        bond.issue(ISSUE_1, address(investorA), 1_000);

        require(bond.decimals() == 0, "bond must use whole units");
        require(bond.assetRef() == ASSET_REF, "asset reference mismatch");
        require(bond.currency() == CNY, "currency mismatch");
        require(bond.faceValue() == 100_000, "face value mismatch");
        require(bond.couponBps() == 320, "coupon mismatch");
        require(bond.balanceOf(address(investorA)) == 1_000, "issued position mismatch");
        require(bond.totalSupply() == 1_000, "issued supply mismatch");
    }

    function testIssueIdCannotBeReplayed() public {
        bond.issue(ISSUE_1, address(investorA), 1_000);

        bool failed = _callFails(
            address(bond), abi.encodeCall(bond.issue, (ISSUE_1, address(investorA), 1_000))
        );

        require(failed, "issue id was replayed");
        require(bond.totalSupply() == 1_000, "replayed issue changed supply");
    }

    function testIssuanceCapCannotBeExceeded() public {
        bond.issue(ISSUE_1, address(investorA), 99_999);

        bool failed = _callFails(
            address(bond), abi.encodeCall(bond.issue, (keccak256("ISSUE_2"), address(investorA), 2))
        );

        require(failed, "issuance exceeded cap");
        require(bond.totalSupply() == 99_999, "failed issue changed supply");
    }

    function testOnlyIssuerCanIssue() public {
        bool failed = _callFails(
            address(unregistered),
            abi.encodeCall(unregistered.issueBond, (bond, ISSUE_1, address(investorA), 100))
        );

        require(failed, "non-issuer issued bonds");
        require(bond.totalSupply() == 0, "unauthorised issue changed supply");
    }

    function testCashOnlyIdentityCannotReceiveBondByIssueOrTransfer() public {
        bool issueFailed = _callFails(
            address(bond), abi.encodeCall(bond.issue, (ISSUE_1, address(investorCashOnly), 100))
        );
        require(issueFailed, "cash-only identity received bond issuance");
        require(!bond.usedIssueIds(ISSUE_1), "failed issue consumed its id");

        bond.issue(keccak256("ISSUE_2"), address(investorA), 100);
        bool transferFailed = _callFails(
            address(investorA),
            abi.encodeCall(investorA.transferBond, (bond, address(investorCashOnly), 50))
        );

        require(transferFailed, "cash-only identity received a bond transfer");
        require(bond.balanceOf(address(investorA)) == 100, "failed transfer changed position");
    }

    function testFrozenBondHolderCannotTransfer() public {
        bond.issue(ISSUE_1, address(investorA), 100);
        registry.setFrozen(address(investorA), true);

        bool failed = _callFails(
            address(investorA),
            abi.encodeCall(investorA.transferBond, (bond, address(investorB), 50))
        );

        require(failed, "frozen holder transferred bonds");
        require(bond.balanceOf(address(investorA)) == 100, "failed bond transfer changed sender");
        require(bond.balanceOf(address(investorB)) == 0, "failed bond transfer changed recipient");
    }

    function testZeroIssueIdAndQuantityAreRejected() public {
        bool zeroIdFailed = _callFails(
            address(bond), abi.encodeCall(bond.issue, (bytes32(0), address(investorA), 1))
        );
        bool zeroQuantityFailed = _callFails(
            address(bond), abi.encodeCall(bond.issue, (ISSUE_1, address(investorA), 0))
        );

        require(zeroIdFailed && zeroQuantityFailed, "invalid bond issue was accepted");
        require(bond.totalSupply() == 0, "invalid bond issue changed supply");
    }
}
