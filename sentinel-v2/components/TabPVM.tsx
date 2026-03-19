"use client";
import { useState } from "react";
import { useWalletClient, usePublicClient } from "wagmi";
import { toast } from "sonner";
import { useStore } from "@/store";
import { useAegis } from "@/hooks";
import { ORACLE, ORACLE_ABI, EXPLORER } from "@/lib/contracts";
import { Card, CardHeader, CardTitle, CardContent } from "./ui/card";
import { Button } from "./ui/button";
import { Input, Label } from "./ui/input";
import { Badge } from "./ui/badge";
import { useRef, useEffect } from "react";

interface PVMOut { action: string; color: string; detail: string; }

export function TabPVM() {
  const { dotPrice, position, addLog, log } = useStore();
  const { activeCR } = useAegis();
  const { data: wc } = useWalletClient();
  const client = usePublicClient();

  const [pvmDot, setPvmDot] = useState("");
  const [pvmCR,  setPvmCR]  = useState("");
  const [result, setResult] = useState<PVMOut | null>(null);
  const [loading, setLoading] = useState(false);
  const logRef = useRef<HTMLDivElement>(null);

  useEffect(() => { logRef.current?.scrollTo({ top: logRef.current.scrollHeight, behavior: "smooth" }); }, [log]);

  function runPVM() {
    setLoading(true);
    setTimeout(() => {
      const dotIn  = parseFloat(pvmDot) || dotPrice;
      const crIn   = parseFloat(pvmCR)  || 0;
      const p      = position;
      const collUSD = ((p?.collDOT ?? 0) * dotIn) + (p?.collUSDT ?? 0);
      const debt   = (p?.minted ?? 0) > 0 ? (p?.minted ?? 1) : 1;
      const cr     = crIn > 0 ? crIn : (collUSD / debt) * 100;
      const target = activeCR;
      const collUSDT = p?.collUSDT ?? 0;

      let action: string, color: string, detail: string;
      if (cr >= target) {
        action = "RebalanceAction::None"; color = "rgba(57,255,20,0.08)";
        detail = `CR ${cr.toFixed(1)}% ≥ target ${target}%. No rebalance needed. Vault healthy.`;
      } else if (cr >= 120) {
        const need = Math.max(0, debt * target / 100 - collUSD);
        if (collUSDT >= need) {
          action = `RebalanceAction::UsePrimaryBuffer($${need.toFixed(2)} USDT)`; color = "rgba(0,242,255,0.08)";
          detail = `USDT buffer covers $${need.toFixed(2)} shortfall. DOT preserved. Trustless execution queued.`;
        } else {
          action = `RebalanceAction::HybridLiquidate`; color = "rgba(255,215,0,0.08)";
          detail = `USDT covers partial shortfall. DOT sale required to bridge gap.`;
        }
      } else {
        action = "RebalanceAction::EmergencyLiquidate"; color = "rgba(255,77,77,0.1)";
        detail = `CR ${cr.toFixed(1)}% below 120%. Emergency liquidation path triggered.`;
      }
      setResult({ action, color, detail });
      addLog("PVM", `Verify complete → ${action.split("(")[0]}`);
      setLoading(false);
    }, 900);
  }

  async function manualCrash(price: number) {
    if (!wc || !client) return;
    try {
      const hash = await wc.writeContract({
        address: ORACLE, abi: ORACLE_ABI, functionName: "setPrice",
        args: [BigInt(Math.round(price * 1e8))],
      });
      toast.info(`Setting oracle price to $${price}…`, { action: { label: "Blockscout ↗", onClick: () => window.open(`${EXPLORER}/tx/${hash}`) } });
      await client.waitForTransactionReceipt({ hash });
      toast.success(`Oracle price set to $${price}`);
      addLog("ORACLE", `Manual crash → $${price}`);
    } catch (e: any) { toast.error(e.shortMessage || e.message || "Failed"); }
  }

  return (
    <div className="page-enter space-y-4">

      {/* Architecture diagram */}
      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2 text-neon">⚙️ PVM Rust Sentinel Brain</CardTitle></CardHeader>
        <CardContent>
          <p className="text-sm text-muted-bright leading-relaxed mb-5">
            Currently, the Java rebalancer runs decision logic <span className="text-red font-semibold">off-chain</span> — you must trust the operator.
            By moving it into a <span className="text-neon font-semibold">PVM Precompile written in Rust</span>,
            Sentinel becomes <span className="text-neon font-semibold">trustless</span>: Polkadot itself verifies every rebalance before execution.
          </p>
          <div className="grid grid-cols-[1fr_auto_1fr] items-center gap-3">
            <div className="border border-red/30 bg-red/5 rounded-2xl p-4 text-center">
              <div className="font-orbitron text-[0.6rem] text-red uppercase tracking-widest mb-2">⚠️ Off-chain (Legacy)</div>
              <div className="font-bold text-sm mb-2">Java Bot</div>
              <div className="text-xs text-muted leading-relaxed">Reads Pyth → local decision → calls vault contract. <span className="text-red">Centralized trust.</span></div>
            </div>
            <div className="text-neon text-2xl font-bold">⟹</div>
            <div className="border border-neon/30 bg-neon/5 rounded-2xl p-4 text-center">
              <div className="font-orbitron text-[0.6rem] text-neon uppercase tracking-widest mb-2">✅ On-chain (PVM)</div>
              <div className="font-bold text-sm mb-2">Rust PVM Module</div>
              <div className="text-xs text-muted leading-relaxed">Logic compiled to PVM bytecode. Network verifies computation. <span className="text-neon">Trustless.</span></div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* PVM Verifier */}
      <Card className="glow-neon border-neon/20">
        <CardHeader><CardTitle className="text-neon">▶ PVM Decision Verifier</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>DOT Price Input ($)</Label>
              <Input type="number" placeholder={`e.g. ${dotPrice.toFixed(2)}`} value={pvmDot} onChange={(e) => setPvmDot(e.target.value)} />
            </div>
            <div>
              <Label>Collateral Ratio (%)</Label>
              <Input type="number" placeholder="e.g. 135" value={pvmCR} onChange={(e) => setPvmCR(e.target.value)} />
            </div>
          </div>

          <Button variant="ghost" className="w-full border-neon/40 text-neon hover:bg-neon/10 hover:border-neon" onClick={runPVM} disabled={loading}>
            {loading ? "⏳ RUNNING PVM BYTECODE…" : "▶ RUN PVM VERIFICATION"}
          </Button>

          {result && (
            <div className="rounded-2xl border border-neon/20 p-4 font-mono text-xs space-y-1.5" style={{ background: result.color }}>
              <div className="text-neon font-bold">✅ PVM VERIFICATION COMPLETE</div>
              <div className="text-muted">Inputs: DOT=${parseFloat(pvmDot) || dotPrice} · CR={pvmCR || "auto-computed"}%</div>
              <div className="text-gold">→ {result.action}</div>
              <div className="text-text-base mt-1">{result.detail}</div>
            </div>
          )}

          {/* Rust snippet */}
          <div>
            <div className="text-[0.65rem] text-muted uppercase tracking-wider mb-2 font-mono">Rust PVM Logic (excerpt)</div>
            <div className="bg-[#05070a] border border-neon/15 rounded-2xl p-4 font-mono text-[0.72rem] text-[#a8ff78] overflow-x-auto leading-relaxed">
              <span className="text-muted">// sentinel_brain.rs — PVM precompile</span><br />
              <span className="text-gold">pub fn </span><span className="text-cyan">verify_rebalance</span>(dot_price: u128, coll_usd: u128, debt: u128, vol: u64) {"-> RebalanceAction {"}<br />
              &nbsp;&nbsp;<span className="text-gold">let</span> cr = (coll_usd * 100) / debt;<br />
              &nbsp;&nbsp;<span className="text-gold">let</span> target = <span className="text-gold">if</span> vol {">"} RED_FLAG {"{ 170 }"} <span className="text-gold">else</span> {"{ 150 }"};<br />
              &nbsp;&nbsp;<span className="text-gold">match</span> cr {"{"}<br />
              &nbsp;&nbsp;&nbsp;&nbsp;cr <span className="text-gold">if</span> cr {">= target => RebalanceAction::None,"}<br />
              &nbsp;&nbsp;&nbsp;&nbsp;cr <span className="text-gold">if</span> cr {">= 120  => RebalanceAction::UsePrimaryBuffer(...),"}<br />
              &nbsp;&nbsp;&nbsp;&nbsp;_ {"=> RebalanceAction::EmergencyLiquidate,"}<br />
              &nbsp;&nbsp;{"}"}<br />
              {"}"}
            </div>
          </div>

          {/* Crash buttons */}
          <div>
            <div className="text-[0.65rem] text-muted uppercase tracking-wider mb-2 font-mono">Manual Oracle Price (MockPriceOracle)</div>
            <div className="flex gap-2 flex-wrap">
              {[["💥 $1.00", 1.0], ["💥 $0.60", 0.6], ["💥 $0.30", 0.3], ["✅ $1.57", 1.57]].map(([l, p]) => (
                <Button key={l as string} onClick={() => manualCrash(p as number)}
                  variant={(l as string).startsWith("✅") ? "primary" : "destructive"} size="sm">
                  {l}
                </Button>
              ))}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Guardian Log */}
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <span className="text-gold animate-blink font-mono">▶</span>
            <CardTitle className="text-gold">SENTINEL CORE NEURAL LINK — ACTIVE</CardTitle>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          <div ref={logRef}
            className="h-52 p-4 font-mono text-xs text-cyan overflow-y-auto rounded-b-2xl"
            style={{
              background: "#05070a",
              backgroundImage: "repeating-linear-gradient(0deg,transparent,transparent 2px,rgba(0,242,255,0.012) 2px,rgba(0,242,255,0.012) 4px)",
            }}>
            {log.map((e) => (
              <div key={e.id} className="flex gap-3 border-l-2 border-cyan/10 pl-2 mb-1 hover:border-cyan/30 transition-colors">
                <span className="text-cyan/40 shrink-0">[{e.ts}]</span>
                <span className="text-gold font-bold shrink-0">{e.tag}</span>
                <span className="text-cyan/80">{e.msg}</span>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
