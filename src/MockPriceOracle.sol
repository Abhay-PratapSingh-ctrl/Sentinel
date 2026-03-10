// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title MockPriceOracle
 * @notice Drop-in replacement for Pyth/Chainlink during testnet development.
 *
 * Implements the IPriceOracle interface expected by SentinelVault:
 *   getLatestPrice() → (int256 price, uint256 updatedAt)
 *
 * Price uses 8-decimal precision (Pyth/Chainlink standard):
 *   $7.00 DOT = 700_000_000   (7.00 * 1e8)
 *   $5.00 DOT = 500_000_000
 *   $4.50 DOT = 450_000_000
 *
 * ─────────────────────────────────────────────────────────
 *  DEMO PLAYBOOK
 * ─────────────────────────────────────────────────────────
 *  Step 1  Deploy MockPriceOracle(700_000_000)  → $7.00 DOT
 *  Step 2  Users deposit DOT + mint sUSD        → HF ≈ 150%
 *  Step 3  setPrice(450_000_000)                → crash to $4.50
 *          HF drops below 130% threshold
 *  Step 4  Java bot detects in ≤60s, fires pausePosition()
 *  Step 5  Bot fires emergencyRebalance()        → TX on Blockscout
 *  Step 6  (Optional) activateAegis(170, ...)   → Aegis Buffer demo
 *
 *  For USDT oracle: deploy a separate instance and set to $1.00
 *    setPrice(100_000_000)  → $1.00 USDT/USD
 * ─────────────────────────────────────────────────────────
 *
 * PRICE CHEAT SHEET (useful ranges):
 *   $10.00 → 1_000_000_000   // bullish
 *    $7.00 →   700_000_000   // starting demo price
 *    $5.00 →   500_000_000   // slight decline
 *    $4.67 →   467_000_000   // HF = 150% warning boundary
 *    $4.50 →   450_000_000   // HF ≈ 140% — Sentinel pauses position
 *    $4.20 →   420_000_000   // HF ≈ 130% — emergencyRebalance fires
 *    $3.50 →   350_000_000   // HF ≈ 120% — liquidatable
 *    $1.00 →   100_000_000   // USDT baseline price
 */
contract MockPriceOracle {
    int256   public price;
    uint256  public updatedAt;
    address  public owner;

    event PriceUpdated(int256 oldPrice, int256 newPrice, uint256 timestamp);

    modifier onlyOwner() {
        require(msg.sender == owner, "MockOracle: not owner");
        _;
    }

    /**
     * @param initialPrice  Starting asset/USD price (8 decimals).
     *                      Example: 700_000_000 = $7.00
     */
    constructor(int256 initialPrice) {
        require(initialPrice > 0, "MockOracle: price must be positive");
        owner     = msg.sender;
        price     = initialPrice;
        updatedAt = block.timestamp;
    }

    // ─────────────────────────────────────────────
    //  IPriceOracle Interface
    // ─────────────────────────────────────────────

    /**
     * @notice Returns the current mock price.
     * @return price      Asset/USD price (8 decimal precision)
     * @return updatedAt  Block timestamp of last price update
     */
    function getLatestPrice() external view returns (int256, uint256) {
        return (price, updatedAt);
    }

    // ─────────────────────────────────────────────
    //  Admin — simulate price moves
    // ─────────────────────────────────────────────

    /**
     * @notice Set a new price. Used to simulate market crashes / rallies during demos.
     * @param newPrice  New price in 8-decimal format (e.g. 450_000_000 = $4.50)
     */
    function setPrice(int256 newPrice) external onlyOwner {
        require(newPrice > 0, "MockOracle: price must be positive");
        emit PriceUpdated(price, newPrice, block.timestamp);
        price     = newPrice;
        updatedAt = block.timestamp;
    }

    function transferOwnership(address newOwner) external onlyOwner {
        require(newOwner != address(0), "MockOracle: zero address");
        owner = newOwner;
    }
}
