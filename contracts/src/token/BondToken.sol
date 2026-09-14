// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { IdentityRegistry } from "../identity/IdentityRegistry.sol";
import { RestrictedERC20 } from "./RestrictedERC20.sol";

/// @title BondToken
/// @notice A zero-decimal representation of the synthetic ABC2029 bond series.
contract BondToken is RestrictedERC20 {
    error BusinessIdAlreadyUsed(bytes32 businessId);
    error InvalidBusinessId();
    error InvalidAmount();
    error InvalidBondTerms();
    error IssuanceCapExceeded(uint256 attemptedSupply, uint256 issuanceCap);

    bytes32 public constant ISSUER_ROLE = keccak256("ISSUER_ROLE");

    event BondIssued(bytes32 indexed issueId, address indexed recipient, uint256 quantity);

    bytes32 public immutable assetRef;
    bytes32 public immutable issuerRef;
    bytes3 public immutable currency;
    uint256 public immutable faceValue;
    uint16 public immutable couponBps;
    uint64 public immutable maturity;
    uint256 public immutable issuanceCap;

    mapping(bytes32 issueId => bool used) public usedIssueIds;

    constructor(
        IdentityRegistry registry,
        address admin,
        address issuer,
        bytes32 assetReference,
        bytes32 issuerReference,
        bytes3 denominationCurrency,
        uint256 faceValueInMinorUnits,
        uint16 annualCouponBps,
        uint64 maturityTimestamp,
        uint256 maximumIssuance
    ) RestrictedERC20("TokenRail ABC2029 Bond", "ABC2029", registry, admin) {
        if (issuer == address(0)) {
            revert InvalidAccount();
        }
        if (
            assetReference == bytes32(0) || issuerReference == bytes32(0)
                || denominationCurrency == bytes3(0) || faceValueInMinorUnits == 0
                || annualCouponBps > 10_000
                // Maturity is a day-scale product term, not a miner-sensitive short deadline.
                // forge-lint: disable-next-line(block-timestamp)
                || maturityTimestamp <= block.timestamp || maximumIssuance == 0
        ) {
            revert InvalidBondTerms();
        }

        assetRef = assetReference;
        issuerRef = issuerReference;
        currency = denominationCurrency;
        faceValue = faceValueInMinorUnits;
        couponBps = annualCouponBps;
        maturity = maturityTimestamp;
        issuanceCap = maximumIssuance;
        _grantRole(ISSUER_ROLE, issuer);
    }

    function decimals() public pure override returns (uint8) {
        return 0;
    }

    function issue(bytes32 issueId, address recipient, uint256 quantity)
        external
        onlyRole(ISSUER_ROLE)
    {
        if (issueId == bytes32(0)) {
            revert InvalidBusinessId();
        }
        if (quantity == 0) {
            revert InvalidAmount();
        }
        if (usedIssueIds[issueId]) {
            revert BusinessIdAlreadyUsed(issueId);
        }

        uint256 attemptedSupply = totalSupply() + quantity;
        if (attemptedSupply > issuanceCap) {
            revert IssuanceCapExceeded(attemptedSupply, issuanceCap);
        }

        usedIssueIds[issueId] = true;
        _mint(recipient, quantity);
        emit BondIssued(issueId, recipient, quantity);
    }

    function _isEligible(address account) internal view override returns (bool) {
        return identityRegistry.isEligibleForBond(account);
    }
}
