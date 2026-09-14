// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { AccessControl } from "@openzeppelin/contracts/access/AccessControl.sol";

/// @title IdentityRegistry
/// @notice Stores the synthetic participation rules used by the TokenRail local lab.
/// @dev This registry demonstrates on-chain eligibility. It is not a real KYC service.
contract IdentityRegistry is AccessControl {
    error InvalidAccount();
    error InvalidParticipantReference();
    error IdentityNotRegistered(address account);
    error SystemAccountMustBeContract(address account);

    bytes32 public constant COMPLIANCE_ROLE = keccak256("COMPLIANCE_ROLE");

    struct Identity {
        bytes32 participantRef;
        uint64 validUntil;
        bool active;
        bool frozen;
        bool canHoldCash;
        bool canHoldBond;
    }

    event IdentityUpdated(
        address indexed account,
        bytes32 indexed participantRef,
        uint64 validUntil,
        bool active,
        bool frozen,
        bool canHoldCash,
        bool canHoldBond
    );
    event IdentityFrozen(address indexed account, bool frozen);
    event SystemAccountUpdated(address indexed account, bool enabled);

    mapping(address account => Identity identity) private identities;
    mapping(address account => bool enabled) public isSystemAccount;

    constructor(address admin, address complianceOfficer) {
        if (admin == address(0) || complianceOfficer == address(0)) {
            revert InvalidAccount();
        }

        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(COMPLIANCE_ROLE, complianceOfficer);
    }

    function setIdentity(
        address account,
        bytes32 participantRef,
        uint64 validUntil,
        bool active,
        bool frozen,
        bool canHoldCash,
        bool canHoldBond
    ) external onlyRole(COMPLIANCE_ROLE) {
        if (account == address(0)) {
            revert InvalidAccount();
        }
        if (participantRef == bytes32(0)) {
            revert InvalidParticipantReference();
        }

        identities[account] = Identity({
            participantRef: participantRef,
            validUntil: validUntil,
            active: active,
            frozen: frozen,
            canHoldCash: canHoldCash,
            canHoldBond: canHoldBond
        });

        emit IdentityUpdated(
            account, participantRef, validUntil, active, frozen, canHoldCash, canHoldBond
        );
    }

    function setFrozen(address account, bool frozen) external onlyRole(COMPLIANCE_ROLE) {
        Identity storage identity = identities[account];
        if (identity.participantRef == bytes32(0)) {
            revert IdentityNotRegistered(account);
        }

        identity.frozen = frozen;
        emit IdentityFrozen(account, frozen);
    }

    function setSystemAccount(address account, bool enabled) external onlyRole(DEFAULT_ADMIN_ROLE) {
        if (account == address(0)) {
            revert InvalidAccount();
        }
        if (enabled && account.code.length == 0) {
            revert SystemAccountMustBeContract(account);
        }

        isSystemAccount[account] = enabled;
        emit SystemAccountUpdated(account, enabled);
    }

    function getIdentity(address account) external view returns (Identity memory) {
        return identities[account];
    }

    function isEligibleForCash(address account) external view returns (bool) {
        if (isSystemAccount[account]) {
            return true;
        }

        Identity memory identity = identities[account];
        return _isCurrent(identity) && identity.canHoldCash;
    }

    function isEligibleForBond(address account) external view returns (bool) {
        if (isSystemAccount[account]) {
            return true;
        }

        Identity memory identity = identities[account];
        return _isCurrent(identity) && identity.canHoldBond;
    }

    function _isCurrent(Identity memory identity) private view returns (bool) {
        // Eligibility is a coarse, day-scale rule; validator timestamp tolerance is immaterial here.
        // forge-lint: disable-next-line(block-timestamp)
        bool withinValidityPeriod = block.timestamp < identity.validUntil;
        return identity.participantRef != bytes32(0) && identity.active && !identity.frozen
            && withinValidityPeriod;
    }
}
