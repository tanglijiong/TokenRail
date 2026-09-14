// SPDX-License-Identifier: MIT
pragma solidity 0.8.30;

/// @notice Minimal target configuration surface consumed by Forge's invariant runner.
abstract contract InvariantTargeting {
    struct FuzzSelector {
        address addr;
        bytes4[] selectors;
    }

    struct FuzzArtifactSelector {
        string artifact;
        bytes4[] selectors;
    }

    struct FuzzInterface {
        address addr;
        string[] artifacts;
    }

    address[] private invariantTargetContracts;

    function _targetContract(address target) internal {
        invariantTargetContracts.push(target);
    }

    function targetContracts() external view returns (address[] memory) {
        return invariantTargetContracts;
    }

    function excludeContracts() external pure returns (address[] memory result) {
        return result;
    }

    function targetSenders() external pure returns (address[] memory result) {
        return result;
    }

    function excludeSenders() external pure returns (address[] memory result) {
        return result;
    }

    function targetArtifacts() external pure returns (string[] memory result) {
        return result;
    }

    function excludeArtifacts() external pure returns (string[] memory result) {
        return result;
    }

    function targetSelectors() external pure returns (FuzzSelector[] memory result) {
        return result;
    }

    function excludeSelectors() external pure returns (FuzzSelector[] memory result) {
        return result;
    }

    function targetArtifactSelectors()
        external
        pure
        returns (FuzzArtifactSelector[] memory result)
    {
        return result;
    }

    function targetInterfaces() external pure returns (FuzzInterface[] memory result) {
        return result;
    }
}
