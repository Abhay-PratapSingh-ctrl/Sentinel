"use client";
import { useState, useEffect } from "react";
import { useAccount } from "wagmi";
import { Navbar } from "./Navbar";
import { WalletModal, LandingHero } from "./WalletPrompt";
import { TabOverview }  from "./TabOverview";
import { TabVault }     from "./TabVault";
import { TabPortfolio } from "./TabPortfolio";
import { TabSpiral }    from "./TabSpiral";
import { TabAegis }     from "./TabAegis";
import { TabAI }        from "./TabAI";
import { TabPVM }       from "./TabPVM";
import { usePythPrice, useVaultPosition } from "@/hooks";
import { useStore } from "@/store";

const TABS = [
  { id: "overview",  label: "Overview",      icon: "⚡", desc: "Position summary" },
  { id: "vault",     label: "Vault",          icon: "💎", desc: "Deposit / Withdraw / Mint" },
  { id: "portfolio", label: "Portfolio",      icon: "📊", desc: "Balances & Swap" },
  { id: "spiral",    label: "Spiral Shield",  icon: "🌀", desc: "Cascade simulator" },
  { id: "aegis",     label: "Aegis",          icon: "🛡️", desc: "Volatility buffer" },
  { id: "ai",        label: "AI Analyst",     icon: "🤖", desc: "LLM risk report" },
  { id: "pvm",       label: "PVM / Log",      icon: "⚙️", desc: "Rust verifier & log" },
] as const;

type TabId = typeof TABS[number]["id"];

export function SentinelApp() {
  const { isConnected } = useAccount();
  const [activeTab, setActiveTab] = useState<TabId>("overview");
  const [walletOpen, setWalletOpen] = useState(false);
  const [mounted, setMounted] = useState(false);
  
  useEffect(() => { setMounted(true); }, []);
  const { refresh } = useVaultPosition();
  const position = useStore((s) => s.position);

  usePythPrice();

  useEffect(() => {
    if (!isConnected) return;
    refresh();
    const id = setInterval(refresh, 30_000);
    return () => clearInterval(id);
  }, [isConnected, refresh]);

  const hf = position?.hf ?? 0;
  const minted = position?.minted ?? 0;
  const getHFDot = () => {
    if (minted === 0) return { color: "#39FF14", label: "∞" };
    if (hf >= 150)    return { color: "#39FF14", label: `${hf.toFixed(1)}%` };
    if (hf >= 130)    return { color: "#ffd700", label: `${hf.toFixed(1)}%` };
    return              { color: "#ff4d4d",  label: `${hf.toFixed(1)}%` };
  };
  const hfDot = getHFDot();

  if (!mounted) return null;

  return (
    <div className="min-h-screen flex flex-col relative z-[1]">
      <Navbar onConnect={() => setWalletOpen(true)} />
      <WalletModal open={walletOpen} onClose={() => setWalletOpen(false)} />

      {!isConnected ? (
        <div className="flex-1">
          <LandingHero onConnect={() => setWalletOpen(true)} />
        </div>
      ) : (
        <div className="flex flex-1 max-w-[1200px] mx-auto w-full px-4 py-6 gap-6">

          {/* ── Sidebar ─────────────────────────────────────────── */}
          <aside className="hidden md:flex flex-col w-56 shrink-0 gap-1">

            {/* HF mini card */}
            <div className="mb-3 p-4 rounded-2xl border border-panel-border bg-panel/80">
              <div className="text-[0.6rem] text-muted uppercase tracking-widest mb-2 font-mono">Health Factor</div>
              <div className="font-orbitron font-black text-2xl" style={{ color: hfDot.color }}>
                {hfDot.label}
              </div>
              <div className="text-[0.65rem] text-muted mt-1">
                Debt: {minted.toFixed(2)} sUSD
              </div>
            </div>

            {/* Tab buttons */}
            {TABS.map((t) => (
              <button key={t.id} onClick={() => setActiveTab(t.id)}
                className={`flex items-center gap-3 px-4 py-3 rounded-xl text-left transition-all group ${
                  activeTab === t.id
                    ? "bg-gold/12 border border-gold/35 shadow-gold-sm"
                    : "border border-transparent hover:border-panel-border hover:bg-white/[0.03]"
                }`}>
                <span className="text-lg w-6 text-center">{t.icon}</span>
                <div className="min-w-0">
                  <div className={`font-orbitron font-bold text-xs uppercase tracking-wider ${
                    activeTab === t.id ? "text-gold" : "text-muted-bright group-hover:text-text-base"
                  }`}>{t.label}</div>
                  <div className="text-[0.6rem] text-muted truncate mt-0.5">{t.desc}</div>
                </div>
                {activeTab === t.id && (
                  <div className="ml-auto w-1 h-6 bg-gold rounded-full shadow-gold-sm" />
                )}
              </button>
            ))}

            {/* Network info */}
            <div className="mt-auto pt-4 border-t border-panel-border space-y-1.5">
              {[
                ["🔗", "Polkadot Hub Testnet"],
                ["⛓️", "Chain 0x190f1b41"],
                ["🔋", "Pyth Network"],
              ].map(([icon, label]) => (
                <div key={label} className="flex items-center gap-2 text-[0.6rem] text-muted font-mono">
                  <span>{icon}</span><span>{label}</span>
                </div>
              ))}
            </div>
          </aside>

          {/* ── Mobile tab bar ──────────────────────────────────── */}
          <div className="md:hidden fixed bottom-0 left-0 right-0 z-40 bg-bg/95 backdrop-blur-xl border-t border-panel-border flex overflow-x-auto">
            {TABS.map((t) => (
              <button key={t.id} onClick={() => setActiveTab(t.id)}
                className={`flex flex-col items-center gap-0.5 px-3 py-2.5 flex-1 min-w-0 transition-colors ${
                  activeTab === t.id ? "text-gold" : "text-muted"
                }`}>
                <span className="text-base">{t.icon}</span>
                <span className="text-[0.5rem] font-orbitron uppercase truncate w-full text-center">{t.label}</span>
                {activeTab === t.id && <div className="absolute bottom-0 h-0.5 w-8 bg-gold rounded-full" />}
              </button>
            ))}
          </div>

          {/* ── Main content ────────────────────────────────────── */}
          <main className="flex-1 min-w-0 pb-20 md:pb-0">
            {/* Page header */}
            <div className="mb-5 pb-4 border-b border-panel-border flex items-center justify-between">
              <div>
                <h1 className="font-orbitron font-black text-xl uppercase tracking-[3px] text-text-base">
                  {TABS.find((t) => t.id === activeTab)?.label}
                </h1>
                <div className="text-xs text-muted mt-0.5">
                  {TABS.find((t) => t.id === activeTab)?.desc}
                </div>
              </div>
              <div className="hidden sm:flex items-center gap-1.5 text-[0.65rem] text-muted font-mono">
                SENTINEL <span className="text-gold">▸</span>
                <span className="text-text-base uppercase">{TABS.find((t) => t.id === activeTab)?.label}</span>
              </div>
            </div>

            {activeTab === "overview"  && <TabOverview />}
            {activeTab === "vault"     && <TabVault />}
            {activeTab === "portfolio" && <TabPortfolio />}
            {activeTab === "spiral"    && <TabSpiral />}
            {activeTab === "aegis"     && <TabAegis />}
            {activeTab === "ai"        && <TabAI />}
            {activeTab === "pvm"       && <TabPVM />}
          </main>
        </div>
      )}
    </div>
  );
}
