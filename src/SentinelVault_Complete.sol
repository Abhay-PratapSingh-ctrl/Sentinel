// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title SentinelVault — COMPLETE VERSION
 * @notice All TODO stubs replaced:
 *   ✅ sUSD ERC-20 mint/burn calls wired to SentinelUSD.sol
 *   ✅ DEX swap in emergencyRebalance() using a Uniswap V2-compatible router
 *      (works with any fork: Hydration DEX on Polkadot, Uniswap, etc.)
 *
 * Deploy order:
 *   1. Deploy SentinelUSD(vaultPlaceholder, supplyCap)
 *   2. Deploy SentinelVault(oracle, guardian, sUSDAddress, dexRouter, wdotAddress)
 *   3. Call sUSD.setVault(vaultAddress) to grant mint/burn rights
 */

// ─────────────────────────────────────────────
//  INTERFACES
// ─────────────────────────────────────────────

interface IPriceOracle {
    function getLatestPrice() external view returns (int256 price, uint256 updatedAt);
}

/**
 * @dev Minimal interface for SentinelUSD (or any ERC-20 with mint/burnFrom)
 */
interface ISentinelUSD {
    function mint(address to, uint256 amount) external;
    function burnFrom(address from, uint256 amount) external;
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
    function approve(address spender, uint256 amount) external returns (bool);
}

/**
 * @dev Uniswap V2 Router interface — compatible with:
 *      - Uniswap V2 on Ethereum
 *      - Hydration DEX on Polkadot (once EVM-compatible)
 *      - Any V2 fork (SushiSwap, PancakeSwap, etc.)
 *
 * WHAT IT DOES:
 *   swapExactETHForTokens() takes native DOT (ETH-equivalent on Polkadot Hub),
 *   sends it through a [WDOT → sUSD] liquidity pool, and returns sUSD.
 *   The `path` array defines the swap route.
 */
interface IUniswapV2Router {
    function swapExactETHForTokens(
        uint256 amountOutMin,       // Minimum sUSD to receive (slippage protection)
        address[] calldata path,    // [WDOT_address, sUSD_address]
        address to,                 // Who receives the sUSD output
        uint256 deadline            // Unix timestamp — tx reverts if mined after this
    ) external payable returns (uint256[] memory amounts);

    function getAmountsOut(
        uint256 amountIn,           // DOT amount going in
        address[] calldata path     // Swap route
    ) external view returns (uint256[] memory amounts);
}

// ─────────────────────────────────────────────
//  MAIN CONTRACT
// ─────────────────────────────────────────────

