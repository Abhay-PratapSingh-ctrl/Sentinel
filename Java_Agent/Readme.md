# 🛡️ Sentinel Bot — Java AI Agent

The Sentinel bot monitors all vault positions on SentinelVault.sol
and autonomously protects users from liquidation.

---

## Architecture

```
Every 60 seconds:
  PythOracleService  → fetches live DOT/USD price
  VaultContractService.getAllUsers() → gets all wallets
  for each wallet:
    VaultContractService.getPosition(user) → reads on-chain data
    MonitoringService  → evaluates risk level
    
    HF >= 150%  → SAFE, log only
    130–150%    → sendWarningAlert() via Telegram
    125–130%    → pausePosition() on-chain + Telegram alert
    120–125%    → emergencyRebalance() on-chain + Telegram alert
    < 120%      → LIQUIDATABLE alert (open to public)
```

---

## Project Structure

```
sentinel-bot/
├── pom.xml
└── src/main/
    ├── java/com/sentinel/
    │   ├── SentinelBotApplication.java     ← Entry point
    │   ├── config/
    │   │   └── SentinelConfig.java         ← All config properties
    │   ├── model/
    │   │   └── VaultPosition.java          ← Position data model
    │   ├── oracle/
    │   │   └── PythOracleService.java      ← Fetches DOT/USD price
    │   ├── service/
    │   │   ├── VaultContractService.java   ← Web3j contract calls
    │   │   └── MonitoringService.java      ← Main 60s loop (THE BRAIN)
    │   └── bot/
    │       └── TelegramAlertService.java   ← Telegram alerts
    └── resources/
        └── application.properties          ← Configuration
```

---

## Setup

### 1. Prerequisites
- Java 17+
- Maven 3.8+
- A deployed SentinelVault on Polkadot Hub Testnet
- A Telegram bot token from @BotFather

### 2. Configure

Edit `src/main/resources/application.properties`:

```properties
sentinel.vault-address=0xYOUR_VAULT_ADDRESS
sentinel.susd-address=0xYOUR_SUSD_ADDRESS
```

Or set environment variables (safer):
```bash
export SENTINEL_PRIVATE_KEY=0x_your_guardian_wallet_private_key
export TELEGRAM_BOT_TOKEN=123456789:ABCdef...
```

### 3. Build
```bash
mvn clean package -DskipTests
```

### 4. Run
```bash
java -jar target/sentinel-bot-1.0.0.jar
```

---

## How Users Link Their Wallet

1. Find your bot on Telegram (the username set in application.properties)
2. Send: `/link 0xYOUR_WALLET_ADDRESS`
3. The bot confirms the link
4. You'll now receive alerts when your vault position is at risk

---

## Guardian Wallet Setup

The guardian wallet is a regular EVM wallet whose address is set
as the `guardian` in SentinelVault.sol. It needs:
- Small amount of DOT for gas (to send pausePosition / emergencyRebalance txns)
- Its private key set in `SENTINEL_PRIVATE_KEY` env var

Generate a new wallet:
```bash
# Using cast (Foundry tool)
cast wallet new
```
Then fund it with testnet DOT from the faucet and call:
```bash
forge script script/Deploy.s.sol ... # sets guardian=<this address>
```

---

## Demo Video Checklist

For the hackathon submission demo:
1. Show the bot running (`java -jar sentinel-bot.jar`)
2. Deposit DOT and mint sUSD to create a position
3. Simulate a price drop (or set up a test oracle with lower price)
4. Show the Telegram alert arriving in real time
5. Show the `emergencyRebalance` TX on Polkadot Hub Blockscout explorer
6. Show the updated Health Factor after the rebalance

Explorer: https://polkadot-hub-testnet.blockscout.com