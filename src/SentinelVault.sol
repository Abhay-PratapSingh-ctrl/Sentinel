// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title SentinelVault — Multi-Collateral + PVM Brain + Aegis Buffer Edition
 * @notice Accepts DOT (native) AND native USDT (ERC-20 precompile) as collateral.
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │  WHAT'S IN THIS CONTRACT                                         │
 * │                                                                  │
 * │  Track 1 — EVM & AI                                             │
 * │  ✅ Aegis Dynamic Buffer: guardian activates 170% floor during   │
 * │     "red flag" volatility windows (replaces flat 150% constant) │
 * │  ✅ aegisCollateralRatioPct is emitted on-chain so the Java bot  │
 * │     can tie it directly to VolatilityService / LLM risk reports │
 * │                                                                  │
 * │  Track 2 — PVM & Native                                         │
 * │  ✅ Trustless Rebalancing: PVM `verify_rebalance` runs on-chain  │
 * │     before any DEX swap executes — decision is verifiable        │
 * │  ✅ Multi-Collateral: native USDT via Polkadot Hub ERC-20        │
 * │     precompile accepted alongside native DOT                     │
 * │  ✅ Graceful degradation: pvmPrecompile == address(0) skips PVM  │
 * └──────────────────────────────────────────────────────────────────┘
 *
 * Deploy order:
 *   1. Deploy SentinelUSD(vaultPlaceholder, supplyCap)
 *   2. Deploy SentinelVault(oracle, guardian, sUSD, dexRouter, wdot, usdtToken, usdtOracle)
 *   3. sUSD.setVault(vaultAddress)
 *   4. (Optional) vault.setPVMPrecompile(pvmAddress) once PVM is available
 *   5. (Optional) vault.activateAegis(170) when VolatilityService fires red flag
 */

// ─────────────────────────────────────────────
//  INTERFACES
// ─────────────────────────────────────────────

interface IPriceOracle {
    /// @return price      Asset/USD price (8 decimal precision, Pyth/Chainlink style)
    /// @return updatedAt  Block timestamp of last oracle update
    function getLatestPrice() external view returns (int256 price, uint256 updatedAt);
}

interface ISentinelUSD {
    function mint(address to, uint256 amount) external;
    function burnFrom(address from, uint256 amount) external;
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
    function approve(address spender, uint256 amount) external returns (bool);
}

/**
 * @dev Minimal ERC-20 interface for Polkadot Hub native asset precompiles.
 *      Native USDT (Asset Hub assetId = 1984) is exposed at a deterministic address:
 *      0xFFFFFFFF000007C0  (0xFFFFFFFF + assetId as uint32 big-endian)
 */
interface IERC20Collateral {
    function transferFrom(address from, address to, uint256 amount) external returns (bool);
    function transfer(address to, uint256 amount) external returns (bool);
    function balanceOf(address account) external view returns (uint256);
}

/**
 * @dev Uniswap V2 Router — interface compatible with Hydration DEX on Polkadot Hub.
 */
interface IUniswapV2Router {
    function swapExactETHForTokens(
        uint256 amountOutMin,
        address[] calldata path,
        address to,
        uint256 deadline
    ) external payable returns (uint256[] memory amounts);

    function getAmountsOut(
        uint256 amountIn,
        address[] calldata path
    ) external view returns (uint256[] memory amounts);
}

/**
 * @dev Interface for the PVM precompile on Polkadot Hub.
 *
 *  Input encoding for `verify_rebalance`:
 *    bytes4 selector (keccak256 of function signature)
 *    + abi.encode(collateralDot, mintedSUSD, dotPrice,
 *                 dotToSell, targetHfBps, rebalanceFloorBps)
 *    All params encoded as uint128 to match Rust u128.
 *
 *  Output: abi.encode(uint32) — 1 = APPROVED, 0 = REJECTED
 */
interface IPVM {
    function call(bytes calldata input) external view returns (bytes memory output);
}

// ─────────────────────────────────────────────
//  MAIN CONTRACT
// ─────────────────────────────────────────────

