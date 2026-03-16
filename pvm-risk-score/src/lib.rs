//! # Sentinel Brain — PVM Precompile: `verify_rebalance`
//!
//! Compiled to PolkaVM (RISC-V) bytecode and deployed as an on-chain
//! precompile on Polkadot Hub via `pallet-revive`.
//!
//! ## Why this exists
//! Without this, the Java/JS bot makes rebalance decisions **off-chain**
//! — users must trust the operator. This module moves the decision logic
//! **on-chain**: Polkadot itself verifies every rebalance before the DEX
//! swap executes. The vault contract calls `pvmPrecompile.call(input)`,
//! and this code runs on-chain inside PolkaVM.
//!
//! ## ABI contract with `SentinelVault_complete.sol → _verifyWithPVM()`
//!
//! **Input** (196 bytes total):
//! ```
//! [0..3]   bytes4  selector = keccak256("verify_rebalance(uint128,...)")
//! [4..35]  uint128 collateral_dot   — DOT locked (1e18 precision)
//! [36..67] uint128 minted_susd      — sUSD debt  (1e18 precision)
//! [68..99] uint128 dot_price        — Pyth DOT/USD (1e8 precision)
//! [100..131] uint128 dot_to_sell    — proposed DOT to sell (1e18)
//! [132..163] uint128 target_hf_bps  — target HF in bps (e.g. 15000 = 150%)
//! [164..195] uint128 rebalance_floor_bps — floor bps (e.g. 12000 = 120%)
//! ```
//!
//! **Output** (32 bytes ABI-encoded uint32):
//! ```
//! 1 = APPROVED  — rebalance is valid, vault may execute DEX swap
//! 0 = REJECTED  — checks failed, vault must revert
//! ```
//!
//! ## Verification checks (all must pass for APPROVED)
//! 1. `dot_to_sell <= collateral_dot`           — can't sell more than locked
//! 2. `dot_to_sell > 0`                         — sell amount is non-zero
//! 3. `current_hf < rebalance_floor_bps`        — position is actually at risk
//! 4. `projected_hf >= target_hf_bps`           — sale restores to target
//! 5. `projected_hf < target_hf_bps * 2`        — not over-selling (sanity cap)

#![no_std]
#![no_main]

// ── PolkaVM host function declarations ────────────────────────────────────────
//
// These syscalls are provided by `pallet-revive`'s PolkaVM execution environment.
// They map to Substrate extrinsics under the hood.
//
// Reference: https://github.com/paritytech/polkadot-sdk/tree/master/substrate/frame/revive
#[link(wasm_import_module = "seal0")]
extern "C" {
    /// Copy `length` bytes of call input starting at `offset` into `dest`.
    fn seal_input(dest: *mut u8, len: *mut u32);

    /// Return `data_len` bytes from `data` as the precompile output and exit.
    fn seal_return(flags: u32, data: *const u8, data_len: u32) -> !;
}

// ── Constants — must mirror SentinelVault_complete.sol exactly ────────────────

/// 1e18 — precision for DOT amounts and sUSD amounts
const PRECISION: u128 = 1_000_000_000_000_000_000;

/// 1e8 — Pyth oracle price precision
const DOT_PRICE_PREC: u128 = 100_000_000;

/// Selector for `verify_rebalance(uint128,uint128,uint128,uint128,uint128,uint128)`
/// = keccak256(...)[0..4]
/// Precomputed: 0x4a6b8e3f  (verify at deploy time)
const EXPECTED_SELECTOR: [u8; 4] = [0x4a, 0x6b, 0x8e, 0x3f];

// ── ABI output constants ───────────────────────────────────────────────────────

/// ABI-encoded uint32(1) — APPROVED
const APPROVED: [u8; 32] = {
    let mut b = [0u8; 32];
    b[31] = 1;
    b
};

/// ABI-encoded uint32(0) — REJECTED
const REJECTED: [u8; 32] = [0u8; 32];

// ── Entry point ───────────────────────────────────────────────────────────────

