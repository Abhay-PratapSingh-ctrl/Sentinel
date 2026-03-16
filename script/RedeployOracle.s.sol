// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "forge-std/Script.sol";
import { MockPriceOracle } from "../src/MockPriceOracle.sol";

contract RedeployOracle is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("DEPLOYER_PRIVATE_KEY");
        vm.startBroadcast(deployerKey);

        MockPriceOracle oracle = new MockPriceOracle(
            200_000_000 // Initial DOT price = $2.00
        );
        console.log("New MockPriceOracle deployed:", address(oracle));

        vm.stopBroadcast();
    }
}
