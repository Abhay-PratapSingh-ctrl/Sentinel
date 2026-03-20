# 🛡️ Sentinel — Agentic DeFi Guardian

Sentinel is a next-generation **Agentic DeFi Guardian** built for the Polkadot Hub Hub ecosystem. It combines a high-performance Java agent with a premium Next.js dashboard to provide automated risk management, AI-driven insights, and one-click vault operations.

## 🚀 Live Access
- **Dashboard**: [sentinel-v2-gamma.vercel.app](https://sentinel-v2-gamma.vercel.app)
- **Bot Endpoint**: [sentineljavaagent.onrender.com](https://sentineljavaagent.onrender.com)

---

## 🏗️ Architecture

The Sentinel ecosystem consists of three main components:

### 1. **Sentinel Hub (Frontend)** — `sentinel-v2/`
- **Tech Stack**: Next.js 14, React, Tailwind CSS, Wagmi/Viensure.
- **Deployment**: Vercel.
- **Features**: 
  - Glassmorphic Pro UI with real-time health factor monitoring.
  - Multi-wallet support (MetaMask, Talisman, EIP-6963).
  - AI Risk Analyst integration for real-time strategy reports.

### 2. **Sentinel Java Agent (Bot)** — `Java_Agent/`
- **Tech Stack**: Java 17, Spring Boot, Web3j, Docker.
- **Deployment**: Render (Web Service).
- **Features**:
  - **Guardian Mode**: Automated health factor monitoring and auto-rebalancing.
  - **LLM Proxy**: High-speed AI inference via Groq/Llama-3.1 to analyze on-chain data.
  - **Telegram Alerts**: Real-time notifications for liquidations and position updates. Join [**@SentinelAegis_bot**](https://t.me/SentinelAegis_bot) to start receiving alerts.

### 3. **Smart Contracts** — `src/` (Foundry)
- **Tech Stack**: Solidity, Foundry.
- **Features**: 
  - Collateralized debt positions (CDP).
  - sUSD stablecoin minting.
  - Rebalancer logic for automated collateral management.

---

## 🛠️ Local Development

### Prerequisites
- Node.js v18+
- Java 17
- Maven
- MetaMask or Talisman wallet

### Running the Frontend
```bash
cd sentinel-v2
npm install
npm run dev
```

### Running the Bot
```bash
cd Java_Agent
# Update .env with your keys
./start_bot.sh
```

---

## 🔐 Environment Variables

### Bot (`Java_Agent/.env`)
| Variable | Description |
|----------|-------------|
| `SENTINEL_PRIVATE_KEY` | Hex private key for the Guardian wallet |
| `TELEGRAM_BOT_TOKEN` | Token from @BotFather |
| `GROQ_API_KEY` | API Key for AI Risk Analyst |
| `SENTINEL_RPC_URL` | Polkadot Hub Hub Testnet RPC |

### Frontend (`sentinel-v2/.env.local`)
| Variable | Description |
|----------|-------------|
| `NEXT_PUBLIC_BOT_URL` | URL of the running Java Agent |

---

## 🚢 Deployment Guide

### Frontend (Vercel)
- Connect repository to Vercel.
- Set **Root Directory** to `sentinel-v2`.
- Deploy.

### Bot (Render)
- New Web Service.
- Set **Root Directory** to `Java_Agent`.
- Set **Runtime** to `Docker`.
- Add environment variables (see above).
- Render will use the included `Dockerfile` for a multi-stage build.

---

## 📄 License
MIT