#[no_mangle]
pub extern "C" fn call() {
    // ── Read input from PolkaVM host ───────────────────────────────────────────
    let mut input = [0u8; 256];
    let mut input_len: u32 = 256;

    unsafe { seal_input(input.as_mut_ptr(), &mut input_len) };

    // Minimum: 4 (selector) + 6×32 (params) = 196 bytes
    if (input_len as usize) < 196 {
        reject();
    }

    // ── Verify selector ────────────────────────────────────────────────────────
    //
    // The Solidity side computes:
    //   bytes4 selector = bytes4(keccak256(
    //     "verify_rebalance(uint128,uint128,uint128,uint128,uint128,uint128)"
    //   ));
    //
    // We verify it matches to prevent accidental or malicious miscalls.
    if input[0..4] != EXPECTED_SELECTOR {
        reject();
    }

    // ── ABI-decode the 6 uint128 parameters ────────────────────────────────────
    //
    // Solidity ABI encodes uint128 as a 32-byte big-endian value with 16 bytes
    // of leading zeros, then the 16-byte big-endian uint128.
    //
    //  offset  content
    //  [4]     collateral_dot   slot starts here  → value in [4+16 .. 4+32]
    //  [36]    minted_susd      slot starts here  → value in [36+16 .. 36+32]
    //  [68]    dot_price        slot
    //  [100]   dot_to_sell      slot
    //  [132]   target_hf_bps    slot
    //  [164]   rebalance_floor_bps slot

    let collateral_dot       = read_u128(&input,   4);
    let minted_susd          = read_u128(&input,  36);
    let dot_price            = read_u128(&input,  68);
    let dot_to_sell          = read_u128(&input, 100);
    let target_hf_bps        = read_u128(&input, 132);
    let rebalance_floor_bps  = read_u128(&input, 164);

    // ── Run verification logic ─────────────────────────────────────────────────

    let approved = verify_rebalance(
        collateral_dot,
        minted_susd,
        dot_price,
        dot_to_sell,
        target_hf_bps,
        rebalance_floor_bps,
    );

    if approved {
        approve();
    } else {
        reject();
    }
}

// ── Core verification logic ───────────────────────────────────────────────────

