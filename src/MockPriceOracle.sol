// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title MockPriceOracle
 * @notice Drop-in replacement for a real Pyth/Chainlink oracle during testnet development.
 *
 * Implements the same IPriceOracle interface as SentinelVault expects:
 *   getLatestPrice() → (int256 price, uint256 updatedAt)
 *
 * Price uses 8-decimal precision (same as Pyth and Chainlink):
 *   $7.00 DOT  = 700_000_000  (7.00 * 1e8)
 *   $5.00 DOT  = 500_000_000
 *   $4.50 DOT  = 450_000_000
 *
 * HOW TO USE FOR DEMO:
 *   1. Deploy with initial price at $7.00
 *   2. Users deposit DOT and mint sUSD (healthy HF ~150%)
 *   3. Call setPrice(450_000_000) to simulate a price crash to $4.50
 *   4. Java bot detects HF below threshold within 60 seconds
 *   5. Bot fires pausePosition() and emergencyRebalance() on-chain
 *   6. Show the TX on Polkadot Hub Blockscout — demo done!
 */
contract MockPriceOracle {
    int256 public price;
    uint256 public updatedAt;
    address public owner;

    event PriceUpdated(int256 oldPrice, int256 newPrice, uint256 timestamp);

    modifier onlyOwner() {
        require(msg.sender == owner, "MockOracle: not owner");
        _;
    }

    /**
     * @param initialPrice  Starting DOT/USD price (8 decimals).
     *                      Example: 700_000_000 = $7.00
     */
    constructor(int256 initialPrice) {
        require(initialPrice > 0, "MockOracle: price must be positive");
        owner = msg.sender;
        price = initialPrice;
        updatedAt = block.timestamp;
    }

    // ─────────────────────────────────────────────
    //  IPriceOracle Interface
    // ─────────────────────────────────────────────

    /**
     * @notice Returns the current mock DOT/USD price.
     * @return price      DOT/USD price (8 decimal precision).
     * @return updatedAt  Block timestamp of last price update.
     */
    function getLatestPrice() external view returns (int256, uint256) {
        return (price, updatedAt);
    }

    // ─────────────────────────────────────────────
    //  Admin — call these to simulate price moves
    // ─────────────────────────────────────────────

    /**
     * @notice Set a new DOT price. Call this to simulate market moves during demos.
     * @param newPrice  New price in 8-decimal format (e.g. 450_000_000 = $4.50)
     *
     * DEMO CHEAT SHEET:
     *   $1.50 → setPrice(150_000_000)   // bullish
     *   $1.00 → setPrice(100_000_000)   // safe baseline (~150% HF)
     *   $0.75 → setPrice(75_000_000)    // HF warning zone (~112%)
     *   $0.70 → setPrice(70_000_000)    // HF danger zone (~105%)
     *   $0.65 → setPrice(65_000_000)    // HF critical - bot triggers rebalance
     *   $0.55 → setPrice(55_000_000)    // liquidatable
     */
    function setPrice(int256 newPrice) external onlyOwner {
        require(newPrice > 0, "MockOracle: price must be positive");
        emit PriceUpdated(price, newPrice, block.timestamp);
        price = newPrice;
        updatedAt = block.timestamp;
    }

    /**
     * @notice Transfer oracle ownership (e.g., hand to a multi-sig after deploy).
     */
    function transferOwnership(address newOwner) external onlyOwner {
        require(newOwner != address(0), "MockOracle: zero address");
        owner = newOwner;
    }
}