contract SentinelVault {

    // ── Protocol constants ──────────────────────────────────────────────
    uint256 public constant BASE_COLLATERAL_RATIO  = 150;  // Standard 150% floor
    uint256 public constant AEGIS_COLLATERAL_RATIO = 170;  // Aegis active: 170% floor
    uint256 public constant LIQUIDATION_RATIO      = 120;  // Position liquidatable below 120%
    uint256 public constant PRECISION              = 1e18;
    uint256 public constant DOT_PRICE_PREC         = 1e8;  // Pyth 8-decimal price
    uint256 public constant USDT_DECIMALS          = 1e6;  // Native USDT has 6 decimals
    uint256 public constant SWAP_SLIPPAGE_BPS      = 200;  // 2% max slippage
    uint256 public constant SWAP_DEADLINE_SEC      = 300;  // 5-min swap deadline

    // ── State ───────────────────────────────────────────────────────────
    address public owner;
    address public guardian;    // Sentinel AI Agent wallet
    bool    public globalPause;

    ISentinelUSD         public sUSD;
    IPriceOracle         public oracle;      // DOT/USD oracle
    IPriceOracle         public usdtOracle;  // USDT/USD oracle (≈$1 but always verified)
    IUniswapV2Router     public dexRouter;
    IERC20Collateral     public usdtToken;   // Native USDT ERC-20 precompile
    address              public wdot;        // Wrapped DOT for swap path

    /**
     * @dev PVM precompile address.
     *      address(0)  → PVM check skipped (graceful degradation mode).
     *      non-zero    → emergencyRebalance MUST pass PVM verification.
     */
    address public pvmPrecompile;

    /**
     * @dev Aegis Mode — Track 1 AI feature.
     *
     *  When the Java VolatilityService detects a "red flag" (e.g. DOT 30-day vol > 80%,
     *  or LLM risk assessment returns HIGH), the guardian calls activateAegis().
     *
     *  While aegisActive == true:
     *    - New sUSD can only be minted against 170% collateral (not 150%)
     *    - Existing positions are not force-liquidated (no retroactive enforcement)
     *    - rebalanceTargetHfBps is automatically elevated to aegisTargetHfBps
     *
     *  The AI bot (LLM risk reporter) reads aegisActive + aegisCollateralRatioPct
     *  to explain risk to users: "Aegis is active. Your effective buffer is 170%."
     */
    bool    public aegisActive;
    uint256 public aegisCollateralRatioPct = AEGIS_COLLATERAL_RATIO; // guardian-configurable

    /**
     * @dev Rebalance target HF in basis points (e.g. 15000 = 150%, 17000 = 170%).
     *      Passed to the PVM verifier so it validates against the current target.
     *      Automatically set to aegisCollateralRatioPct * 100 when Aegis activates.
     */
    uint256 public rebalanceTargetHfBps = 15_000;

    // ── Rebalance floor: allow rebalancing when HF drops below this ─────
    uint256 public constant REBALANCE_FLOOR = 120; // 120% — also the liquidation line

    // ── Position — tracks both DOT and USDT collateral ─────────────────
    struct Position {
        uint256 collateralDOT;  // Native DOT locked (wei, 1e18)
        uint256 collateralUSDT; // Native USDT locked (1e6 — 6 decimals)
        uint256 mintedSUSD;     // sUSD debt outstanding (1e18)
        bool    paused;         // Guardian can freeze individual positions
    }

    mapping(address => Position) public positions;
    address[] public positionHolders;

    // ── Events ──────────────────────────────────────────────────────────
    event CollateralDeposited(address indexed user, uint256 amount);
    event USDTDeposited(address indexed user, uint256 amount);
    event USDTWithdrawn(address indexed user, uint256 amount);
    event StablecoinMinted(address indexed user, uint256 amount);
    event StablecoinBurned(address indexed user, uint256 amount);
    event CollateralWithdrawn(address indexed user, uint256 amount);
    event PositionPaused(address indexed user, string reason);
    event PositionUnpaused(address indexed user);

    /**
     * @dev Emitted when Aegis mode activates or deactivates.
     *      Java bot reads this to update LLM context: "Aegis activated at ratio 170%".
     *      LLM risk reporter uses this to explain to users why their buffer increased.
     */
    event AegisActivated(uint256 collateralRatioPct, uint256 rebalanceTargetHfBps, string reason);
    event AegisDeactivated(uint256 rebalanceTargetHfBps);

    event RebalanceVerified(
        address indexed user,
        uint256 dotToSell,
        uint256 targetHfBps,
        bool pvmVerified     // true if PVM precompile approved; false if PVM was skipped
    );
    event EmergencyRebalance(
        address indexed user,
        uint256 dotSold,
        uint256 sUSDReceived,
        uint256 debtRepaid,
        uint256 newHealthFactor
    );
    event Liquidated(
        address indexed user,
        address indexed liquidator,
        uint256 dotSeized,
        uint256 usdtSeized
    );
    event PVMPrecompileUpdated(address indexed newAddress);
    event RebalanceTargetUpdated(uint256 newTargetHfBps);
    event GuardianUpdated(address indexed newGuardian);

    // ── Modifiers ───────────────────────────────────────────────────────
    modifier onlyOwner() {
        require(msg.sender == owner, "Sentinel: not owner");
        _;
    }
    modifier onlyGuardian() {
        require(msg.sender == guardian, "Sentinel: not guardian");
        _;
    }
    modifier notPaused() {
        require(!globalPause, "Sentinel: protocol paused");
        _;
    }
    modifier posnNotPaused(address user) {
        require(!positions[user].paused, "Sentinel: position paused");
        _;
    }

    // ── Constructor ─────────────────────────────────────────────────────
    /**
     * @param _oracle      Pyth/Chainlink DOT/USD oracle
     * @param _guardian    Sentinel AI Agent wallet
     * @param _sUSD        SentinelUSD token contract
     * @param _dexRouter   Hydration DEX router (Uniswap V2 compatible)
     * @param _wdot        Wrapped DOT address for swap path
     * @param _usdtToken   Native USDT ERC-20 precompile on Polkadot Hub
     * @param _usdtOracle  USDT/USD price oracle
     */
    constructor(
        address _oracle,
        address _guardian,
        address _sUSD,
        address _dexRouter,
        address _wdot,
        address _usdtToken,
        address _usdtOracle
    ) {
        owner      = msg.sender;
        oracle     = IPriceOracle(_oracle);
        guardian   = _guardian;
        sUSD       = ISentinelUSD(_sUSD);
        dexRouter  = IUniswapV2Router(_dexRouter);
        wdot       = _wdot;
        usdtToken  = IERC20Collateral(_usdtToken);
        usdtOracle = IPriceOracle(_usdtOracle);
        // pvmPrecompile defaults to address(0) — PVM check skipped until set
    }

    // ═════════════════════════════════════════════
    //  SECTION 1 — USER FUNCTIONS
    // ═════════════════════════════════════════════

    /**
     * @notice Deposit native DOT as collateral. Send DOT with the transaction (msg.value).
     */
    function depositCollateral() external payable notPaused {
        require(msg.value > 0, "Sentinel: zero deposit");
        _ensureRegistered(msg.sender);
        positions[msg.sender].collateralDOT += msg.value;
        emit CollateralDeposited(msg.sender, msg.value);
    }

    /**
     * @notice Deposit native USDT as collateral.
     *
     * HOW IT WORKS (Polkadot Hub native asset flow):
     *   1. User calls usdtToken.approve(vaultAddress, amount) via the ERC-20 precompile.
     *   2. User calls this function — vault pulls USDT via transferFrom.
     *   3. USDT is held in vault; adds to user's collateral USD value.
     *
     * USDT is the Polkadot Asset Hub USDT (bridged via XCM, assetId = 1984).
     *
     * @param amount USDT amount (6 decimals, e.g. 100_000_000 = $100)
     */
    function depositUSDT(uint256 amount) external notPaused {
        require(amount > 0, "Sentinel: zero deposit");
        bool ok = usdtToken.transferFrom(msg.sender, address(this), amount);
        require(ok, "Sentinel:USDT transfer failed did you approve the vault?");
        _ensureRegistered(msg.sender);
        positions[msg.sender].collateralUSDT += amount;
        emit USDTDeposited(msg.sender, amount);
    }

    /**
     * @notice Withdraw USDT collateral. Health Factor must remain >= effective ratio after.
     * @param amount USDT amount (6 decimals)
     */
    function withdrawUSDT(uint256 amount) external notPaused posnNotPaused(msg.sender) {
        Position storage pos = positions[msg.sender];
        require(amount <= pos.collateralUSDT, "Sentinel: insufficient USDT collateral");

        if (pos.mintedSUSD > 0) {
            uint256 remainingUSD    = _getTotalCollateralUSD(pos.collateralDOT, pos.collateralUSDT - amount);
            uint256 requiredUSD     = (pos.mintedSUSD * _effectiveCollateralRatio()) / 100;
            require(remainingUSD >= requiredUSD, "Sentinel: would undercollateralize");
        }

        pos.collateralUSDT -= amount;
        bool ok = usdtToken.transfer(msg.sender, amount);
        require(ok, "Sentinel: USDT withdrawal failed");
        emit USDTWithdrawn(msg.sender, amount);
    }

    /**
     * @notice Mint sUSD against combined DOT + USDT collateral.
     *
     *  Effective collateral ratio depends on Aegis mode:
     *    Normal  → 150% (BASE_COLLATERAL_RATIO)
     *    Aegis   → 170% (aegisCollateralRatioPct — set by guardian)
     *
     *  The Sentinel AI bot uses this distinction to generate LLM risk reports:
     *  "Aegis is active. During high-volatility windows the protocol requires 170%
     *   collateral. Your current ratio is X% — you are [SAFE / AT RISK]."
     */
    function mintStablecoin(uint256 amountSUSD) external notPaused posnNotPaused(msg.sender) {
        require(amountSUSD > 0, "Sentinel: zero amount");
        Position storage pos = positions[msg.sender];

        uint256 newMinted       = pos.mintedSUSD + amountSUSD;
        uint256 collateralUSD   = _getTotalCollateralUSD(pos.collateralDOT, pos.collateralUSDT);
        uint256 requiredUSD     = (newMinted * _effectiveCollateralRatio()) / 100;

        require(
            collateralUSD >= requiredUSD,
            aegisActive
                ? "Sentinel: undercollateralized (Aegis active : 170% required)"
                : "Sentinel: undercollateralized"
        );

        pos.mintedSUSD = newMinted;
        sUSD.mint(msg.sender, amountSUSD);
        emit StablecoinMinted(msg.sender, amountSUSD);
    }

    /**
     * @notice Burn sUSD to repay debt. Caller must approve vault first:
     *         sUSD.approve(vaultAddress, amount)
     */
    function burnStablecoin(uint256 amountSUSD) external notPaused {
        Position storage pos = positions[msg.sender];
        require(amountSUSD > 0, "Sentinel: zero amount");
        require(amountSUSD <= pos.mintedSUSD, "Sentinel: burn exceeds debt");

        pos.mintedSUSD -= amountSUSD;
        bool pulled = sUSD.transferFrom(msg.sender, address(this), amountSUSD);
        require(pulled, "Sentinel: transferFrom failed did you approve vault?");
        sUSD.burnFrom(address(this), amountSUSD);
        emit StablecoinBurned(msg.sender, amountSUSD);
    }

    /**
     * @notice Withdraw native DOT collateral.
     *         Health Factor must stay >= effective ratio (150% normal / 170% Aegis).
     */
    function withdrawCollateral(uint256 amount) external notPaused posnNotPaused(msg.sender) {
        Position storage pos = positions[msg.sender];
        require(amount <= pos.collateralDOT, "Sentinel: insufficient DOT collateral");

        if (pos.mintedSUSD > 0) {
            uint256 remainingUSD = _getTotalCollateralUSD(pos.collateralDOT - amount, pos.collateralUSDT);
            uint256 requiredUSD  = (pos.mintedSUSD * _effectiveCollateralRatio()) / 100;
            require(remainingUSD >= requiredUSD, "Sentinel: would undercollateralize");
        }

        pos.collateralDOT -= amount;
        (bool sent, ) = payable(msg.sender).call{value: amount}("");
        require(sent, "Sentinel: DOT transfer failed");
        emit CollateralWithdrawn(msg.sender, amount);
    }

    // ═════════════════════════════════════════════
    //  SECTION 2 — VIEW FUNCTIONS
    // ═════════════════════════════════════════════

    /**
     * @notice Returns the currently active collateral ratio (150 or 170).
     *         Exposed so the Java bot / LLM risk reporter can read it on-chain.
     */
    function effectiveCollateralRatio() external view returns (uint256) {
        return _effectiveCollateralRatio();
    }

    /**
     * @notice Health Factor scaled to 1e18. Considers DOT + USDT combined.
     *         type(uint256).max = no debt (completely safe).
     */
    function getHealthFactor(address user) public view returns (uint256) {
        Position memory pos = positions[user];
        if (pos.mintedSUSD == 0) return type(uint256).max;
        uint256 totalUSD = _getTotalCollateralUSD(pos.collateralDOT, pos.collateralUSDT);
        return (totalUSD * PRECISION) / pos.mintedSUSD;
    }

    /**
     * @notice Full position snapshot. Returns everything the Java bot / LLM needs.
     *         Includes Aegis status so risk reports can reference the active buffer.
     */
    function getPosition(address user) external view returns (
        uint256 collateralDOT,
        uint256 collateralUSDT,
        uint256 mintedSUSD,
        uint256 collateralUSD,
        uint256 healthFactor,
        bool    positionPaused,
        bool    isAegisActive,
        uint256 activeCollateralRatio
    ) {
        Position memory pos = positions[user];
        return (
            pos.collateralDOT,
            pos.collateralUSDT,
            pos.mintedSUSD,
            _getTotalCollateralUSD(pos.collateralDOT, pos.collateralUSDT),
            getHealthFactor(user),
            pos.paused,
            aegisActive,
            _effectiveCollateralRatio()
        );
    }

    function getAllUsers()    external view returns (address[] memory) { return positionHolders; }
    function getDOTPrice()   external view returns (int256, uint256)  { return oracle.getLatestPrice(); }

    function previewSwap(uint256 dotAmount) external view returns (uint256 sUSDOut) {
        address[] memory path = _buildSwapPath();
        uint256[] memory amounts = dexRouter.getAmountsOut(dotAmount, path);
        return amounts[amounts.length - 1];
    }

    // ═════════════════════════════════════════════
    //  SECTION 3 — AEGIS BUFFER (Track 1 — AI)
    // ═════════════════════════════════════════════

    /**
     * @notice Activate the Aegis Dynamic Buffer.
     *
     *  Called by the Java bot's VolatilityService when it detects a "red flag":
     *    - 30-day DOT volatility > configurable threshold (e.g. 80%)
     *    - LLM risk assessment returns HIGH or CRITICAL
     *    - Rapid price decline detected (e.g. >15% in 1 hour)
     *
     *  Effect:
     *    - New mints require `ratioPct`% collateral (default 170)
     *    - rebalanceTargetHfBps elevated accordingly
     *    - AegisActivated emitted — Java bot logs this to Telegram + LLM context
     *
     *  LLM Risk Report integration:
     *    The bot calls getPosition() → reads isAegisActive + activeCollateralRatio
     *    and injects them into the LLM prompt:
     *      "Aegis is ACTIVE (ratio=170%). DOT 30d vol: 82%. User HF: 1.45.
     *       Explain why this user's risk level is HIGH and what they should do."
     *
     * @param ratioPct  New collateral ratio percent (e.g. 170). Must be > BASE and <= 300.
     * @param reason    Human-readable reason logged to Telegram (e.g. "DOT vol 82%")
     */
    function activateAegis(uint256 ratioPct, string calldata reason) external onlyGuardian {
        require(ratioPct > BASE_COLLATERAL_RATIO, "Sentinel: Aegis ratio must exceed 150%");
        require(ratioPct <= 300, "Sentinel: Aegis ratio too high");

        aegisActive               = true;
        aegisCollateralRatioPct   = ratioPct;
        rebalanceTargetHfBps      = ratioPct * 100; // e.g. 170 → 17_000 bps

        emit AegisActivated(ratioPct, rebalanceTargetHfBps, reason);
    }

    /**
     * @notice Deactivate Aegis — revert to standard 150% ratio.
     *         Called when VolatilityService clears the red flag.
     */
    function deactivateAegis() external onlyGuardian {
        aegisActive          = false;
        rebalanceTargetHfBps = BASE_COLLATERAL_RATIO * 100; // back to 15_000

        emit AegisDeactivated(rebalanceTargetHfBps);
    }

    /**
     * @notice Manually override the rebalance target HF in basis points.
     *         Fine-grained control independent of full Aegis activation.
     *         Example: setRebalanceTargetHfBps(16_000) sets 160% target.
     */
    function setRebalanceTargetHfBps(uint256 _bps) external onlyGuardian {
        require(_bps >= 10_000 && _bps <= 30_000, "Sentinel: HF bps out of range");
        rebalanceTargetHfBps = _bps;
        emit RebalanceTargetUpdated(_bps);
    }

    // ═════════════════════════════════════════════
    //  SECTION 4 — GUARDIAN / SENTINEL FUNCTIONS
    // ═════════════════════════════════════════════

    function pausePosition(address user, string calldata reason) external onlyGuardian {
        positions[user].paused = true;
        emit PositionPaused(user, reason);
    }

    function unpausePosition(address user) external onlyGuardian {
        uint256 hf = getHealthFactor(user);
        require(hf >= (140 * PRECISION) / 100, "Sentinel: HF still too low to unpause");
        positions[user].paused = false;
        emit PositionUnpaused(user);
    }

    /**
     * @notice Emergency rebalance — trustlessly verified by PVM then executed via DEX.
     *
     * COMPLETE FLOW:
     *  Step 1  Bot calculates dotToSell off-chain using VolatilityService + oracle data.
     *  Step 2  Bot calls this function with dotToSell + minSUSDOut.
     *  Step 3  [PVM — Track 2] If pvmPrecompile is set, calls PVM `verify_rebalance`
     *          on-chain. The PVM runs Rust binary and checks:
     *            (a) position is actually undercollateralised
     *            (b) the proposed dotToSell achieves the target HF
     *            (c) target HF accounts for current aegisCollateralRatioPct
     *          Reverts with "Sentinel: PVM rejected rebalance" if either check fails.
     *  Step 4  Deducts collateral (CEI pattern).
     *  Step 5  DEX swap: DOT → sUSD via Hydration router.
     *  Step 6  Burns sUSD to reduce debt. Returns surplus to user.
     *  Step 7  Emits EmergencyRebalance — the key event for demo + Telegram alert.
     *
     * @param user        At-risk position
     * @param dotToSell   DOT collateral to sell (wei, 1e18)
     * @param minSUSDOut  Minimum sUSD to accept from DEX (slippage guard)
     */
    function emergencyRebalance(
        address user,
        uint256 dotToSell,
        uint256 minSUSDOut
    ) external onlyGuardian notPaused {
        Position storage pos = positions[user];
        require(pos.collateralDOT >= dotToSell, "Sentinel: insufficient DOT to sell");
        require(dotToSell > 0, "Sentinel: sell amount is zero");

        // ── Step 3: PVM trustless verification (Track 2) ──────────────────────────
        bool pvmVerified = false;
        if (pvmPrecompile != address(0)) {
            pvmVerified = _verifyWithPVM(pos.collateralDOT, pos.mintedSUSD, dotToSell);
            require(pvmVerified, "Sentinel: PVM rejected rebalance decision");
        }

        emit RebalanceVerified(user, dotToSell, rebalanceTargetHfBps, pvmVerified);

        // ── Step 4: Deduct collateral first (Checks-Effects-Interactions) ─────────
        pos.collateralDOT -= dotToSell;

        // ── Step 5: Execute DEX swap — native DOT → sUSD ──────────────────────────
        address[] memory path     = _buildSwapPath();
        uint256          deadline = block.timestamp + SWAP_DEADLINE_SEC;

        uint256[] memory amounts = dexRouter.swapExactETHForTokens{value: dotToSell}(
            minSUSDOut, path, address(this), deadline
        );

        uint256 sUSDReceived = amounts[amounts.length - 1];
        require(sUSDReceived >= minSUSDOut, "Sentinel: slippage too high");

        // ── Step 6: Burn sUSD to repay debt; refund surplus to user ───────────────
        uint256 debtRepaid = sUSDReceived <= pos.mintedSUSD ? sUSDReceived : pos.mintedSUSD;
        pos.mintedSUSD -= debtRepaid;
        sUSD.burnFrom(address(this), debtRepaid);

        uint256 surplus = sUSDReceived - debtRepaid;
        if (surplus > 0) {
            sUSD.mint(user, surplus);
        }

        // ── Step 7: Emit result ────────────────────────────────────────────────────
        uint256 newHF = getHealthFactor(user);
        emit EmergencyRebalance(user, dotToSell, sUSDReceived, debtRepaid, newHF);
    }

    /**
     * @notice Liquidate a position below 120% Health Factor.
     *         Liquidator repays full sUSD debt and receives all DOT + USDT collateral.
     */
    function liquidate(address user) external notPaused {
        uint256 hf = getHealthFactor(user);
        require(hf < (LIQUIDATION_RATIO * PRECISION) / 100, "Sentinel: position is healthy");

        Position storage pos = positions[user];
        uint256 dotSeized  = pos.collateralDOT;
        uint256 usdtSeized = pos.collateralUSDT;
        uint256 debt       = pos.mintedSUSD;

        // Clear position BEFORE external calls (reentrancy protection)
        pos.collateralDOT  = 0;
        pos.collateralUSDT = 0;
        pos.mintedSUSD     = 0;
        pos.paused         = false;

        // Pull and burn sUSD from liquidator
        bool pulled = sUSD.transferFrom(msg.sender, address(this), debt);
        require(pulled, "Sentinel: liquidator sUSD transfer failed");
        sUSD.burnFrom(address(this), debt);

        // Send DOT to liquidator
        if (dotSeized > 0) {
            (bool sentDOT, ) = payable(msg.sender).call{value: dotSeized}("");
            require(sentDOT, "Sentinel: DOT transfer failed");
        }

        // Send USDT to liquidator
        if (usdtSeized > 0) {
            bool sentUSDT = usdtToken.transfer(msg.sender, usdtSeized);
            require(sentUSDT, "Sentinel: USDT transfer failed");
        }

        emit Liquidated(user, msg.sender, dotSeized, usdtSeized);
    }

    // ═════════════════════════════════════════════
    //  SECTION 5 — ADMIN
    // ═════════════════════════════════════════════

    /**
     * @notice Set the PVM precompile address.
     *         address(0) = disable trustless verification (graceful degradation).
     */
    function setPVMPrecompile(address _pvm) external onlyOwner {
        pvmPrecompile = _pvm;
        emit PVMPrecompileUpdated(_pvm);
    }

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

    function updateUsdtOracle(address _o) external onlyOwner {
        usdtOracle = IPriceOracle(_o);
    }

    function updateDexRouter(address _router, address _wdot) external onlyOwner {
        dexRouter = IUniswapV2Router(_router);
        wdot      = _wdot;
    }

    // ═════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ═════════════════════════════════════════════

    /**
     * @dev Returns 150 (normal) or aegisCollateralRatioPct (Aegis active).
     *      All collateral checks in mintStablecoin / withdrawCollateral / withdrawUSDT
     *      call this so the Aegis buffer is applied consistently.
     */
    function _effectiveCollateralRatio() internal view returns (uint256) {
        return aegisActive ? aegisCollateralRatioPct : BASE_COLLATERAL_RATIO;
    }

    /**
     * @dev Calls the PVM precompile's `verify_rebalance` export.
     *
     *  Encoding matches the Rust function signature:
     *    verify_rebalance(u128 collateralDot, u128 mintedSusd, u128 dotPrice,
     *                     u128 dotToSell, u128 targetHfBps, u128 rebalanceFloorBps)
     *
     *  Returns true if PVM returns 1 (APPROVED), false if 0 (REJECTED) or call fails.
     */
    function _verifyWithPVM(
        uint256 collateralDot,
        uint256 mintedSusd,
        uint256 dotToSell
    ) internal view returns (bool) {
        (int256 dotPrice, ) = oracle.getLatestPrice();
        require(dotPrice > 0, "Sentinel: invalid oracle price for PVM");

        bytes4 selector = bytes4(
            keccak256("verify_rebalance(uint128,uint128,uint128,uint128,uint128,uint128)")
        );

        bytes memory input = abi.encodePacked(
            selector,
            abi.encode(
                uint128(collateralDot),               // collateral_dot
                uint128(mintedSusd),                  // minted_susd
                uint128(uint256(dotPrice)),            // dot_price (8 decimals)
                uint128(dotToSell),                   // dot_to_sell
                uint128(rebalanceTargetHfBps),         // target_hf_bps (e.g. 15000 or 17000 in Aegis)
                uint128(REBALANCE_FLOOR * 100)         // rebalance_floor_bps (12000)
            )
        );

        try IPVM(pvmPrecompile).call(input) returns (bytes memory output) {
            if (output.length < 32) return false;
            uint32 result = abi.decode(output, (uint32));
            return result == 1;
        } catch {
            // Fail safe: reject rebalance if PVM call itself reverts
            return false;
        }
    }

    /**
     * @dev Compute total USD value of combined DOT + USDT collateral (result: 1e18).
     *
     *  DOT:  dotAmount (1e18) * dotPrice (1e8) / 1e8  → 1e18
     *  USDT: usdtAmount (1e6) * usdtPrice (1e8) * 1e4 / 1e18  → 1e18
     *        = (usdtAmount * usdtPrice) / 1e14
     */
    function _getTotalCollateralUSD(
        uint256 dotAmount,
        uint256 usdtAmount
    ) internal view returns (uint256) {
        (int256 dotPrice, ) = oracle.getLatestPrice();
        require(dotPrice > 0, "Sentinel: invalid DOT oracle price");

        uint256 dotUSD  = (dotAmount * uint256(dotPrice)) / DOT_PRICE_PREC;

        uint256 usdtUSD = 0;
        if (usdtAmount > 0) {
            (int256 usdtPrice, ) = usdtOracle.getLatestPrice();
            require(usdtPrice > 0, "Sentinel: invalid USDT oracle price");
            usdtUSD = (usdtAmount * uint256(usdtPrice) * 1e4) / PRECISION;
        }

        return dotUSD + usdtUSD;
    }

    /**
     * @dev Register a new user in positionHolders[] on their first deposit.
     */
    function _ensureRegistered(address user) internal {
        if (
            positions[user].collateralDOT  == 0 &&
            positions[user].collateralUSDT == 0 &&
            positions[user].mintedSUSD     == 0
        ) {
            positionHolders.push(user);
        }
    }

    /** @dev Swap path: [WDOT → sUSD] for Hydration DEX. */
    function _buildSwapPath() internal view returns (address[] memory path) {
        path    = new address[](2);
        path[0] = wdot;
        path[1] = address(sUSD);
    }

    /// @dev Accept native DOT payments and DEX swap proceeds
    receive() external payable {}
}