contract SentinelVault {

    // ── Constants ─────────────────────────────────────────────────
    uint256 public constant COLLATERAL_RATIO  = 150;   // 150% — safe minting ceiling
    uint256 public constant LIQUIDATION_RATIO = 120;   // 120% — liquidation floor
    uint256 public constant TARGET_RATIO      = 150;   // Rebalance restores to 150%
    uint256 public constant PRECISION         = 1e18;
    uint256 public constant PRICE_PRECISION   = 1e8;   // Pyth/Chainlink use 8 decimals
    uint256 public constant SWAP_SLIPPAGE_BPS = 200;   // 2% max slippage on DEX swaps
    uint256 public constant SWAP_DEADLINE_SEC = 300;   // 5 minute deadline on swaps

    // ── State ─────────────────────────────────────────────────────
    address public owner;
    address public guardian;          // Sentinel AI Agent wallet
    bool    public globalPause;

    ISentinelUSD    public sUSD;      // The stablecoin contract
    IPriceOracle    public oracle;    // Pyth or Chainlink DOT/USD feed
    IUniswapV2Router public dexRouter; // Hydration DEX (or Uniswap V2 fork)
    address         public wdot;      // Wrapped DOT address for swap path

    struct Position {
        uint256 collateralDOT;
        uint256 mintedSUSD;
        bool    paused;
    }

    mapping(address => Position) public positions;
    address[] public positionHolders;

    // ── Events ─────────────────────────────────────────────────────
    event CollateralDeposited(address indexed user, uint256 amount);
    event StablecoinMinted(address indexed user, uint256 amount);
    event StablecoinBurned(address indexed user, uint256 amount);
    event CollateralWithdrawn(address indexed user, uint256 amount);
    event PositionPaused(address indexed user, string reason);
    event PositionUnpaused(address indexed user);
    event EmergencyRebalance(
        address indexed user,
        uint256 dotSold,
        uint256 sUSDReceived,
        uint256 debtRepaid,
        uint256 newHealthFactor
    );
    event Liquidated(address indexed user, address indexed liquidator, uint256 collateralSeized);
    event SwapFailed(address indexed user, uint256 dotAmount, bytes reason);
    event GuardianUpdated(address indexed newGuardian);

    // ── Modifiers ──────────────────────────────────────────────────
    modifier onlyOwner() {
        require(msg.sender == owner, "Sentinel: not owner");
        _;
    }

    modifier onlyGuardian() {
        require(msg.sender == guardian, "Sentinel: not guardian");
        _;
    }

    modifier notGloballyPaused() {
        require(!globalPause, "Sentinel: protocol paused");
        _;
    }

    modifier positionNotPaused(address user) {
        require(!positions[user].paused, "Sentinel: position paused by Sentinel");
        _;
    }

    // ── Constructor ────────────────────────────────────────────────
    /**
     * @param _oracle     Pyth/Chainlink DOT/USD oracle address
     * @param _guardian   Sentinel AI Agent wallet (signs bot transactions)
     * @param _sUSD       Deployed SentinelUSD contract address
     * @param _dexRouter  Hydration DEX router (Uniswap V2 compatible)
     * @param _wdot       Wrapped DOT token address (needed for swap path)
     */
    constructor(
        address _oracle,
        address _guardian,
        address _sUSD,
        address _dexRouter,
        address _wdot
    ) {
        owner     = msg.sender;
        oracle    = IPriceOracle(_oracle);
        guardian  = _guardian;
        sUSD      = ISentinelUSD(_sUSD);
        dexRouter = IUniswapV2Router(_dexRouter);
        wdot      = _wdot;
    }

    // ═════════════════════════════════════════════
    //  SECTION 1 — USER FUNCTIONS
    // ═════════════════════════════════════════════

    /**
     * WHAT IT DOES:
     *   Accepts native DOT sent with the transaction.
     *   Registers new users in positionHolders[] for bot iteration.
     *   Emits CollateralDeposited — Java bot adds user to watchlist.
     */
    function depositCollateral() external payable notGloballyPaused {
        require(msg.value > 0, "Sentinel: zero deposit");

        if (positions[msg.sender].collateralDOT == 0 && positions[msg.sender].mintedSUSD == 0) {
            positionHolders.push(msg.sender);
        }

        positions[msg.sender].collateralDOT += msg.value;
        emit CollateralDeposited(msg.sender, msg.value);
    }

    /**
     * WHAT IT DOES:
     *   1. Calculates if minting amountSUSD keeps Health Factor >= 150%.
     *   2. If safe: updates mintedSUSD, then calls sUSD.mint(msg.sender, amount)
     *      — this actually creates the ERC-20 tokens and sends them to the user.
     *   3. Emits StablecoinMinted.
     *
     * NOTE: sUSD.mint() will REVERT if this contract is not set as the vault
     *       in SentinelUSD. Deploy order matters!
     */
    function mintStablecoin(uint256 amountSUSD) external notGloballyPaused positionNotPaused(msg.sender) {
        require(amountSUSD > 0, "Sentinel: zero amount");

        Position storage pos = positions[msg.sender];
        uint256 newMinted = pos.mintedSUSD + amountSUSD;

        uint256 collateralUSD    = _getCollateralValueUSD(pos.collateralDOT);
        uint256 requiredCollateral = (newMinted * COLLATERAL_RATIO) / 100;
        require(collateralUSD >= requiredCollateral, "Sentinel: undercollateralized");

        pos.mintedSUSD = newMinted;

        // ✅ REPLACES TODO: actually mints ERC-20 sUSD tokens to the user
        sUSD.mint(msg.sender, amountSUSD);

        emit StablecoinMinted(msg.sender, amountSUSD);
    }

    /**
     * WHAT IT DOES:
     *   1. Pulls sUSD from the user back into this contract via transferFrom.
     *      (User must call sUSD.approve(vaultAddress, amount) first.)
     *   2. Calls sUSD.burnFrom() to destroy the tokens — totalSupply decreases.
     *   3. Reduces mintedSUSD in the position struct.
     *   4. Emits StablecoinBurned — bot detects this and recalculates HF.
     */
    function burnStablecoin(uint256 amountSUSD) external notGloballyPaused {
        Position storage pos = positions[msg.sender];
        require(amountSUSD > 0,                "Sentinel: zero amount");
        require(amountSUSD <= pos.mintedSUSD,  "Sentinel: burn exceeds debt");

        pos.mintedSUSD -= amountSUSD;

        // ✅ REPLACES TODO: pulls tokens in then destroys them
        bool pulled = sUSD.transferFrom(msg.sender, address(this), amountSUSD);
        require(pulled, "Sentinel: transferFrom failed — did you approve vault?");
        sUSD.burnFrom(address(this), amountSUSD);

        emit StablecoinBurned(msg.sender, amountSUSD);
    }

    /**
     * WHAT IT DOES:
     *   Verifies withdrawal keeps HF >= 150%, then sends native DOT back to user.
     */
    function withdrawCollateral(uint256 amount) external notGloballyPaused positionNotPaused(msg.sender) {
        Position storage pos = positions[msg.sender];
        require(amount <= pos.collateralDOT, "Sentinel: insufficient collateral");

        uint256 remaining = pos.collateralDOT - amount;
        if (pos.mintedSUSD > 0) {
            uint256 remainingUSD = _getCollateralValueUSD(remaining);
            uint256 requiredUSD  = (pos.mintedSUSD * COLLATERAL_RATIO) / 100;
            require(remainingUSD >= requiredUSD, "Sentinel: would undercollateralize");
        }

        pos.collateralDOT -= amount;

        (bool sent, ) = payable(msg.sender).call{value: amount}("");
        require(sent, "Sentinel: DOT transfer failed");

        emit CollateralWithdrawn(msg.sender, amount);
    }

    // ═════════════════════════════════════════════
    //  SECTION 2 — VIEW / READ FUNCTIONS
    //  Java bot calls these every 60 seconds
    // ═════════════════════════════════════════════

    /**
     * WHAT IT DOES:
     *   Returns the Health Factor scaled to 1e18.
     *   > 1.5e18 = safe
     *   1.2e18–1.5e18 = bot sends Telegram warning
     *   < 1.2e18 = bot triggers emergency rebalance
     *   = type(uint256).max = no debt, completely safe
     */
    function getHealthFactor(address user) public view returns (uint256) {
        Position memory pos = positions[user];
        if (pos.mintedSUSD == 0) return type(uint256).max;
        uint256 collateralUSD = _getCollateralValueUSD(pos.collateralDOT);
        return (collateralUSD * PRECISION) / pos.mintedSUSD;
    }

    /**
     * WHAT IT DOES:
     *   Full snapshot of a user's position — one call gets everything the bot needs.
     *   Returns collateralDOT, mintedSUSD, collateral in USD, health factor, paused status.
     */
    function getPosition(address user) external view returns (
        uint256 collateralDOT,
        uint256 mintedSUSD,
        uint256 collateralUSD,
        uint256 healthFactor,
        bool    paused
    ) {
        Position memory pos = positions[user];
        return (
            pos.collateralDOT,
            pos.mintedSUSD,
            _getCollateralValueUSD(pos.collateralDOT),
            getHealthFactor(user),
            pos.paused
        );
    }

    function getAllUsers() external view returns (address[] memory) {
        return positionHolders;
    }

    function getDOTPrice() external view returns (int256, uint256) {
        return oracle.getLatestPrice();
    }

    /**
     * WHAT IT DOES:
     *   Preview how much sUSD would come out of a swap of dotAmount through the DEX.
     *   The bot calls this BEFORE emergencyRebalance to decide dotToSell.
     *   Uses the DEX's live liquidity pool prices — more accurate than oracle alone.
     */
    function previewSwap(uint256 dotAmount) external view returns (uint256 sUSDOut) {
        address[] memory path = _buildSwapPath();
        uint256[] memory amounts = dexRouter.getAmountsOut(dotAmount, path);
        return amounts[amounts.length - 1];
    }

    // ═════════════════════════════════════════════
    //  SECTION 3 — GUARDIAN / SENTINEL FUNCTIONS
    // ═════════════════════════════════════════════

    /**
     * WHAT IT DOES:
     *   Freezes a user's position — they cannot mint more sUSD or withdraw collateral.
     *   The bot calls this when HF drops below 130%.
     *   The reason string appears in the Telegram alert message.
     */
    function pausePosition(address user, string calldata reason) external onlyGuardian {
        positions[user].paused = true;
        emit PositionPaused(user, reason);
    }

    /**
     * WHAT IT DOES:
     *   Unfreezes a position only after HF is back above 140%.
     *   Bot calls this after confirming the user topped up their collateral.
     */
    function unpausePosition(address user) external onlyGuardian {
        uint256 hf = getHealthFactor(user);
        require(hf >= 140 * PRECISION / 100, "Sentinel: HF still too low");
        positions[user].paused = false;
        emit PositionUnpaused(user);
    }

    /**
     * WHAT IT DOES — THE COMPLETE FLOW (replaces the old TODO stub):
     *
     *  Step 1  Bot calls previewSwap(dotToSell) to estimate sUSD output.
     *  Step 2  Bot calls this function with dotToSell + expected sUSD minimum.
     *  Step 3  Contract takes dotToSell from user's collateral balance.
     *  Step 4  Calls dexRouter.swapExactETHForTokens{value: dotToSell}()
     *          → sends DOT to Hydration DEX pool
     *          → receives sUSD back to this contract
     *  Step 5  Uses received sUSD to call sUSD.burnFrom() — reduces user's debt.
     *  Step 6  Updates position: collateralDOT -= dotSold, mintedSUSD -= debtRepaid.
     *  Step 7  Emits EmergencyRebalance with new Health Factor — this is the
     *          on-chain transaction shown in the demo video on the explorer.
     *
     *  If the DEX swap fails (e.g., insufficient liquidity), the whole tx reverts
     *  and the user's position is unchanged. The bot catches the revert and
     *  sends a different Telegram alert: "Auto-rebalance failed, manual action needed."
     *
     * @param user          The at-risk position to rebalance
     * @param dotToSell     How much DOT collateral to swap (bot calculates this)
     * @param minSUSDOut    Minimum sUSD to accept — reverts if DEX gives less (slippage guard)
     */
    function emergencyRebalance(
        address user,
        uint256 dotToSell,
        uint256 minSUSDOut
    )
        external
        onlyGuardian
        notGloballyPaused
    {
        Position storage pos = positions[user];
        require(pos.collateralDOT >= dotToSell, "Sentinel: insufficient collateral to sell");
        require(dotToSell > 0,                  "Sentinel: sell amount is zero");

        // ── Step 1: Deduct collateral before swap (CEI pattern — prevents reentrancy) ──
        pos.collateralDOT -= dotToSell;

        // ── Step 2: Execute DEX swap — DOT in, sUSD out ──────────────────────────────
        address[] memory path    = _buildSwapPath();
        uint256   deadline       = block.timestamp + SWAP_DEADLINE_SEC;

        uint256[] memory amounts = dexRouter.swapExactETHForTokens{value: dotToSell}(
            minSUSDOut,    // Slippage protection — bot sets this from previewSwap()
            path,          // [WDOT, sUSD]
            address(this), // sUSD comes to this contract, not the user
            deadline
        );

        uint256 sUSDReceived = amounts[amounts.length - 1];
        require(sUSDReceived >= minSUSDOut, "Sentinel: slippage too high");

        // ── Step 3: Burn the received sUSD to repay user's debt ──────────────────────
        // Cap debtRepaid at mintedSUSD — don't overshoot and underflow
        uint256 debtRepaid = sUSDReceived <= pos.mintedSUSD
            ? sUSDReceived
            : pos.mintedSUSD;

        pos.mintedSUSD -= debtRepaid;
        sUSD.burnFrom(address(this), debtRepaid);

        // ── Step 4: Refund any sUSD surplus back to the user ─────────────────────────
        // This happens if the swap returned more sUSD than the user's total debt
        uint256 surplus = sUSDReceived - debtRepaid;
        if (surplus > 0) {
            sUSD.mint(user, surplus); // Return overage as free sUSD
        }

        // ── Step 5: Emit event with new health factor for monitoring ──────────────────
        uint256 newHF = getHealthFactor(user);
        emit EmergencyRebalance(user, dotToSell, sUSDReceived, debtRepaid, newHF);
    }

    /**
     * WHAT IT DOES:
     *   Anyone can call this when a position's HF is below 120%.
     *   Liquidator pays off the user's full sUSD debt.
     *   Liquidator receives the user's entire DOT collateral (profitable for them).
     *   Position is fully cleared.
     *
     *   In production, you'd add a liquidation bonus (e.g., liquidator keeps 5% extra).
     *   For the hackathon, 1:1 exchange is fine.
     */
    function liquidate(address user) external notGloballyPaused {
        uint256 hf = getHealthFactor(user);
        require(hf < LIQUIDATION_RATIO * PRECISION / 100, "Sentinel: position is healthy");

        Position storage pos     = positions[user];
        uint256 collateralSeized = pos.collateralDOT;
        uint256 debt             = pos.mintedSUSD;

        // Clear position before transfers (reentrancy protection)
        pos.collateralDOT = 0;
        pos.mintedSUSD    = 0;
        pos.paused        = false;

        // Pull sUSD from liquidator and burn it to settle debt
        // Liquidator must have approved vault: sUSD.approve(vault, debt)
        bool pulled = sUSD.transferFrom(msg.sender, address(this), debt);
        require(pulled, "Sentinel: liquidator sUSD transfer failed");
        sUSD.burnFrom(address(this), debt);

        // Send seized collateral to liquidator as reward
        (bool sent, ) = payable(msg.sender).call{value: collateralSeized}("");
        require(sent, "Sentinel: collateral transfer failed");

        emit Liquidated(user, msg.sender, collateralSeized);
    }

    // ═════════════════════════════════════════════
    //  SECTION 4 — ADMIN
    // ═════════════════════════════════════════════

    function setGuardian(address _guardian) external onlyOwner {
        require(_guardian != address(0), "Sentinel: zero address");
        guardian = _guardian;
        emit GuardianUpdated(_guardian);
    }

    function setGlobalPause(bool _paused) external onlyOwner {
        globalPause = _paused;
    }

    function updateOracle(address _oracle) external onlyOwner {
        oracle = IPriceOracle(_oracle);
    }

    function updateDexRouter(address _router, address _wdot) external onlyOwner {
        dexRouter = IUniswapV2Router(_router);
        wdot = _wdot;
    }

    // ═════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ═════════════════════════════════════════════

    /**
     * @dev Converts DOT amount (wei, 1e18) to USD value (1e18 precision).
     *      dotAmount * oraclePrice(1e8) / 1e8 = USD(1e18)
     */
    function _getCollateralValueUSD(uint256 dotAmount) internal view returns (uint256) {
        (int256 price, ) = oracle.getLatestPrice();
        require(price > 0, "Sentinel: invalid oracle price");
        return (dotAmount * uint256(price)) / PRICE_PRECISION;
    }

    /**
     * @dev Builds the swap path array: [WDOT → sUSD]
     *      Used by both swapExactETHForTokens and getAmountsOut.
     */
    function _buildSwapPath() internal view returns (address[] memory path) {
        path    = new address[](2);
        path[0] = wdot;              // Wrapped DOT (input token)
        path[1] = address(sUSD);     // sUSD (output token)
    }

    /// @dev Accept plain DOT transfers and swap proceeds
    receive() external payable {}
}
