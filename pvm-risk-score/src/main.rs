#![no_std]
#![no_main]

use polkavm_derive::polkavm_export;

// We use 18 decimals for precision in calculations.
const PRECISION: u128 = 1_000_000_000_000_000_000;

#[polkavm_export]
extern "C" fn calculate_health_factor(collateral_dot: u128, minted_susd: u128, dot_price: u128) -> u128 {
    if minted_susd == 0 {
        return u128::MAX;
    }

    // collateral_usd = (collateral_dot * dot_price) / 1e8
    // Note: dot_price is 8 decimals, collateral_dot is 18 decimals.
    // Result should be 18 decimals.
    let collateral_usd = (collateral_dot * dot_price) / 100_000_000;

    // health_factor = (collateral_usd * 1e18) / minted_susd
    (collateral_usd * PRECISION) / minted_susd
}

#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    loop {}
}
