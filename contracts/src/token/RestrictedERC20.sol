// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

import { AccessControl } from "@openzeppelin/contracts/access/AccessControl.sol";
import { ERC20 } from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import { ERC20Pausable } from "@openzeppelin/contracts/token/ERC20/extensions/ERC20Pausable.sol";
import { IdentityRegistry } from "../identity/IdentityRegistry.sol";

/// @notice Shared transfer controls for TokenRail's cash and bond representations.
abstract contract RestrictedERC20 is ERC20Pausable, AccessControl {
    error InvalidAccount();
    error InvalidRegistry();
    error IneligibleAccount(address account);

    bytes32 public constant PAUSER_ROLE = keccak256("PAUSER_ROLE");

    IdentityRegistry public immutable identityRegistry;

    constructor(string memory name, string memory symbol, IdentityRegistry registry, address admin)
        ERC20(name, symbol)
    {
        if (address(registry) == address(0)) {
            revert InvalidRegistry();
        }
        if (admin == address(0)) {
            revert InvalidAccount();
        }

        identityRegistry = registry;
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(PAUSER_ROLE, admin);
    }

    function pause() external onlyRole(PAUSER_ROLE) {
        _pause();
    }

    function unpause() external onlyRole(PAUSER_ROLE) {
        _unpause();
    }

    function _isEligible(address account) internal view virtual returns (bool);

    function _update(address from, address to, uint256 value) internal virtual override {
        if (from != address(0) && !_isEligible(from)) {
            revert IneligibleAccount(from);
        }
        if (to != address(0) && !_isEligible(to)) {
            revert IneligibleAccount(to);
        }

        super._update(from, to, value);
    }
}
