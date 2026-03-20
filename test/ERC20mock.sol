// SPDX-License-Identifier: MIT
pragma solidity ^0.8.20;

/**
 * @title ERC20Mock (Mock USDT)
 * @notice Drop-in replacement for the real USDT Asset Hub precompile.
 *
 * ⚠️  TESTNET ONLY — NOT FOR MAINNET DEPLOYMENT
 *
 * WHY THIS EXISTS:
 *   The real USDT on Polkadot Asset Hub is available via a precompile
 *   address, but that precompile does NOT exist on the Polkadot Hub
 *   testnet. Any contract or frontend trying to call it gets:
 *     "USDT contract not available on this network."
 *
 *   This mock deploys a standard ERC-20 at a real address you control,
 *   behaves identically to USDT (6 decimals, same interface), and lets
 *   you mint infinite tokens for testing — no faucet needed.
 *
 * HOW TO WIRE IT IN:
 *   After deploying, pass this contract's address wherever your
 *   frontend or SentinelVault expects the USDT address.
 *
 * README NOTE:
 *   "Mock USDT contract used for testing due to testnet
 *    Asset Hub precompile unavailability. On mainnet, replace
 *    this address with the real USDT Asset Hub precompile."
 */
contract ERC20Mock {
    // ── Metadata ──────────────────────────────────────────────────
    // Matches real USDT exactly — name, symbol, decimals
    string public name;
    string public symbol;
    uint8 public decimals;

    // ── State ─────────────────────────────────────────────────────
    uint256 public totalSupply;
    address public owner;

    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;

    // ── Faucet & Swap ──────────────────────────────────────────
    uint256 public constant FAUCET_AMOUNT = 10_000 * 1e6; // 10,000 USDT (6 decimals)
    uint256 public constant FAUCET_COOLDOWN = 24 hours;
    mapping(address => uint256) public lastFaucetTime;

    // Mock exchange rate: 1 DOT = X USDT (6 decimals)
    // e.g. price = 1500000 => 1 DOT = 1.50 USDT
    uint256 public price = 1_500_000;

    // ── Events (standard ERC-20) ──────────────────────────────────
    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner_, address indexed spender, uint256 value);
    event Minted(address indexed to, uint256 amount);
    event Swapped(address indexed user, uint256 dotAmount, uint256 usdtAmount);

    modifier onlyOwner() {
        require(msg.sender == owner, "ERC20Mock: not owner");
        _;
    }

    /**
     * @param _name     Token name   — pass "Tether USD"
     * @param _symbol   Token symbol — pass "USDT"
     * @param _decimals Decimals     — pass 6 (real USDT uses 6, NOT 18)
     *
     * Constructor mints 10,000,000 USDT to the deployer immediately.
     * No faucet needed for your own wallet.
     */
    constructor(string memory _name, string memory _symbol, uint8 _decimals) {
        owner = msg.sender;
        name = _name;
        symbol = _symbol;
        decimals = _decimals;

        // Mint 10 million USDT to deployer on deploy
        _mint(msg.sender, 10_000_000 * (10 ** _decimals));
    }

    // ═════════════════════════════════════════════
    //  STANDARD ERC-20
    // ═════════════════════════════════════════════

    function transfer(address to, uint256 amount) external returns (bool) {
        require(to != address(0), "ERC20Mock: transfer to zero address");
        require(balanceOf[msg.sender] >= amount, "ERC20Mock: insufficient balance");
        balanceOf[msg.sender] -= amount;
        balanceOf[to] += amount;
        emit Transfer(msg.sender, to, amount);
        return true;
    }

    function approve(address spender, uint256 amount) external returns (bool) {
        require(spender != address(0), "ERC20Mock: approve to zero address");
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    function transferFrom(address from, address to, uint256 amount) external returns (bool) {
        require(to != address(0), "ERC20Mock: transfer to zero address");
        require(balanceOf[from] >= amount, "ERC20Mock: insufficient balance");
        require(allowance[from][msg.sender] >= amount, "ERC20Mock: insufficient allowance");
        allowance[from][msg.sender] -= amount;
        balanceOf[from] -= amount;
        balanceOf[to] += amount;
        emit Transfer(from, to, amount);
        return true;
    }

    // ═════════════════════════════════════════════
    //  MINT & SWAP FUNCTIONS
    // ═════════════════════════════════════════════

    /**
     * Free 10,000 USDT for anyone — once per 24 hours.
     * Judges and testers use this to get USDT without a faucet.
     */
    function faucet() public {
        require(block.timestamp >= lastFaucetTime[msg.sender] + FAUCET_COOLDOWN, "ERC20Mock: cooldown active wait 24h");
        lastFaucetTime[msg.sender] = block.timestamp;
        _mint(msg.sender, FAUCET_AMOUNT);
    }

    /**
     * Swap native DOT for mock USDT.
     * The USDT is minted to the sender based on the 'price' rate.
     */
    function swap() external payable {
        require(msg.value > 0, "ERC20Mock: must send DOT to swap");

        // usdtAmount = (dotWei * price) / 1e18
        // since price has 6 decimals, it acts as a multiplier
        uint256 usdtAmount = (msg.value * price) / 1e18;
        require(usdtAmount > 0, "ERC20Mock: swap amount too small");

        _mint(msg.sender, usdtAmount);
        emit Swapped(msg.sender, msg.value, usdtAmount);
    }

    /**
     * Update the mock price (1 DOT = X USDT).
     * @param _price Price in 6 decimals (e.g. 1500000 for $1.50)
     */
    function setPrice(uint256 _price) external onlyOwner {
        price = _price;
    }

    /**
     * Owner mints any amount to any address. No cooldown.
     * Use this for demo setup and test scenarios.
     *
     * @param to     Recipient wallet
     * @param amount Amount in smallest unit (6 decimals for USDT)
     *               e.g. 1000 USDT = 1000 * 1e6 = 1_000_000_000
     */
    function mint(address to, uint256 amount) external onlyOwner {
        require(to != address(0), "ERC20Mock: mint to zero address");
        _mint(to, amount);
        emit Minted(to, amount);
    }

    /**
     * Batch mint to multiple wallets in one transaction.
     * Useful for setting up multiple test positions quickly.
     */
    function mintBatch(address[] calldata recipients, uint256[] calldata amounts) external onlyOwner {
        require(recipients.length == amounts.length, "ERC20Mock: length mismatch");
        for (uint256 i = 0; i < recipients.length; i++) {
            require(recipients[i] != address(0), "ERC20Mock: zero address in batch");
            _mint(recipients[i], amounts[i]);
            emit Minted(recipients[i], amounts[i]);
        }
    }

    // ── Internal ──────────────────────────────────────────────────
    function _mint(address to, uint256 amount) internal {
        totalSupply += amount;
        balanceOf[to] += amount;
        emit Transfer(address(0), to, amount);
    }
}
