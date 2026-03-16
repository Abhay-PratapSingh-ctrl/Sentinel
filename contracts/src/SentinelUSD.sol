// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title SentinelUSD (sUSD)
 * @notice The stablecoin minted by SentinelVault as debt against DOT/USDT collateral.
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │  WHAT IT DOES                                               │
 * │  A standard ERC-20 token that represents 1:1 USD debt.     │
 * │  Only the SentinelVault contract is allowed to mint or      │
 * │  burn it — users can never print money for free.           │
 * │                                                             │
 * │  FEATURES                                                   │
 * │  1. Full ERC-20 (transfer, approve, allowance, transferFrom)│
 * │  2. mint() and burn() restricted to vault address only.    │
 * │  3. Owner can update vault address (upgradeable).          │
 * │  4. Optional supply cap as a hard inflation ceiling.       │
 * └─────────────────────────────────────────────────────────────┘
 */
contract SentinelUSD {
    // ── ERC-20 Metadata ──────────────────────────────────────────
    string public constant name = "Sentinel USD";
    string public constant symbol = "sUSD";
    uint8 public constant decimals = 18;

    // ── Storage ──────────────────────────────────────────────────
    uint256 public totalSupply;
    uint256 public supplyCap; // Hard ceiling on total sUSD (0 = no cap)

    address public owner;
    address public vault; // SentinelVault — the only minter/burner

    mapping(address => uint256) public balanceOf;
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

    modifier onlyVault() {
        require(msg.sender == vault, "sUSD: caller is not the vault");
        _;
    }

    // ── Constructor ───────────────────────────────────────────────
    /**
     * @param _vault     Address of SentinelVault (set at deploy time)
     * @param _supplyCap Max sUSD that can ever be minted (0 = unlimited)
     */
    constructor(address _vault, uint256 _supplyCap) {
        require(_vault != address(0), "sUSD: zero vault address");
        owner = msg.sender;
        vault = _vault;
        supplyCap = _supplyCap;
    }

    // ═════════════════════════════════════════════
    //  SECTION 1 — STANDARD ERC-20
    // ═════════════════════════════════════════════

    function transfer(address to, uint256 amount) external returns (bool) {
        require(to != address(0), "sUSD: transfer to zero address");
        require(balanceOf[msg.sender] >= amount, "sUSD: insufficient balance");
        balanceOf[msg.sender] -= amount;
        balanceOf[to] += amount;
        emit Transfer(msg.sender, to, amount);
        return true;
    }

    function approve(address spender, uint256 amount) external returns (bool) {
        require(spender != address(0), "sUSD: approve to zero address");
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    function transferFrom(address from, address to, uint256 amount) external returns (bool) {
        require(to != address(0), "sUSD: transfer to zero address");
        require(balanceOf[from] >= amount, "sUSD: insufficient balance");
        require(allowance[from][msg.sender] >= amount, "sUSD: insufficient allowance");
        allowance[from][msg.sender] -= amount;
        balanceOf[from] -= amount;
        balanceOf[to] += amount;
        emit Transfer(from, to, amount);
        return true;
    }

    // ═════════════════════════════════════════════
    //  SECTION 2 — VAULT-ONLY MINT & BURN
    // ═════════════════════════════════════════════

    /**
     * @notice Mint new sUSD. Called by vault when user deposits collateral and borrows.
     * @param to      Recipient of new sUSD
     * @param amount  Amount to mint (18 decimals)
     */
    function mint(address to, uint256 amount) external onlyVault {
        require(to != address(0), "sUSD: mint to zero address");
        require(amount > 0, "sUSD: mint amount is zero");
        if (supplyCap > 0) {
            require(totalSupply + amount <= supplyCap, "sUSD: supply cap exceeded");
        }
        totalSupply += amount;
        balanceOf[to] += amount;
        emit Transfer(address(0), to, amount);
    }

    /**
     * @notice Burn sUSD to reduce debt. Called by vault on repayment or liquidation.
     * @param from    Address whose sUSD is destroyed
     * @param amount  Amount to burn
     */
    function burnFrom(address from, uint256 amount) external onlyVault {
        require(balanceOf[from] >= amount, "sUSD: burn exceeds balance");
        balanceOf[from] -= amount;
        totalSupply -= amount;
        emit Transfer(from, address(0), amount);
    }

    // ═════════════════════════════════════════════
    //  SECTION 3 — ADMIN
    // ═════════════════════════════════════════════

    /**
     * @notice Point to a new vault contract (upgrade path).
     */
    function setVault(address _vault) external onlyOwner {
        require(_vault != address(0), "sUSD: zero address");
        vault = _vault;
        emit VaultUpdated(_vault);
    }

    /**
     * @notice Adjust supply ceiling. Cannot be set below current totalSupply.
     */
    function setSupplyCap(uint256 _cap) external onlyOwner {
        require(_cap == 0 || _cap >= totalSupply, "sUSD: cap below current supply");
        supplyCap = _cap;
        emit SupplyCapUpdated(_cap);
    }

    /**
     * @notice Transfer contract ownership.
     */
    function transferOwnership(address newOwner) external onlyOwner {
        require(newOwner != address(0), "sUSD: zero address");
        emit OwnershipTransferred(owner, newOwner);
        owner = newOwner;
    }
}
