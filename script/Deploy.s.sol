// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

import "forge-std/Script.sol";
import {SentinelUSD} from "../src/SentinelUSD.sol";
import {SentinelVault_complete} from "../src/SentinelVault_Complete.sol";
import {MockPriceOracle} from "../src/MockPriceOracle.sol";

// ─────────────────────────────────────────────
//  Interfaces (copied so we don't need imports)
// ─────────────────────────────────────────────

interface ISentinelUSD_Admin {
    function setVault(address _vault) external;
}

// ─────────────────────────────────────────────
//  Deployed contract bytecode references
//  (Foundry reads from src/ automatically)
// ─────────────────────────────────────────────

// We use string references — forge script resolves these
// from the compiled artifacts in `out/`

/**
 * @title DeployScript
 * @notice Deploys SentinelUSD + SentinelVault_Complete to Polkadot Hub Testnet
 *
 * DEPLOYMENT ORDER (required):
 *   1. Deploy MockPriceOracle (or use real Pyth on Polkadot Hub)
 *   2. Deploy SentinelUSD(vaultPlaceholder, supplyCap)
 *        — uses a placeholder vault address initially
 *   3. Deploy SentinelVault_Complete(oracle, guardian, sUSD, dexRouter, wdot)
 *   4. Call sUSD.setVault(vaultAddress) to grant mint/burn rights
 *
 * RUN:
 *   forge script script/Deploy.s.sol:DeployScript \
 *     --rpc-url https://services.polkadothub-rpc.com/testnet \
 *     --private-key $DEPLOYER_PRIVATE_KEY \
 *     --broadcast \
 *     --legacy \
 *     -vvvv
 *
 * ENVIRONMENT VARIABLES:
 *   DEPLOYER_PRIVATE_KEY   — wallet that pays for deployment gas
 *   GUARDIAN_ADDRESS       — Sentinel bot wallet (set as guardian in vault)
 *   DEX_ROUTER_ADDRESS     — Hydration DEX router (leave 0x0 if not live yet)
 *   WDOT_ADDRESS           — Wrapped DOT address (leave 0x0 if not live yet)
 *   SUPPLY_CAP             — Max sUSD supply (0 = unlimited, default for testnet)
 */
contract DeployScript is Script {
    // ── Polkadot Hub Testnet known addresses ──────────────────────
    // Update these when Hydration DEX is live on testnet
    // For now, use address(0) to deploy without a real DEX router
    // (emergencyRebalance DEX swap will be disabled but everything else works)
    address constant PLACEHOLDER_DEX_ROUTER = address(0);
    address constant PLACEHOLDER_WDOT = address(0);
    address constant USDT_TOKEN_ADDR = 0xAfBDeD88916ea0DC2F4882968Cc6C3E3202402c5; // Mock USDT deployed

    function run() external {
        // ── Load environment ──────────────────────────────────────
        uint256 deployerKey = vm.envUint("DEPLOYER_PRIVATE_KEY");
        address guardian = vm.envOr("GUARDIAN_ADDRESS", vm.addr(deployerKey));
        address dexRouter = vm.envOr("DEX_ROUTER_ADDRESS", PLACEHOLDER_DEX_ROUTER);
        address wdot = vm.envOr("WDOT_ADDRESS", PLACEHOLDER_WDOT);
        uint256 supplyCap = vm.envOr("SUPPLY_CAP", uint256(0)); // 0 = no cap

        address deployer = vm.addr(deployerKey);

        console.log("==============================================");
        console.log("  SENTINEL DEPLOYMENT - Polkadot Hub Testnet");
        console.log("==============================================");
        console.log("Deployer  :", deployer);
        console.log("Guardian  :", guardian);
        console.log("DEX Router:", dexRouter);
        console.log("WDOT      :", wdot);
        console.log("Supply Cap:", supplyCap);
        console.log("");

        vm.startBroadcast(deployerKey);

        MockPriceOracle oracle = new MockPriceOracle(
            100_000_000 // Initial DOT price = $1.00 (8 decimals: 1.00 * 1e8)
        );
        console.log("MockPriceOracle (DOT) deployed:", address(oracle));

        // ── Step 1.5: Deploy Mock USDT Oracle ──────────────────────
        MockPriceOracle usdtOracle = new MockPriceOracle(
            100_000_000 // Initial USDT price = $1.00 (8 decimals)
        );
        console.log("MockPriceOracle (USDT) deployed:", address(usdtOracle));

        // ── Step 2: Deploy SentinelUSD ────────────────────────────
        // Pass deployer as placeholder vault — we'll update it after vault deploy
        SentinelUSD sUSD = new SentinelUSD(
            deployer, // temporary vault = deployer (will be updated in Step 4)
            supplyCap
        );
        console.log("SentinelUSD (sUSD) deployed:", address(sUSD));

        // ── Step 3: Deploy SentinelVault_Complete ─────────────────
        SentinelVault_complete vault = new SentinelVault_complete(
            address(oracle), // DOT/USD price oracle
            guardian, // Sentinel bot's signing wallet
            address(sUSD), // sUSD stablecoin contract
            dexRouter, // Hydration DEX router (0x0 for now)
            wdot, // Wrapped DOT (0x0 for now)
            USDT_TOKEN_ADDR, // USDT native precompile
            address(usdtOracle) // USDT/USD price oracle
        );
        console.log("SentinelVault deployed:", address(vault));

        // ── Step 4: Wire sUSD to the vault ───────────────────────
        // Grant SentinelVault the exclusive right to mint and burn sUSD.
        // Without this call, mintStablecoin() and liquidate() will REVERT.
        sUSD.setVault(address(vault));
        console.log("sUSD.setVault() called - vault is now the sole minter/burner");

        vm.stopBroadcast();

        // ── Deployment Summary ────────────────────────────────────
        console.log("");
        console.log("==============================================");
        console.log("  DEPLOYMENT COMPLETE");
        console.log("==============================================");
        console.log("MockPriceOracle :", address(oracle));
        console.log("SentinelUSD     :", address(sUSD));
        console.log("SentinelVault   :", address(vault));
        console.log("");
        console.log("Next steps:");
        console.log("1. Copy these addresses into Java_Agent/Applications.properties");
        console.log("   sentinel.vault-address=", address(vault));
        console.log("   sentinel.susd-address=", address(sUSD));
        console.log("2. Set SENTINEL_PRIVATE_KEY env var to the guardian wallet private key");
        console.log("3. Set TELEGRAM_BOT_TOKEN env var");
        console.log("4. Run: mvn clean package -DskipTests && java -jar target/sentinel-bot-1.0.0.jar");
        console.log("");
        console.log("Explorer: https://polkadot-hub-testnet.blockscout.com");
    }
}
