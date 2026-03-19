"use client";
import { createConfig, http, createStorage } from "wagmi";
import { injected, metaMask } from "wagmi/connectors";
import { polkadotHub } from "./chain";

export const wagmiConfig = createConfig({
  chains: [polkadotHub],
  // Persist connector so wallet auto-reconnects on refresh
  storage: createStorage({
    storage: typeof window !== "undefined" ? window.localStorage : undefined,
  }),
  connectors: [
    metaMask(),
    injected({ target: "talisman" }),
    injected(),
  ],
  transports: {
    [polkadotHub.id]: http("https://services.polkadothub-rpc.com/testnet"),
  },
  ssr: true,
});
