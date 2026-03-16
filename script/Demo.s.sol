// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "forge-std/Script.sol";
import { SentinelVault } from "../contracts/SentinelVault_Complete.sol";
import { SentinelUSD } from "../contracts/SentinelUSD.sol";
import { MockPriceOracle } from "../contracts/MockPriceOracle.sol";

/*
 * Demo Script — Full Sentinel Flow
 *
 * Stages:
 *   1. depositCollateral  — depositor sends 100 DOT to the vault
 *   2. mintStablecoin     — mints max safe sUSD (at 150% collateral ratio)
 *   3. crashPrice         — oracle owner drops DOT price to trigger bot
 *   4. Bot detects it     — wait 60s for monitoring cycle, watch Telegram
 *
 * RUN STAGE 1+2 (deposit + mint):
 *   forge script script/Demo.s.sol:Demo_DepositAndMint \
 *     --rpc-url https://services.polkadothub-rpc.com/testnet \
 *     --private-key $DEPLOYER_PRIVATE_KEY \
 *     --broadcast --legacy -vvvv
 *
 * RUN STAGE 3 (crash the price — triggers the bot):
 *   forge script script/Demo.s.sol:Demo_CrashPrice \
 *     --rpc-url https://services.polkadothub-rpc.com/testnet \
 *     --private-key $DEPLOYER_PRIVATE_KEY \
 *     --broadcast --legacy -vvvv
 *
 * CONTRACT ADDRESSES (deployed 2026-03-08):
 *   SentinelVault   : 0xb57a5D2621Ba0D149c0F1076009D42f107d33579
 *   SentinelUSD     : 0x9Cc3efD0e03AfE50A04Cc5643bE7509023b193A0
 *   MockPriceOracle : 0xdAeDe10E2aB19201485283aFdACbE2E296435aE6
 */

address constant VAULT = 0xb57a5D2621Ba0D149c0F1076009D42f107d33579;
address constant SUSD = 0x9Cc3efD0e03AfE50A04Cc5643bE7509023b193A0;
address constant ORACLE = 0xdAeDe10E2aB19201485283aFdACbE2E296435aE6;

// ─────────────────────────────────────────────────────────────
//  STAGE 1+2: Deposit 100 DOT + Mint max safe sUSD
// ─────────────────────────────────────────────────────────────
contract Demo_DepositAndMint is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("DEPLOYER_PRIVATE_KEY");
        address depositor = vm.addr(deployerKey);

        SentinelVault vault = SentinelVault(payable(VAULT));
        SentinelUSD sUSD = SentinelUSD(SUSD);

        // Deposit 100 DOT (100 * 1e18 wei)
        uint256 dotToDeposit = 100 ether;

        // At $1.00/DOT and 150% collateral ratio:
        //   collateralUSD = 100 * 1e18 * 1e8 (price) / 1e8 = 100 USD in 1e18 precision
        //   maxSUSD = collateralUSD * 100 / 150 = ~66.66 sUSD
        //   We mint 60 sUSD to be safe (~166% HF)
        uint256 susdToMint = 60 ether; // 60 sUSD

        console.log("==============================================");
        console.log("  SENTINEL DEMO - Stage 1: Deposit & Mint");
        console.log("==============================================");
        console.log("Depositor:", depositor);
        console.log("Depositing:", dotToDeposit, "wei DOT (100 DOT)");
        console.log("Minting:   ", susdToMint, "wei sUSD (60 sUSD)");

        vm.startBroadcast(deployerKey);

        // Step 1: Deposit 100 DOT as collateral
        vault.depositCollateral{ value: dotToDeposit }();
        console.log("Collateral deposited!");

        // Step 2: Mint 60 sUSD (safely under the 150% ceiling)
        vault.mintStablecoin(susdToMint);
        console.log("sUSD minted!");

        vm.stopBroadcast();

        // Print current health factor
        (
            uint256 collateralDOT,
            uint256 collateralUSDT,
            uint256 mintedSUSD,
            uint256 collateralUSD,
            uint256 healthFactor,
            bool paused,
            bool isAegisActive,
            uint256 activeCollateralRatio
        ) = vault.getPosition(depositor);

        console.log("");
        console.log("==============================================");
        console.log("  POSITION CREATED");
        console.log("==============================================");
        console.log("Collateral DOT (wei):", collateralDOT);
        console.log("Minted sUSD    (wei):", mintedSUSD);
        console.log("Collateral USD (wei):", collateralUSD);
        console.log("Health Factor  (1e18):", healthFactor);
        console.log("Paused:", paused);
        console.log("");
        console.log("sUSD balance:", sUSD.balanceOf(depositor));
        console.log("");
        console.log("NEXT: Run Demo_CrashPrice to trigger the Sentinel bot!");
        console.log("Make sure the bot is running first:");
        console.log(
            "  source .env && java -jar Java_Agent/target/sentinel-bot-1.0.0.jar"
        );
    }
}

