// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { IdentityRegistry } from "../identity/IdentityRegistry.sol";
import { RestrictedERC20 } from "./RestrictedERC20.sol";

/// @title DepositToken
/// @notice A two-decimal representation of one simulated bank's CNY deposit liability.
contract DepositToken is RestrictedERC20 {
    error BusinessIdAlreadyUsed(bytes32 businessId);
    error InvalidBusinessId();
    error InvalidAmount();

    bytes32 public constant ISSUER_ROLE = keccak256("ISSUER_ROLE");

    event MintConverted(bytes32 indexed conversionId, address indexed recipient, uint256 amount);
    event RedemptionRequested(bytes32 indexed redemptionId, address indexed holder, uint256 amount);

    mapping(bytes32 conversionId => bool used) public usedConversionIds;
    mapping(bytes32 redemptionId => bool used) public usedRedemptionIds;

    constructor(IdentityRegistry registry, address admin, address issuer)
        RestrictedERC20("TokenRail CNY Deposit", "TRCNY", registry, admin)
    {
        if (issuer == address(0)) {
            revert InvalidAccount();
        }
        _grantRole(ISSUER_ROLE, issuer);
    }

    function decimals() public pure override returns (uint8) {
        return 2;
    }

    function mintForConversion(bytes32 conversionId, address recipient, uint256 amount)
        external
        onlyRole(ISSUER_ROLE)
    {
        _requireUnusedId(conversionId, usedConversionIds);
        if (amount == 0) {
            revert InvalidAmount();
        }

        usedConversionIds[conversionId] = true;
        _mint(recipient, amount);
        emit MintConverted(conversionId, recipient, amount);
    }

    function requestRedemption(bytes32 redemptionId, uint256 amount) external {
        _requireUnusedId(redemptionId, usedRedemptionIds);
        if (amount == 0) {
            revert InvalidAmount();
        }

        usedRedemptionIds[redemptionId] = true;
        _burn(msg.sender, amount);
        emit RedemptionRequested(redemptionId, msg.sender, amount);
    }

    function _isEligible(address account) internal view override returns (bool) {
        return identityRegistry.isEligibleForCash(account);
    }

    function _requireUnusedId(bytes32 businessId, mapping(bytes32 => bool) storage usedIds)
        private
        view
    {
        if (businessId == bytes32(0)) {
            revert InvalidBusinessId();
        }
        if (usedIds[businessId]) {
            revert BusinessIdAlreadyUsed(businessId);
        }
    }
}
