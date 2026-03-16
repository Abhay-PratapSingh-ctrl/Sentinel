// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import {Script, console} from "forge-std/Script.sol";
import {ERC20Mock} from "../test/ERC20mock.sol";

contract DeployMockUSDTScript is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("DEPLOYER_PRIVATE_KEY");
        vm.startBroadcast(deployerKey);

        ERC20Mock usdt = new ERC20Mock("Tether USD", "USDT", 6);
        console.log("Mock USDT deployed at:", address(usdt));

        vm.stopBroadcast();
    }
}