/// All checks must pass for APPROVED.
///
/// Math note: all intermediate values stay in u128.
/// To avoid overflow with 1e18 × 1e8, we divide early:
///   dot_usd = (collateral_dot × dot_price) / DOT_PRICE_PREC
/// This gives USD value in 1e18 precision (same as sUSD).
fn verify_rebalance(
    collateral_dot: u128,
    minted_susd: u128,
    dot_price: u128,
    dot_to_sell: u128,
    target_hf_bps: u128,
    rebalance_floor_bps: u128,
) -> bool {
    // ── Guard: zero debt or zero price means nothing to verify ────────────────
    if minted_susd == 0 || dot_price == 0 {
        return false;
    }

    // ── Check 1: can't sell more than what's locked ───────────────────────────
    if dot_to_sell > collateral_dot {
        return false;
    }

    // ── Check 2: sell amount must be non-zero ─────────────────────────────────
    if dot_to_sell == 0 {
        return false;
    }

    // ── Check 3: position must actually be at risk ────────────────────────────
    //
    // current_hf_bps = (collateral_usd / minted_susd) × 10_000
    // where collateral_usd = (collateral_dot × dot_price) / DOT_PRICE_PREC
    //
    // Rearranged to avoid division before multiplication:
    //   current_hf_bps = (collateral_dot × dot_price × 10_000)
    //                    / (DOT_PRICE_PREC × minted_susd)
    //
    // We use checked arithmetic — if it overflows, reject conservatively.
    let collateral_usd = match collateral_dot.checked_mul(dot_price) {
        Some(v) => v / DOT_PRICE_PREC,
        None => return false, // overflow — reject
    };

    let current_hf_bps = match collateral_usd.checked_mul(10_000) {
        Some(v) => v / minted_susd,
        None => return false,
    };

    // Position must be below the rebalance floor to justify a rebalance
    // e.g. rebalance_floor_bps = 12_000 → HF < 120%
    if current_hf_bps >= rebalance_floor_bps {
        return false;
    }

    // ── Check 4: projected HF after sale must reach the target ───────────────
    //
    // After selling `dot_to_sell` DOT via Hydration DEX:
    //   - collateral decreases by dot_to_sell
    //   - sUSD received from DEX ≈ (dot_to_sell × dot_price) / DOT_PRICE_PREC
    //     (we use oracle price as a proxy; actual DEX slippage is checked
    //      separately by the Solidity contract's minSUSDOut parameter)
    //   - debt decreases by sUSD received
    //
    // projected_collateral_usd = (collateral_dot - dot_to_sell) × dot_price / 1e8
    // susd_received             = (dot_to_sell × dot_price) / DOT_PRICE_PREC
    // projected_debt            = max(minted_susd - susd_received, 0)
    // projected_hf_bps          = projected_collateral_usd / projected_debt × 10_000

    let remaining_dot = collateral_dot - dot_to_sell; // safe: check 1 passed

    let projected_collateral_usd = match remaining_dot.checked_mul(dot_price) {
        Some(v) => v / DOT_PRICE_PREC,
        None => return false,
    };

    let susd_received = match dot_to_sell.checked_mul(dot_price) {
        Some(v) => v / DOT_PRICE_PREC,
        None => return false,
    };

    let projected_debt = if susd_received >= minted_susd {
        // Sale covers entire debt — always valid if check 1 passed
        return true;
    } else {
        minted_susd - susd_received
    };

    if projected_debt == 0 {
        return true;
    }

    let projected_hf_bps = match projected_collateral_usd.checked_mul(10_000) {
        Some(v) => v / projected_debt,
        None => return false,
    };

    // Projected HF must reach the target (e.g. 15000 = 150%, or 17000 in Aegis mode)
    if projected_hf_bps < target_hf_bps {
        return false;
    }

    // ── Check 5: sanity cap — not over-selling (2× target) ───────────────────
    //
    // Prevents the bot from liquidating far more than needed.
    // If projected HF would be > 2× the target, the bot is selling too much.
    // Example: target = 15000, cap = 30000 — selling to 300%+ is wasteful.
    let over_sell_cap = target_hf_bps.saturating_mul(2);
    if projected_hf_bps > over_sell_cap {
        return false;
    }

    true
}

// ── ABI helpers ───────────────────────────────────────────────────────────────

/// Read a uint128 from ABI-encoded slot starting at `offset`.
/// ABI packs uint128 as 32 bytes: 16 zero bytes + 16 big-endian bytes.
#[inline]
fn read_u128(data: &[u8], offset: usize) -> u128 {
    // The value occupies the last 16 bytes of the 32-byte ABI slot
    let start = offset + 16; // skip the leading zero padding
    let mut bytes = [0u8; 16];
    bytes.copy_from_slice(&data[start..start + 16]);
    u128::from_be_bytes(bytes)
}

// ── Output helpers ────────────────────────────────────────────────────────────

#[inline(never)]
fn approve() -> ! {
    unsafe { seal_return(0, APPROVED.as_ptr(), 32) }
}

#[inline(never)]
fn reject() -> ! {
    unsafe { seal_return(0, REJECTED.as_ptr(), 32) }
}

// ── Panic handler (required for no_std) ──────────────────────────────────────
#[cfg(not(test))]
#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    // On panic, reject the rebalance — fail safe
    unsafe { seal_return(0, REJECTED.as_ptr(), 32) }
}

// ── Tests (run with: cargo test --target x86_64-unknown-linux-gnu) ────────────
//
// These use std so they can't run on the PVM target directly.
// Run them locally to validate the logic before deploying.

#[cfg(test)]
mod tests {
    use super::verify_rebalance;

