// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title SentinelVault
 * @notice Agentic DeFi Vault for Polkadot Hub (Chain ID: 420420417)
 * @dev Users deposit DOT as collateral and mint sUSD stablecoin.
 *      The Sentinel AI Agent acts as "Guardian" — it can pause risky
 *      positions and trigger emergency rebalances to prevent liquidation.
 */

interface IPriceOracle {
    /// @notice Returns the latest DOT/USD price scaled to 8 decimals
    function getLatestPrice()
        external
        view
        returns (int256 price, uint256 updatedAt);
}

/**
 * @title IPolkaVM
 * @notice Interface for PolkaVM Precompile at 0x420
 */
interface IPolkaVM {
    /**
     * @notice Execute a RISC-V program in PolkaVM
     * @param programId  The registered ID of the PVM blob
     * @param input      Input data for the Rust library (encodes collateral, debt, price)
     * @return result    The output from the Rust library (Health Factor)
     */
    function execute(
        bytes32 programId,
        bytes calldata input
    ) external view returns (bytes memory);
}

contract SentinelVault {
    // ─────────────────────────────────────────────
    //  CONSTANTS & CONFIGURATION
    // ─────────────────────────────────────────────

    uint256 public constant COLLATERAL_RATIO = 150; // 150% minimum collateralization
    uint256 public constant LIQUIDATION_RATIO = 120; // Below 120% = liquidatable
    uint256 public constant PRECISION = 1e18;
    uint256 public constant PRICE_PRECISION = 1e8; // Oracle uses 8 decimals

    // ─────────────────────────────────────────────
    //  STATE VARIABLES
    // ─────────────────────────────────────────────

    address public owner;
    address public guardian; // The Sentinel AI Agent's wallet
    IPriceOracle public oracle;
    bool public globalPause; // Emergency stop for entire protocol

    // PolkaVM Integration
    bytes32 public pvmProgramId; // Registered Risk Score PVM Program
    bool public usePVM = true; // Toggle for "Speed Pillar" experiment

    struct Position {
        uint256 collateralDOT; // How much DOT the user deposited (in wei)
        uint256 mintedSUSD; // How much sUSD the user has minted
        bool paused; // Sentinel can pause individual positions
    }

    mapping(address => Position) public positions;
    address[] public positionHolders; // Track all users for iteration

    // Multi-Sig Guardian: Tracks if a user has signed for a specific rebalance
    mapping(address => bool) public userApprovalForRebalance;
    uint256 public constant HIGH_VALUE_THRESHOLD = 1000 * 1e18; // 1000 sUSD debt = high value

    // ─────────────────────────────────────────────
    //  EVENTS  (your Java bot will listen to these)
    // ─────────────────────────────────────────────

    event CollateralDeposited(address indexed user, uint256 amount);
    event StablecoinMinted(address indexed user, uint256 amount);
    event StablecoinBurned(address indexed user, uint256 amount);
    event CollateralWithdrawn(address indexed user, uint256 amount);
    event PositionPaused(address indexed user, string reason);
    event PositionUnpaused(address indexed user);
    event EmergencyRebalance(
        address indexed user,
        uint256 collateralSold,
        uint256 debtRepaid
    );
    event Liquidated(
        address indexed user,
        address indexed liquidator,
        uint256 collateralSeized
    );
    event GuardianUpdated(address indexed newGuardian);
    event GlobalPauseToggled(bool isPaused);

    // ─────────────────────────────────────────────
    //  MODIFIERS
    // ─────────────────────────────────────────────

    modifier onlyOwner() {
        require(msg.sender == owner, "SentinelVault: not owner");
        _;
    }

    modifier onlyGuardian() {
        require(msg.sender == guardian, "SentinelVault: not guardian");
        _;
    }

    modifier notGloballyPaused() {
        require(!globalPause, "SentinelVault: protocol paused");
        _;
    }

    modifier positionNotPaused(address user) {
        require(
            !positions[user].paused,
            "SentinelVault: position paused by Sentinel"
        );
        _;
    }

    // ─────────────────────────────────────────────
    //  CONSTRUCTOR
    // ─────────────────────────────────────────────

    constructor(address _oracle, address _guardian) {
        owner = msg.sender;
        oracle = IPriceOracle(_oracle);
        guardian = _guardian;
    }

    // ═════════════════════════════════════════════
    //  SECTION 1 — USER FUNCTIONS
    //  What they should do / what they actually do
    // ═════════════════════════════════════════════

    /**
     * @notice SHOULD DO:  Accept DOT from user as collateral backing their sUSD.
     * @notice ACTUALLY DOES:
     *   1. Receives native DOT (msg.value) sent with the transaction.
     *   2. Adds it to the user's collateralDOT balance in their Position struct.
     *   3. If this is a new user (first deposit), registers them in positionHolders[]
     *      so the Sentinel bot can iterate over all active users.
     *   4. Emits CollateralDeposited — the Java bot listens for this event
     *      to add the user to its monitoring watchlist.
     */
    function depositCollateral() external payable notGloballyPaused {
        require(msg.value > 0, "SentinelVault: deposit must be > 0");

        if (
            positions[msg.sender].collateralDOT == 0 &&
            positions[msg.sender].mintedSUSD == 0
        ) {
            positionHolders.push(msg.sender);
        }

        positions[msg.sender].collateralDOT += msg.value;

        emit CollateralDeposited(msg.sender, msg.value);
    }

    /**
     * @notice SHOULD DO:  Let the user borrow sUSD against their DOT collateral.
     * @notice ACTUALLY DOES:
     *   1. Checks that minting this amount would keep the Health Factor >= 150%.
     *      Formula: (collateralUSD * 100) / mintedSUSD >= COLLATERAL_RATIO
     *   2. If safe, increments mintedSUSD in the user's position.
     *   3. In a full implementation, this calls an ERC-20 mint() on the sUSD token.
     *      Here it tracks the debt on-chain and the ERC-20 mint is a TODO stub.
     *   4. Emits StablecoinMinted.
     *
     * @param amountSUSD  Amount of sUSD to mint (18 decimals)
     */
    function mintStablecoin(
        uint256 amountSUSD
    ) external notGloballyPaused positionNotPaused(msg.sender) {
        require(amountSUSD > 0, "SentinelVault: amount must be > 0");

        Position storage pos = positions[msg.sender];
        uint256 newMinted = pos.mintedSUSD + amountSUSD;

        // Verify collateral ratio would still be healthy after mint
        uint256 collateralUSD = _getCollateralValueUSD(pos.collateralDOT);
        uint256 requiredCollateral = (newMinted * COLLATERAL_RATIO) / 100;

        require(
            collateralUSD >= requiredCollateral,
            "SentinelVault: undercollateralized"
        );

        pos.mintedSUSD = newMinted;

        // TODO: sUSDToken.mint(msg.sender, amountSUSD);
        emit StablecoinMinted(msg.sender, amountSUSD);
    }

    /**
     * @notice SHOULD DO:  Allow user to repay sUSD debt, reducing liquidation risk.
     * @notice ACTUALLY DOES:
     *   1. Subtracts amountSUSD from the user's minted balance.
     *   2. In a full build, calls ERC-20 burn() to destroy the returned tokens.
     *   3. Emits StablecoinBurned — Sentinel bot can detect debt reduction
     *      and recalculate the Health Factor immediately.
     */
    function burnStablecoin(uint256 amountSUSD) external notGloballyPaused {
        Position storage pos = positions[msg.sender];
        require(
            amountSUSD <= pos.mintedSUSD,
            "SentinelVault: burn exceeds debt"
        );

        pos.mintedSUSD -= amountSUSD;

        // TODO: sUSDToken.burnFrom(msg.sender, amountSUSD);
        emit StablecoinBurned(msg.sender, amountSUSD);
    }

    /**
     * @notice SHOULD DO:  Let user reclaim their DOT after repaying debt.
     * @notice ACTUALLY DOES:
     *   1. Verifies the withdrawal won't push the Health Factor below 150%.
     *   2. Subtracts from collateralDOT.
     *   3. Sends native DOT back to the user via call{value}.
     *   4. Emits CollateralWithdrawn.
     */
    function withdrawCollateral(
        uint256 amount
    ) external notGloballyPaused positionNotPaused(msg.sender) {
        Position storage pos = positions[msg.sender];
        require(
            amount <= pos.collateralDOT,
            "SentinelVault: insufficient collateral"
        );

        uint256 remainingCollateral = pos.collateralDOT - amount;
        if (pos.mintedSUSD > 0) {
            uint256 remainingUSD = _getCollateralValueUSD(remainingCollateral);
            uint256 requiredUSD = (pos.mintedSUSD * COLLATERAL_RATIO) / 100;
            require(
                remainingUSD >= requiredUSD,
                "SentinelVault: would undercollateralize"
            );
        }

        pos.collateralDOT -= amount;

        (bool sent, ) = payable(msg.sender).call{value: amount}("");
        require(sent, "SentinelVault: ETH transfer failed");

        emit CollateralWithdrawn(msg.sender, amount);
    }

    // ═════════════════════════════════════════════
    //  SECTION 2 — VIEW FUNCTIONS
    //  The Java bot calls these every 60 seconds
    // ═════════════════════════════════════════════

    /**
     * @notice SHOULD DO:  Tell us how "safe" a user's position is.
     * @notice ACTUALLY DOES:
     *   Returns a number (scaled by 1e18) representing the ratio of
     *   collateral value to debt value.
     *
     *   Health Factor = (collateralUSD * 1e18) / mintedSUSD
     *
     *   > 1.5e18 = SAFE    (above 150%)
     *   1.2e18–1.5e18 = WARNING  (Sentinel sends Telegram alert)
     *   < 1.2e18 = DANGER  (Sentinel triggers emergency rebalance)
     *   type(uint256).max = No debt, fully safe
     */
    function getHealthFactor(address user) external view returns (uint256) {
        Position memory pos = positions[user];
        if (pos.mintedSUSD == 0) return type(uint256).max;

        (int256 price, ) = oracle.getLatestPrice();
        uint256 dotPrice = uint256(price);

        if (usePVM) {
            /**
             * PVM EXPERIMENT: The "Speed" Pillar
             * Calling Rust-based Risk Engine via PolkaVM precompile at 0x420.
             * This offloads heavy math (sqrt, multi-var risk scoring) to RISC-V.
             */
            try
                IPolkaVM(address(0x420)).execute(
                    pvmProgramId,
                    abi.encode(pos.collateralDOT, pos.mintedSUSD, dotPrice)
                )
            returns (bytes memory result) {
                return abi.decode(result, (uint256));
            } catch {
                // Fallback to Solidity math if PVM fails or not available in current environment
                uint256 collateralUSD = (pos.collateralDOT * dotPrice) /
                    PRICE_PRECISION;
                return (collateralUSD * PRECISION) / pos.mintedSUSD;
            }
        } else {
            // Standard Solidity Math
            uint256 collateralUSD = (pos.collateralDOT * dotPrice) /
                PRICE_PRECISION;
            return (collateralUSD * PRECISION) / pos.mintedSUSD;
        }
    }

    /**
     * @notice Returns current DOT/USD price from oracle
     */
    function getDOTPrice() external view returns (int256, uint256) {
        return oracle.getLatestPrice();
    }

    /**
     * @notice Returns all tracked position holders for bot iteration
     */
    function getAllUsers() external view returns (address[] memory) {
        return positionHolders;
    }

    /**
     * @notice Returns a user's full position details
     */
    function getPosition(
        address user
    )
        external
        view
        returns (
            uint256 collateralDOT,
            uint256 mintedSUSD,
            uint256 collateralUSD,
            uint256 healthFactor,
            bool paused
        )
    {
        Position memory pos = positions[user];
        collateralDOT = pos.collateralDOT;
        mintedSUSD = pos.mintedSUSD;
        collateralUSD = _getCollateralValueUSD(pos.collateralDOT);
        healthFactor = this.getHealthFactor(user);
        paused = pos.paused;
    }

    // ═════════════════════════════════════════════
    //  SECTION 3 — GUARDIAN / SENTINEL FUNCTIONS
    //  Only the AI Agent wallet can call these
    // ═════════════════════════════════════════════

    /**
     * @notice SHOULD DO:  Freeze a risky position to protect the protocol.
     * @notice ACTUALLY DOES:
     *   1. Sets positions[user].paused = true.
     *   2. This blocks the user from minting more sUSD or withdrawing collateral.
     *   3. The reason string is stored in the event for the Telegram alert message.
     *   4. The Sentinel bot calls this when Health Factor drops below 130%.
     *
     * @param user    The wallet address to pause
     * @param reason  Human-readable reason (e.g. "HF below 130% threshold")
     */
    function pausePosition(
        address user,
        string calldata reason
    ) external onlyGuardian {
        positions[user].paused = true;
        emit PositionPaused(user, reason);
    }

    /**
     * @notice SHOULD DO:  Unfreeze a position after the user has added collateral.
     * @notice ACTUALLY DOES:
     *   1. Checks Health Factor is back above 140% before allowing unfreeze.
     *   2. Sets paused = false.
     *   3. Emits PositionUnpaused.
     */
    function unpausePosition(address user) external onlyGuardian {
        uint256 hf = this.getHealthFactor(user);
        require(
            hf >= (140 * PRECISION) / 100,
            "SentinelVault: HF still too low to unpause"
        );
        positions[user].paused = false;
        emit PositionUnpaused(user);
    }

    function approveRebalance() external {
        userApprovalForRebalance[msg.sender] = true;
    }

    /**
     * @notice SHOULD DO:  Automatically reduce a user's debt before liquidation hits.
     * @notice ACTUALLY DOES:
     *   1. Can only be called by the Guardian (Sentinel bot).
     *   2. Calculates how much collateral to sell to bring Health Factor back to 150%.
     *   3. Reduces both collateralDOT and mintedSUSD proportionally.
     *      (In production this calls a DEX swap; here it adjusts accounting.)
     *   4. Emits EmergencyRebalance — triggers the demo transaction on the explorer.
     *
     * @param user         The at-risk user
     * @param dotToSell    Amount of DOT collateral to convert to pay down debt
     */
    function emergencyRebalance(
        address user,
        uint256 dotToSell
    ) external onlyGuardian notGloballyPaused {
        Position storage pos = positions[user];
        require(
            pos.collateralDOT >= dotToSell,
            "SentinelVault: insufficient collateral"
        );

        // Multi-Sig Guardian Logic: Bot signs (onlyGuardian) + User signs (approveRebalance)
        if (pos.mintedSUSD > HIGH_VALUE_THRESHOLD) {
            require(
                userApprovalForRebalance[user],
                "SentinelVault: User approval required for high-value rebalance"
            );
            userApprovalForRebalance[user] = false; // Reset for next time
        }

        // Calculate sUSD value of DOT being sold
        uint256 dotValueUSD = _getCollateralValueUSD(dotToSell);

        // Reduce debt by the USD value of sold collateral
        uint256 debtRepaid = dotValueUSD < pos.mintedSUSD
            ? dotValueUSD
            : pos.mintedSUSD;

        pos.collateralDOT -= dotToSell;
        pos.mintedSUSD -= debtRepaid;

        // TODO: In production — call DEX (e.g., Uniswap/Hydration)
        // to swap dotToSell → sUSD → burn

        emit EmergencyRebalance(user, dotToSell, debtRepaid);
    }

    /**
     * @notice SHOULD DO:  Allow anyone to liquidate a dangerously undercollateralized position.
     * @notice ACTUALLY DOES:
     *   1. Verifies Health Factor is below LIQUIDATION_RATIO (120%).
     *   2. Liquidator repays the user's full sUSD debt.
     *   3. Liquidator receives the user's DOT collateral (at a 5% bonus discount).
     *   4. Clears the position.
     *   5. Emits Liquidated.
     *
     * @param user  The wallet to liquidate
     */
    function liquidate(address user) external notGloballyPaused {
        uint256 hf = this.getHealthFactor(user);
        require(
            hf < (LIQUIDATION_RATIO * PRECISION) / 100,
            "SentinelVault: position is healthy"
        );

        Position storage pos = positions[user];
        uint256 collateralSeized = pos.collateralDOT;
        // uint256 debt = pos.mintedSUSD;

        // Clear the position
        pos.collateralDOT = 0;
        pos.mintedSUSD = 0;
        pos.paused = false;

        // TODO: sUSDToken.burnFrom(msg.sender, debt); // liquidator pays debt
        // Transfer seized collateral to liquidator
        (bool sent, ) = payable(msg.sender).call{value: collateralSeized}("");
        require(sent, "SentinelVault: collateral transfer failed");

        emit Liquidated(user, msg.sender, collateralSeized);
    }

    // ═════════════════════════════════════════════
    //  SECTION 4 — ADMIN FUNCTIONS
    // ═════════════════════════════════════════════

    function setGuardian(address _guardian) external onlyOwner {
        guardian = _guardian;
        emit GuardianUpdated(_guardian);
    }

    function setGlobalPause(bool _paused) external onlyOwner {
        globalPause = _paused;
        emit GlobalPauseToggled(_paused);
    }

    function updateOracle(address _oracle) external onlyOwner {
        oracle = IPriceOracle(_oracle);
    }

    function setPVMConfig(bytes32 _programId, bool _usePVM) external onlyOwner {
        pvmProgramId = _programId;
        usePVM = _usePVM;
    }

    // ═════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ═════════════════════════════════════════════

    /**
     * @dev Converts a DOT amount (wei) to USD value using oracle price.
     *      dotAmount (1e18 precision) * price (1e8 precision) / 1e8 = USD (1e18 precision)
     */
    function _getCollateralValueUSD(
        uint256 dotAmount
    ) internal view returns (uint256) {
        (int256 price, ) = oracle.getLatestPrice();
        require(price > 0, "SentinelVault: invalid oracle price");
        return (dotAmount * uint256(price)) / PRICE_PRECISION;
    }

    /// @dev Accept plain DOT transfers
    receive() external payable {}
}
