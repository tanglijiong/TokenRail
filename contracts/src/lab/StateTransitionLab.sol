// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

/// @title StateTransitionLab
/// @notice M0-only contract for observing authorization, events, state commits, and reverts.
/// @dev This is deliberately not a TokenRail business contract.
contract StateTransitionLab {
    error NotOwner(address caller);
    error LimitExceeded(uint256 attemptedValue, uint256 limit);
    error IntentionalRollback();

    event CounterChanged(address indexed caller, uint256 previousValue, uint256 newValue);

    address public immutable owner;
    uint256 public immutable limit;
    uint256 public counter;

    constructor(uint256 initialValue, uint256 maximumValue) {
        if (initialValue > maximumValue) {
            revert LimitExceeded(initialValue, maximumValue);
        }

        owner = msg.sender;
        limit = maximumValue;
        counter = initialValue;
    }

    function increaseBy(uint256 amount) external {
        if (msg.sender != owner) {
            revert NotOwner(msg.sender);
        }

        uint256 previousValue = counter;
        uint256 newValue = previousValue + amount;
        if (newValue > limit) {
            revert LimitExceeded(newValue, limit);
        }

        counter = newValue;
        emit CounterChanged(msg.sender, previousValue, newValue);
    }

    /// @notice Changes storage and then reverts so tests can prove transaction rollback.
    function increaseThenRevert(uint256 amount) external {
        if (msg.sender != owner) {
            revert NotOwner(msg.sender);
        }

        counter += amount;
        revert IntentionalRollback();
    }
}