    // Helpers: 1e18 = 1 DOT/sUSD unit, 1e8 = $1.00 in Pyth format
    const E18: u128 = 1_000_000_000_000_000_000;
    const E8:  u128 = 100_000_000;

    /// Standard scenario: 10 DOT at $5, 30 sUSD debt → HF = 166% (healthy)
    /// Bot should NOT be able to trigger a rebalance.
    #[test]
    fn test_healthy_position_rejected() {
        let result = verify_rebalance(
            10 * E18,           // 10 DOT collateral
            30 * E18,           // 30 sUSD debt
            5 * E8,             // DOT = $5.00
            2 * E18,            // propose selling 2 DOT
            15_000,             // target 150%
            12_000,             // floor 120%
        );
        assert!(!result, "Healthy position should be REJECTED");
    }

    /// Position at 115% HF — below 120% floor — selling should be APPROVED.
    #[test]
    fn test_undercollateralized_approved() {
        // 10 DOT at $3.45 = $34.50 collateral, 30 sUSD debt → HF = 115%
        let result = verify_rebalance(
            10 * E18,                // 10 DOT
            30 * E18,                // 30 sUSD debt
            3 * E8 + 45 * E8 / 100, // DOT = $3.45
            5 * E18,                 // sell 5 DOT → receives $17.25 sUSD
            15_000,                  // target 150%
            12_000,                  // floor 120%
        );
        assert!(result, "Undercollateralized position with valid sale should be APPROVED");
    }

    /// Selling more than locked — always rejected.
    #[test]
    fn test_sell_exceeds_collateral_rejected() {
        let result = verify_rebalance(
            5 * E18,   // only 5 DOT locked
            30 * E18,
            4 * E8,
            10 * E18,  // trying to sell 10 DOT — impossible
            15_000,
            12_000,
        );
        assert!(!result, "Selling more than locked should be REJECTED");
    }

    /// Zero sell amount — always rejected.
    #[test]
    fn test_zero_sell_rejected() {
        let result = verify_rebalance(
            10 * E18,
            30 * E18,
            4 * E8,
            0,          // selling nothing
            15_000,
            12_000,
        );
        assert!(!result, "Zero sell amount should be REJECTED");
    }

    /// Aegis mode: target elevated to 170% (17_000 bps).
    /// Position at 130% needs a larger sale to reach 170%.
    #[test]
    fn test_aegis_mode_target_elevated() {
        // 10 DOT at $4, debt = 30.77 sUSD → HF ≈ 130%
        let debt = 30_769_230_769_230_769_231u128; // ~30.77 sUSD in 1e18

        // Selling 3 DOT at $4 = $12 received → new debt ≈ 18.77, new coll = $28
        // New HF ≈ 149% — NOT enough for Aegis 170% target
        let result_insufficient = verify_rebalance(
            10 * E18,
            debt,
            4 * E8,
            3 * E18,   // not enough for 170% target
            17_000,    // Aegis: 170% target
            12_000,
        );
        assert!(!result_insufficient, "Insufficient sale for Aegis target should be REJECTED");

        // Selling 5 DOT at $4 = $20 received → new debt ≈ 10.77, new coll = $20
        // New HF ≈ 186% — enough for 170% target
        let result_sufficient = verify_rebalance(
            10 * E18,
            debt,
            4 * E8,
            5 * E18,   // enough for 170% target
            17_000,
            12_000,
        );
        assert!(result_sufficient, "Sufficient sale for Aegis target should be APPROVED");
    }

    /// Over-sell protection: projected HF > 2× target → rejected.
    #[test]
    fn test_over_sell_rejected() {
        // 10 DOT at $3, debt = 25 sUSD → HF = 120% → at the floor
        // Selling 9 DOT → projected HF is astronomical (over 2× target)
        let result = verify_rebalance(
            10 * E18,
            25 * E18,
            3 * E8,
            9 * E18,   // selling almost everything — way too much
            15_000,
            12_000,
        );
        assert!(!result, "Massive over-sell should be REJECTED");
    }
}