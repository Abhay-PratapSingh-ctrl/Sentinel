import { defineChain } from "viem";
export const polkadotHub = defineChain({
  id: 0x190f1b41,
  name: "Polkadot Hub Testnet",
  nativeCurrency: { name: "WND", symbol: "WND", decimals: 18 },
  rpcUrls: { default: { http: ["https://services.polkadothub-rpc.com/testnet"] } },
  blockExplorers: {
    default: { name: "Blockscout", url: "https://polkadot-hub-testnet.blockscout.com" },
  },
});
