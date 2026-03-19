"use client";
import { useStore } from "@/store";
import { useAegis } from "@/hooks";
import { hfColor, hfVariant } from "@/lib/utils";
import { Card, CardHeader, CardTitle, CardContent } from "./ui/card";
import { Badge } from "./ui/badge";
import { Progress } from "./ui/progress";
import {
  Activity, Shield, TrendingUp, TrendingDown, Zap, AlertTriangle,
} from "lucide-react";

export function TabOverview() {
  const { dotPrice, position, priceHistory } = useStore();
  const { vol, isRed, activeCR, maxMint } = useAegis();

  const p = position;
  const hf = p?.hf ?? 0;
  const minted = p?.minted ?? 0;
  const collUSD = p?.collUSD ?? 0;
  const variant = hfVariant(hf);

  // Gauge
  const circ = 2 * Math.PI * 45;
  const fraction = minted === 0 ? 1 : Math.min(hf / 300, 1);
  const offset = circ * (1 - fraction);
  const color = minted === 0 ? "#39FF14" : hfColor(hf);

  const VARIANT_MAP: Record<string, { label: string; badge: "safe"|"warning"|"danger"|"nodebt" }> = {
    safe:    { label: "● SAFE",        badge: "safe" },
    warning: { label: "⚠ WARNING",    badge: "warning" },
    danger:  { label: "🚨 DANGER",    badge: "danger" },
    nodebt:  { label: "● NO POSITION", badge: "nodebt" },
  };
  const vm = VARIANT_MAP[variant];

  // Mini price spark
  const mini = priceHistory.slice(-12);
  const minP = Math.min(...mini);
  const maxP = Math.max(...mini);
  const sparkPts = mini.map((p, i) =>
    `${(i / (mini.length - 1)) * 180},${40 - ((p - minP) / (maxP - minP + 0.0001)) * 36}`
  ).join(" ");

  return (
    <div className="page-enter grid grid-cols-1 md:grid-cols-12 gap-4">

      {/* ── Health Factor (large) ─────────────────────────── col 1-5 */}
      <div className="md:col-span-5">
        <Card className="h-full glow-gold">
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Activity className="w-3.5 h-3.5 text-gold" /> Health Factor
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col items-center gap-4">
            {/* SVG Gauge */}
            <div className="relative w-40 h-40 mt-2">
              <svg viewBox="0 0 100 100" className="w-full h-full" style={{ transform: "rotate(-90deg)" }}>
                <circle cx="50" cy="50" r="45" fill="none" stroke="rgba(255,255,255,0.06)" strokeWidth="9" />
                <circle
                  cx="50" cy="50" r="45" fill="none"
                  stroke={color} strokeWidth="9" strokeLinecap="round"
                  strokeDasharray={circ} strokeDashoffset={offset}
                  className="hf-fill-transition"
                />
              </svg>
              <div className="absolute inset-0 flex flex-col items-center justify-center">
                <span className="font-orbitron font-black text-2xl" style={{ color }}>
                  {minted === 0 ? "∞" : hf === Infinity ? "∞" : `${hf.toFixed(1)}%`}
                </span>
                <span className="text-[0.6rem] text-muted font-mono tracking-[2px]">HEALTH FACTOR</span>
              </div>
            </div>

            <Badge variant={vm.badge} className="text-sm px-4 py-1.5">{vm.label}</Badge>

            <div className="w-full space-y-3 pt-2">
              {[
                { label: "Collateral (USD)", val: `$${collUSD.toFixed(2)}`, color: "#ffd700" },
                { label: "Minted Debt",      val: `${minted.toFixed(2)} sUSD`, color: "#ff4d4d" },
                { label: "Required CR",      val: `${activeCR}%`, color: isRed ? "#ff4d4d" : "#39FF14" },
                { label: "Max Mintable",     val: `${maxMint.toFixed(2)} sUSD`, color: "#00f2ff" },
              ].map(({ label, val, color }) => (
                <div key={label} className="flex justify-between items-center text-sm">
                  <span className="text-muted-bright">{label}</span>
                  <span className="font-mono font-bold" style={{ color }}>{val}</span>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      </div>

      {/* ── Right column ─────────────────────────────────── col 6-12 */}
      <div className="md:col-span-7 flex flex-col gap-4">

        {/* DOT Price card */}
        <Card className="glow-cyan">
          <CardHeader><CardTitle className="flex items-center gap-2"><Zap className="w-3.5 h-3.5 text-cyan" />Live Oracle — DOT/USD</CardTitle></CardHeader>
          <CardContent>
            <div className="flex items-end justify-between">
              <div>
                <div className="font-orbitron font-black text-3xl text-cyan">${dotPrice.toFixed(4)}</div>
                <div className="text-xs text-muted font-mono mt-1">via Pyth Network · 30s refresh</div>
              </div>
              {/* Sparkline */}
              <svg width="180" height="44" className="opacity-80">
                <polyline
                  points={sparkPts} fill="none"
                  stroke="#00f2ff" strokeWidth="2" strokeLinejoin="round"
                />
              </svg>
            </div>
            <div className="mt-3 grid grid-cols-3 gap-2">
              {[["DOT Deposited", `${p?.collDOT.toFixed(2) ?? "—"}`, "text-gold"],
                ["DOT Value",    `$${((p?.collDOT ?? 0) * dotPrice).toFixed(2)}`, "text-text-base"],
                ["Wallet Bal",   `${p?.walBal.toFixed(3) ?? "—"} DOT`, "text-muted-bright"]].map(([l,v,c]) => (
                <div key={l} className="bg-white/[0.03] rounded-xl p-2.5 text-center">
                  <div className="text-[0.65rem] text-muted mb-1">{l}</div>
                  <div className={`font-mono font-bold text-sm ${c}`}>{v}</div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>

        {/* Collateral breakdown */}
        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2"><Shield className="w-3.5 h-3.5 text-gold" />Collateral Armor</CardTitle></CardHeader>
          <CardContent className="space-y-3">
            {/* DOT bar */}
            <div>
              <div className="flex justify-between text-xs mb-1.5">
                <span className="text-muted-bright flex items-center gap-1.5">💎 DOT</span>
                <span className="font-mono text-gold">${((p?.collDOT ?? 0) * dotPrice).toFixed(2)}</span>
              </div>
              <Progress
                value={collUSD > 0 ? (((p?.collDOT ?? 0) * dotPrice) / collUSD) * 100 : 0}
                indicatorColor="#ffd700"
              />
            </div>
            {/* USDT bar */}
            <div>
              <div className="flex justify-between text-xs mb-1.5">
                <span className="text-muted-bright flex items-center gap-1.5">🛡️ USDT</span>
                <span className="font-mono text-cyan">${(p?.collUSDT ?? 0).toFixed(2)}</span>
              </div>
              <Progress
                value={collUSD > 0 ? ((p?.collUSDT ?? 0) / collUSD) * 100 : 0}
                indicatorColor="#00f2ff"
              />
            </div>
            <div className="flex justify-between pt-2 border-t border-panel-border text-sm">
              <span className="text-muted-bright">Total Collateral</span>
              <span className="font-mono font-bold text-text-base">${collUSD.toFixed(2)}</span>
            </div>
          </CardContent>
        </Card>

        {/* Aegis status mini */}
        <Card className={isRed ? "glow-red border-red/30" : "glow-neon border-neon/20"}>
          <CardContent className="pt-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <AlertTriangle className={`w-5 h-5 ${isRed ? "text-red" : "text-neon"}`} />
                <div>
                  <div className="font-orbitron font-bold text-sm uppercase tracking-wider">
                    {isRed ? "🔴 AEGIS RED FLAG" : "🟢 AEGIS NORMAL"}
                  </div>
                  <div className="text-xs text-muted mt-0.5">
                    {isRed
                      ? `CR raised to 170% · Vol score: ${vol.toFixed(2)}%`
                      : `Standard 150% CR · Vol score: ${vol.toFixed(2)}%`}
                  </div>
                </div>
              </div>
              <Badge variant={isRed ? "danger" : "safe"}>{activeCR}% CR</Badge>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