// ─────────────────────────────────────────────────────────────
//  STAGE 3: Crash the oracle price to trigger the bot
// ─────────────────────────────────────────────────────────────
contract Demo_CrashPrice is Script {
    function run() external {
        uint256 deployerKey = vm.envUint("DEPLOYER_PRIVATE_KEY");
        address deployer = vm.addr(deployerKey);

        MockPriceOracle oracle = MockPriceOracle(ORACLE);
        SentinelVault vault = SentinelVault(payable(VAULT));

        // Drop DOT price from $1.00 to $0.60
        // At $0.60/DOT with 100 DOT collateral and 60 sUSD debt:
        //   collateralUSD = 100 * 0.60 = $60
        //   HF = ($60 / $60) * 100 = 100% — CRITICAL (below 120% threshold)
        //   Bot should trigger emergencyRebalance instantly
        int256 crashedPrice = 60_000_000; // $0.60 (8 decimals)

        console.log("==============================================");
        console.log("  SENTINEL DEMO - Stage 3: PRICE CRASH");
        console.log("==============================================");
        console.log("Crashing DOT price to $0.60...");
        console.log("Expected HF after crash: ~100% (CRITICAL)");
        console.log("Bot should detect this within 60 seconds!");

        vm.startBroadcast(deployerKey);
        oracle.setPrice(crashedPrice);
        vm.stopBroadcast();

        console.log("Price crashed!");
        console.log("");

        // Show updated position state
        (, , , uint256 collateralUSD, uint256 healthFactor, , , ) = vault
            .getPosition(deployer);

        console.log("Updated collateral USD:", collateralUSD);
        console.log("Updated health factor :", healthFactor);
        console.log("");
        console.log("==============================================");
        console.log("  WATCH YOUR TELEGRAM NOW");
        console.log("==============================================");
        console.log("The Sentinel bot will fire within 60 seconds:");
        console.log("  1. Detect HF below 120% threshold");
        console.log("  2. Send CRITICAL alert to your Telegram");
        console.log("  3. Call emergencyRebalance() on-chain");
        console.log("  4. Send SUCCESS/FAILURE alert with TX hash");
        console.log("");
        console.log("View live logs: tail -f /tmp/sentinel_bot.log");
        console.log("Explorer: https://polkadot-hub-testnet.blockscout.com");
    }
}

// ─────────────────────────────────────────────────────────────
//  UTILS: Read current position state (no broadcast)
// ─────────────────────────────────────────────────────────────
contract Demo_ReadPosition is Script {
    function run() external view {
        uint256 deployerKey = vm.envUint("DEPLOYER_PRIVATE_KEY");
        address user = vm.addr(deployerKey);

        SentinelVault vault = SentinelVault(payable(VAULT));
        MockPriceOracle oracle = MockPriceOracle(ORACLE);

        (int256 dotPrice, uint256 updatedAt) = oracle.getLatestPrice();
        (
            uint256 collateralDOT,
            uint256 collateralUSDT,
            uint256 mintedSUSD,
            uint256 collateralUSD,
            uint256 healthFactor,
            bool paused,
            bool isAegisActive,
            uint256 activeCollateralRatio
        ) = vault.getPosition(user);

        console.log("==============================================");
        console.log("  CURRENT POSITION STATE");
        console.log("==============================================");
        console.log("DOT/USD price (8dec):", uint256(dotPrice));
        console.log("Price update time   :", updatedAt);
        console.log("Collateral DOT (wei):", collateralDOT);
        console.log("Minted sUSD    (wei):", mintedSUSD);
        console.log("Collateral USD (wei):", collateralUSD);
        console.log("Health Factor  (1e18):", healthFactor);
        console.log("Paused              :", paused);
        console.log("All position holders:", vault.getAllUsers().length);
    }
}

// Make getAllUsers and getAllUsers return value accessible
interface ExtVault {
    function getAllUsers() external view returns (address[] memory);

    function getPosition(
        address user
    )
        external
        view
        returns (
            uint256 collateralDOT,
            uint256 collateralUSDT,
            uint256 mintedSUSD,
            uint256 collateralUSD,
            uint256 healthFactor,
            bool positionPaused,
            bool isAegisActive,
            uint256 activeCollateralRatio
        );
}
