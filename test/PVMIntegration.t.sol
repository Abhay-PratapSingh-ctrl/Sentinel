// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "forge-std/Test.sol";
import "../src/SentinelVault.sol";
import "../src/MockPriceOracle.sol";

contract PVMIntegrationTest is Test {
    SentinelVault public vault;
    MockPriceOracle public oracle;
    address public guardian = address(0x1);
    address public user = address(0x2);

    function setUp() public {
        oracle = new MockPriceOracle(100_000_000); // $1.00
        vault = new SentinelVault(address(oracle), guardian);

        // Setup initial position
        vm.deal(user, 100 ether);
        vm.prank(user);
        vault.depositCollateral{ value: 10 ether }();
        vm.prank(user);
        vault.mintStablecoin(5 ether); // $10 collateral / $5 debt = 200% HF
    }

    function testHealthFactorStandard() public {
        vault.setPVMConfig(bytes32(0), false); // Disable PVM
        uint256 hf = vault.getHealthFactor(user);
        assertEq(hf, 2e18); // 200%
    }

    function testHealthFactorPVM_Fallback() public {
        // We don't etch anything at 0x420, so the call will fail.
        // The vault should catch the failure and fallback to Solidity math.
        vault.setPVMConfig(bytes32(uint256(123)), true); // Enable PVM

        // In Foundry, calling an empty address reverts if not handled.
        // We can mock the call to return a specific value or just let it fail.
        vm.mockCallRevert(
            address(0x420),
            abi.encodeWithSelector(IPolkaVM.execute.selector),
            "PVM Not Found"
        );

        uint256 hf = vault.getHealthFactor(user);
        assertEq(hf, 2e18); // Fallback works
    }

    function testHealthFactorPVM_Success() public {
        vault.setPVMConfig(bytes32(uint256(123)), true);

        // Mock a successful PVM execution returning 250% HF
        vm.mockCall(
            address(0x420),
            abi.encodeWithSelector(IPolkaVM.execute.selector),
            abi.encode(abi.encode(2.5e18))
        );

        uint256 hf = vault.getHealthFactor(user);
        assertEq(hf, 2.5e18);
    }
}
