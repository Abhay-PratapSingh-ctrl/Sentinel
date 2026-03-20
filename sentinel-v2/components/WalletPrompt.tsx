"use client";
import { useConnect } from "wagmi";
import { injected, metaMask } from "wagmi/connectors";
import { useState } from "react";
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription,
} from "./ui/dialog";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";

interface Props { open: boolean; onClose: () => void; }

export function WalletModal({ open, onClose }: Props) {
  const { connect, connectors, isPending } = useConnect();
  const [scanning, setScanning] = useState(false);

  // Map our UI labels to the actual available connectors
  const availableWallets = [
    { id: "metamask", label: "MetaMask", icon: "🦊", badge: "Most Popular", connectorId: "io.metamask" },
    { id: "talisman", label: "Talisman", icon: "🛡️", badge: "Polkadot Native", connectorId: "xyz.talisman" },
    { id: "injected", label: "Browser Wallet", icon: "🔑", badge: "Any EVM", connectorId: "injected" },
  ];

  function handleConnect(connectorId: string, label: string) {
    // Find the actual connector instance from wagmi
    // Match by ID first, then fallback to name matching (for better compatibility)
    const connector = connectors.find(c => c.id === connectorId) 
                   || connectors.find(c => c.name.toLowerCase().includes(label.toLowerCase()))
                   || connectors.find(c => c.id === 'injected');
    
    if (!connector) {
      console.warn("Connector not found in config:", label, connectorId);
      // If we can't find the specific one, try ANY injected as a last resort
      const injectedFallback = connectors.find(c => c.type === 'injected');
      if (injectedFallback) {
        connect({ connector: injectedFallback });
      }
      return;
    }

    setScanning(true);
    setTimeout(() => {
      connect({ connector });
      setScanning(false);
      onClose();
    }, 600);
  }

  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="max-w-sm">
        {/* Corner accents */}
        <span className="absolute top-0 left-0 w-6 h-6 border-t-2 border-l-2 border-gold rounded-tl-2xl" />
        <span className="absolute bottom-0 right-0 w-6 h-6 border-b-2 border-r-2 border-gold rounded-br-2xl" />

        <DialogHeader>
          <DialogTitle>Connect Wallet</DialogTitle>
          <DialogDescription>
            Choose your wallet to link with Sentinel on Polkadot Hub Testnet.
          </DialogDescription>
        </DialogHeader>

        {scanning ? (
          <div className="py-10 text-center">
            <div className="text-2xl mb-3 animate-pulse2">⚡</div>
            <div className="font-mono text-xs text-gold animate-pulse2 tracking-widest">
              SCANNING FOR NEURAL SIGNATURES…
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-2.5">
            {availableWallets.map((w) => (
              <button
                key={w.id}
                disabled={isPending}
                onClick={() => handleConnect(w.connectorId, w.label)}
                className="flex items-center gap-4 px-4 py-4 rounded-2xl border border-panel-border bg-white/[0.02] hover:border-gold/40 hover:bg-gold/5 transition-all group text-left"
              >
                <span className="text-3xl w-11 h-11 flex items-center justify-center rounded-xl bg-white/5 group-hover:shadow-gold-sm transition-all">
                  {w.icon}
                </span>
                <div className="flex-1">
                  <div className="font-semibold text-sm text-text-base">{w.label}</div>
                  <div className="text-[0.65rem] text-muted mt-0.5">{w.badge}</div>
                </div>
                <span className="text-gold opacity-0 group-hover:opacity-100 transition-opacity">▶</span>
              </button>
            ))}
          </div>
        )}

        <div className="mt-4 pt-4 border-t border-panel-border">
          <div className="flex items-center gap-2 justify-center flex-wrap">
            {[["🔗", "Polkadot Hub Testnet"], ["⛓️", "Chain 0x190f1b41"], ["🔋", "Pyth Oracle"]].map(([icon, label]) => (
              <span key={label} className="text-[0.6rem] text-muted font-mono px-2 py-0.5 rounded-full border border-panel-border">
                {icon} {label}
              </span>
            ))}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/* ─── Landing hero shown before connect ─────────────────────────────────── */
export function LandingHero({ onConnect }: { onConnect: () => void }) {
  return (
    <div className="min-h-[80vh] flex flex-col items-center justify-center text-center px-4 page-enter">
      {/* Glow orb */}
      <div className="relative mb-12">
        <div className="w-32 h-32 rounded-full bg-gradient-to-br from-gold/20 to-cyan/10 border-2 border-gold/40 flex items-center justify-center shadow-[0_0_60px_rgba(255,215,0,0.2)] mx-auto">
          <span className="text-6xl filter drop-shadow-[0_0_20px_rgba(255,215,0,0.8)]">⚡</span>
        </div>
        <div className="absolute -top-2 -right-2 w-4 h-4 bg-neon rounded-full shadow-neon animate-pulse2" />
      </div>

      <h1 className="font-orbitron font-black text-4xl md:text-5xl tracking-[6px] mb-3 bg-gradient-to-r from-gold via-white to-cyan bg-clip-text text-transparent">
        SENTINEL
      </h1>
      <div className="font-mono text-xs tracking-[4px] text-muted mb-6">
        AGENTIC DEFI VAULT · CYBERTRON PROTOCOL
      </div>

      <p className="text-muted-bright max-w-md mb-10 leading-relaxed text-sm">
        Multi-collateral CDP vault on Polkadot Hub. Deposit DOT + USDT,
        mint sUSD, and let the AI Guardian protect your position 24/7.
      </p>

      <Button size="lg" onClick={onConnect} className="text-sm px-12 py-4 h-auto">
        INITIALIZE NEURAL LINK
      </Button>

      {/* Feature pills */}
      <div className="mt-12 flex flex-wrap gap-3 justify-center max-w-lg">
        {[
          ["⚡", "Wagmi v2 + Viem"],
          ["🛡️", "Aegis Dynamic Buffer"],
          ["🤖", "AI Risk Reports"],
          ["⚙️", "PVM Rust Verifier"],
          ["🌀", "Spiral Shield Sim"],
          ["⛓️", "Asset Hub Native"],
        ].map(([icon, label]) => (
          <span key={label} className="flex items-center gap-1.5 text-xs text-muted-bright px-3 py-1.5 rounded-full border border-panel-border bg-white/[0.02] font-mono">
            {icon} {label}
          </span>
        ))}
      </div>
    </div>
  );
}
