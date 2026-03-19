"use client";
import { useState } from "react";
import { useStore } from "@/store";
import { hfColor } from "@/lib/utils";
import { Card, CardHeader, CardTitle, CardContent } from "./ui/card";
import { Badge } from "./ui/badge";
import { Slider } from "./ui/slider";
import { Progress } from "./ui/progress";

type Strategy = "stable-first" | "proportional" | "dot-only";

const STRATS: { id: Strategy; label: string; sub: string }[] = [
  { id: "stable-first", label: "🥇 Stable-First",  sub: "Use USDT buffer first" },
  { id: "proportional", label: "⚖️ Proportional",  sub: "Split DOT + USDT evenly" },
  { id: "dot-only",     label: "⚠️ DOT-Only",       sub: "Legacy — spiral risk!" },
];

export function TabSpiral() {
  const { dotPrice, position } = useStore();
  const [strat, setStrat] = useState<Strategy>("stable-first");
  const [simPrice, setSimPrice] = useState(dotPrice);

  const live = dotPrice;
  const min  = Math.max(0.01, live * 0.1);
  const max  = live * 2.0;

  const debt    = position?.minted ?? 0;
  const collDOT = position?.collDOT ?? 0;
  const collUSDT = position?.collUSDT ?? 0;
  const hasDebt = debt > 0;
  const CR = 150;

  // DOT-only
  const dotOnlyUSD = collDOT * simPrice;
  const hfDotOnly  = debt > 0 ? (dotOnlyUSD / debt) * 100 : Infinity;
  let dotSold = 0, dotSpiralRisk = "✅ None", dotStatus = "● SAFE";
  if (hfDotOnly < CR && hfDotOnly >= 100) {
    dotSold = Math.max(0, (debt * CR / 100 - dotOnlyUSD) / simPrice);
    dotSpiralRisk = hfDotOnly < 130 ? "🔴 HIGH" : "🟡 MEDIUM";
    dotStatus = "⚠️ REBALANCING";
  } else if (hfDotOnly < 100) {
    dotSold = collDOT; dotSpiralRisk = "🔴 CRITICAL"; dotStatus = "💀 LIQUIDATED";
  }

  // Multi-collateral
  const multiUSD  = collDOT * simPrice + collUSDT;
  const hfMulti   = debt > 0 ? (multiUSD / debt) * 100 : Infinity;
  let usdtUsed = 0, dotPreserved = collDOT, multiPress = "✅ Zero", multiStatus = "● SAFE";
  if (hfMulti < CR && hfMulti >= 100) {
    const shortfall = Math.max(0, debt * CR / 100 - multiUSD);
    if (strat === "stable-first") {
      usdtUsed = Math.min(collUSDT, shortfall + collUSDT);
      multiPress = usdtUsed > 0 ? "✅ Zero DOT sold" : "⚠️ Partial";
      multiStatus = "🛡️ USDT BUFFER";
    } else if (strat === "proportional") {
      const r = multiUSD > 0 ? collUSDT / multiUSD : 0;
      usdtUsed = shortfall * r;
      dotPreserved = collDOT - (shortfall * (1 - r)) / simPrice;
      multiPress = `~${((shortfall * (1-r)) / simPrice).toFixed(2)} DOT`;
      multiStatus = "⚖️ PROPORTIONAL";
    } else {
      dotPreserved = collDOT - shortfall / simPrice;
      multiPress = `${(shortfall / simPrice).toFixed(2)} DOT sold`;
      multiStatus = "⚠️ DOT-ONLY MODE";
    }
  } else if (hfMulti < 100) {
    usdtUsed = collUSDT; dotPreserved = 0; multiPress = "Full liquidation"; multiStatus = "💀 LIQUIDATED";
  }

  const priceDrop = Math.max(0, (live - simPrice) / live);

  return (
    <div className="page-enter space-y-4">

      {/* Explain */}
      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2">🌀 Liquidation Spiral Prevention Engine</CardTitle></CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-sm text-muted-bright leading-relaxed">
            <div className="p-4 bg-red/5 border border-red/20 rounded-2xl">
              <div className="font-bold text-red mb-2">⚠️ The Paradox</div>
              If Sentinel only holds DOT and price crashes, selling DOT to cover debt <em>adds</em> more sell pressure — making the crash worse for everyone. Classic liquidation spiral.
            </div>
            <div className="p-4 bg-neon/5 border border-neon/20 rounded-2xl">
              <div className="font-bold text-neon mb-2">✅ The Solution</div>
              With USDT as a stable buffer, Sentinel absorbs the drop without touching DOT. Zero forced sells. Zero added sell pressure. No spiral possible.
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Strategy selector */}
      <Card>
        <CardHeader><CardTitle>🤖 Rebalance Strategy</CardTitle></CardHeader>
        <CardContent>
          <div className="grid grid-cols-3 gap-2">
            {STRATS.map((s) => (
              <button key={s.id} onClick={() => setStrat(s.id)}
                className={`p-3 rounded-xl border text-left transition-all ${
                  strat === s.id
                    ? s.id === "dot-only"
                      ? "border-red bg-red/10 shadow-red"
                      : "border-gold bg-gold/10 shadow-gold-sm"
                    : "border-panel-border bg-white/[0.02] hover:border-gold/40"
                }`}>
                <div className="font-orbitron text-xs font-bold uppercase tracking-wide mb-0.5">{s.label}</div>
                <div className="text-[0.65rem] text-muted">{s.sub}</div>
              </button>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Price slider */}
      <Card>
        <CardHeader><CardTitle>📉 Price Simulation</CardTitle></CardHeader>
        <CardContent className="space-y-5">
          <div className="flex justify-between items-center">
            <span className="text-xs text-muted font-mono">${min.toFixed(2)} (crash)</span>
            <span className="font-orbitron font-bold text-lg text-cyan">${simPrice.toFixed(3)}</span>
            <span className="text-xs text-muted font-mono">${max.toFixed(2)} (rally)</span>
          </div>
          <Slider
            min={Math.floor(min * 1000)} max={Math.floor(max * 1000)}
            value={[Math.floor(simPrice * 1000)]}
            onValueChange={([v]) => setSimPrice(v / 1000)}
            step={1}
          />

          {/* Drop meter */}
          {simPrice < live && (
            <div>
              <div className="flex justify-between items-center mb-1.5">
                <span className="text-xs text-muted uppercase tracking-wider">Price Drop Severity</span>
                <span className="font-orbitron font-bold text-red text-sm">-{(priceDrop * 100).toFixed(1)}%</span>
              </div>
              <Progress
                value={Math.min(priceDrop * 200, 100)}
                indicatorColor={`hsl(${Math.floor((1 - priceDrop * 2) * 120)}, 100%, 50%)`}
              />
            </div>
          )}
        </CardContent>
      </Card>

      {/* Comparison grid */}
      {!hasDebt ? (
        <Card>
          <CardContent className="py-8 text-center text-muted-bright text-sm">
            ℹ️ Mint some sUSD debt first to activate the spiral simulation.
          </CardContent>
        </Card>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {/* DOT-Only */}
          <Card className="glow-red border-red/25">
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-red">⚠️ DOT-Only (Legacy)</CardTitle>
                <span className="font-orbitron font-bold text-xl" style={{ color: hfColor(hfDotOnly) }}>
                  {hfDotOnly === Infinity ? "∞" : `${hfDotOnly.toFixed(1)}%`}
                </span>
              </div>
            </CardHeader>
            <CardContent className="space-y-2.5">
              {[
                ["DOT sold to cover:", `${dotSold > 0 ? dotSold.toFixed(3) : 0} DOT`, "text-red"],
                ["Sell pressure:", dotSold > 0 ? `$${(dotSold * simPrice).toFixed(0)} added` : "None", "text-red"],
                ["Spiral risk:", dotSpiralRisk, "text-red font-bold"],
                ["Status:", dotStatus, ""],
              ].map(([k, v, c]) => (
                <div key={k} className="flex justify-between text-sm">
                  <span className="text-muted-bright">{k}</span>
                  <span className={`font-mono font-semibold ${c}`}>{v}</span>
                </div>
              ))}
            </CardContent>
          </Card>

          {/* Multi-Collateral */}
          <Card className="glow-neon border-neon/25">
            <CardHeader>
              <div className="flex items-center justify-between">
                <CardTitle className="text-neon">🛡️ Multi-Collateral (Sentinel)</CardTitle>
                <span className="font-orbitron font-bold text-xl" style={{ color: hfColor(hfMulti) }}>
                  {hfMulti === Infinity ? "∞" : `${hfMulti.toFixed(1)}%`}
                </span>
              </div>
            </CardHeader>
            <CardContent className="space-y-2.5">
              {[
                ["USDT buffer used:", usdtUsed > 0 ? `$${usdtUsed.toFixed(2)}` : "$0 (not needed)", "text-cyan"],
                ["DOT preserved:", `${dotPreserved.toFixed(3)} DOT`, "text-neon"],
                ["Sell pressure:", multiPress, "text-neon"],
                ["Status:", multiStatus, "text-neon"],
              ].map(([k, v, c]) => (
                <div key={k} className="flex justify-between text-sm">
                  <span className="text-muted-bright">{k}</span>
                  <span className={`font-mono font-semibold ${c}`}>{v}</span>
                </div>
              ))}
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  );
}
