// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title SentinelUSD (sUSD)
 * @notice The stablecoin minted by SentinelVault as debt against DOT collateral.
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │  WHAT IT SHOULD DO                                          │
 * │  A standard ERC-20 token that represents 1:1 USD debt.     │
 * │  Only the SentinelVault contract is allowed to mint or      │
 * │  burn it — users can never print money for free.           │
 * │                                                             │
 * │  WHAT IT ACTUALLY DOES                                      │
 * │  1. Implements full ERC-20 (transfer, approve, allowance).  │
 * │  2. Restricts mint() and burn() to the vault address only.  │
 * │  3. Owner can update the vault address (for upgrades).      │
 * │  4. Tracks minted supply cap (optional safety ceiling).     │
 * └─────────────────────────────────────────────────────────────┘
 */

// ─────────────────────────────────────────────
//  MINIMAL ERC-20 IMPLEMENTATION (no imports needed)
//  Self-contained so Foundry compiles without OpenZeppelin
// ─────────────────────────────────────────────

contract SentinelUSD {

    // ── ERC-20 Metadata ──────────────────────────────────────────
    string  public constant name     = "Sentinel USD";
    string  public constant symbol   = "sUSD";
    uint8   public constant decimals = 18;

    // ── Storage ──────────────────────────────────────────────────
    uint256 public totalSupply;
    uint256 public supplyCap;          // Hard ceiling on total sUSD that can exist (0 = no cap)

    address public owner;
    address public vault;              // SentinelVault — the only minter/burner

    mapping(address => uint256)                     public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;

    // ── Events ───────────────────────────────────────────────────
    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner_, address indexed spender, uint256 value);
    event VaultUpdated(address indexed newVault);
    event SupplyCapUpdated(uint256 newCap);
    event OwnershipTransferred(address indexed previousOwner, address indexed newOwner);

    // ── Modifiers ─────────────────────────────────────────────────
    modifier onlyOwner() {
        require(msg.sender == owner, "sUSD: not owner");
        _;
    }

    /**
     * @dev WHAT IT DOES: Restricts mint/burn to the vault.
     *      This is the critical security gate — without it anyone
     *      could call mint() and create infinite sUSD.
     */
    modifier onlyVault() {
        require(msg.sender == vault, "sUSD: caller is not the vault");
        _;
    }

    // ── Constructor ───────────────────────────────────────────────
    /**
     * @param _vault    Address of SentinelVault (set on deploy)
     * @param _supplyCap Max sUSD that can ever be minted (0 = unlimited)
     *
     * WHAT IT DOES: Stores the vault address and ownership.
     * The vault address is set at deploy time and can be updated
     * by the owner — this lets you upgrade the vault later.
     */
    constructor(address _vault, uint256 _supplyCap) {
        require(_vault != address(0), "sUSD: zero vault address");
        owner     = msg.sender;
        vault     = _vault;
        supplyCap = _supplyCap;
    }

    // ═════════════════════════════════════════════
    //  SECTION 1 — STANDARD ERC-20 FUNCTIONS
    // ═════════════════════════════════════════════

    /**
     * SHOULD DO:  Move tokens from caller to `to`.
     * ACTUALLY DOES:
     *   1. Checks caller has enough balance.
     *   2. Subtracts from sender, adds to recipient.
     *   3. Emits Transfer event.
     *   Users call this to send sUSD to others (e.g., repay a friend's debt).
     */
    function transfer(address to, uint256 amount) external returns (bool) {
        require(to != address(0), "sUSD: transfer to zero address");
        require(balanceOf[msg.sender] >= amount, "sUSD: insufficient balance");

        balanceOf[msg.sender] -= amount;
        balanceOf[to]         += amount;

        emit Transfer(msg.sender, to, amount);
        return true;
    }

    /**
     * SHOULD DO:  Approve a spender to pull tokens on your behalf.
     * ACTUALLY DOES:
     *   Sets the allowance mapping so `spender` can call transferFrom()
     *   up to `amount` tokens from `msg.sender`.
     *   The vault calls this pattern when burning during rebalance.
     */
    function approve(address spender, uint256 amount) external returns (bool) {
        require(spender != address(0), "sUSD: approve to zero address");
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    /**
     * SHOULD DO:  Pull approved tokens from `from` to `to`.
     * ACTUALLY DOES:
     *   1. Verifies the caller has sufficient allowance.
     *   2. Deducts from allowance AND from `from`'s balance.
     *   3. Credits `to`.
     *   4. The vault uses this in burnFrom() during liquidation
     *      — the liquidator first approves the vault, then the vault
     *      pulls and burns their sUSD to settle the debt.
     */
    function transferFrom(address from, address to, uint256 amount) external returns (bool) {
        require(to != address(0), "sUSD: transfer to zero address");
        require(balanceOf[from]           >= amount, "sUSD: insufficient balance");
        require(allowance[from][msg.sender] >= amount, "sUSD: insufficient allowance");

        allowance[from][msg.sender] -= amount;
        balanceOf[from]             -= amount;
        balanceOf[to]               += amount;

        emit Transfer(from, to, amount);
        return true;
    }

    // ═════════════════════════════════════════════
    //  SECTION 2 — VAULT-ONLY MINT & BURN
    //  These replace the TODO stubs in SentinelVault
    // ═════════════════════════════════════════════

    /**
     * SHOULD DO:  Create new sUSD when a user mints against their collateral.
     * ACTUALLY DOES:
     *   1. Enforces the supply cap (if set) — prevents infinite inflation.
     *   2. Increases totalSupply and recipient's balance atomically.
     *   3. Emits Transfer from address(0) — the ERC-20 standard signal for minting.
     *
     *   Called by SentinelVault.mintStablecoin() — replaces the TODO there.
     *
     * @param to      User who receives the new sUSD
     * @param amount  How much to mint (18 decimals)
     */
    function mint(address to, uint256 amount) external onlyVault {
        require(to != address(0), "sUSD: mint to zero address");
        require(amount > 0,       "sUSD: mint amount is zero");

        if (supplyCap > 0) {
            require(totalSupply + amount <= supplyCap, "sUSD: supply cap exceeded");
        }

        totalSupply    += amount;
        balanceOf[to]  += amount;

        emit Transfer(address(0), to, amount);
    }

    /**
     * SHOULD DO:  Destroy sUSD when a user repays debt or gets liquidated.
     * ACTUALLY DOES:
     *   1. Checks the target address has enough balance.
     *   2. Reduces totalSupply and from's balance.
     *   3. Emits Transfer to address(0) — ERC-20 standard for burning.
     *
     *   Called by SentinelVault in two situations:
     *   a) burnStablecoin()    — user voluntarily repays
     *   b) liquidate()         — liquidator's sUSD is burned to settle debt
     *      (vault calls transferFrom first to pull from liquidator, then burnFrom)
     *
     * @param from    Address whose sUSD is destroyed
     * @param amount  How much to burn
     */
    function burnFrom(address from, uint256 amount) external onlyVault {
        require(balanceOf[from] >= amount, "sUSD: burn exceeds balance");

        balanceOf[from] -= amount;
        totalSupply     -= amount;

        emit Transfer(from, address(0), amount);
    }

    // ═════════════════════════════════════════════
    //  SECTION 3 — ADMIN FUNCTIONS
    // ═════════════════════════════════════════════

    /**
     * SHOULD DO:  Update the vault address (for contract upgrades).
     * ACTUALLY DOES:
     *   Replaces the stored `vault` address so a new version of
     *   SentinelVault gains mint/burn rights. Old vault loses them instantly.
     *   Emits VaultUpdated for off-chain monitoring.
     */
    function setVault(address _vault) external onlyOwner {
        require(_vault != address(0), "sUSD: zero address");
        vault = _vault;
        emit VaultUpdated(_vault);
    }

    /**
     * SHOULD DO:  Adjust the maximum sUSD that can exist.
     * ACTUALLY DOES:
     *   Updates supplyCap. Set to 0 to remove the cap entirely.
     *   Can only be raised, not lowered below current supply,
     *   to prevent locking users out of repaying.
     */
    function setSupplyCap(uint256 _cap) external onlyOwner {
        require(_cap == 0 || _cap >= totalSupply, "sUSD: cap below current supply");
        supplyCap = _cap;
        emit SupplyCapUpdated(_cap);
    }

    /**
     * SHOULD DO:  Transfer contract ownership.
     * ACTUALLY DOES: Two-step would be safer but for hackathon,
     *   direct transfer to newOwner. Guards against zero address.
     */
    function transferOwnership(address newOwner) external onlyOwner {
        require(newOwner != address(0), "sUSD: zero address");
        emit OwnershipTransferred(owner, newOwner);
        owner = newOwner;
    }
}
