"use client";
import { useAegis } from "@/hooks";
import { Card, CardHeader, CardTitle, CardContent } from "./ui/card";
import { Badge } from "./ui/badge";
import { Progress } from "./ui/progress";

export function TabAegis() {
  const { vol, isRed, isCaution, activeCR, maxMint, priceHistory, VOL_RED_FLAG, VOL_CAUTION } = {
    ...useAegis(), VOL_RED_FLAG: 7, VOL_CAUTION: 3,
  };

  const scoreColor = isRed ? "#ff4d4d" : isCaution ? "#ffd700" : "#39FF14";
  const scorePct   = Math.min((vol / 10) * 100, 100);

  const maxVol = priceHistory.length > 1
    ? Math.max(...priceHistory.slice(1).map((p, i) => Math.abs((p - priceHistory[i]) / priceHistory[i]) * 100), 0.1)
    : 1;

  return (
    <div className="page-enter space-y-4">

      {/* Status banner */}
      <Card className={isRed ? "glow-red border-red/30" : "glow-neon border-neon/25"}>
        <CardContent className="pt-5">
          <div className="flex items-center justify-between flex-wrap gap-4">
            <div>
              <div className="font-orbitron font-black text-xl uppercase tracking-wider mb-1">
                {isRed ? "🔴 RED FLAG MODE ACTIVE" : "🟢 AEGIS CALM"}
              </div>
              <div className="text-sm text-muted-bright">
                {isRed
                  ? "Volatility exceeds threshold. CR raised to 170% to protect against cascade liquidation."
                  : "Market volatility within normal bounds. Standard 150% CR applies."}
              </div>
            </div>
            <Badge variant={isRed ? "danger" : "safe"} className="text-sm px-4 py-2">
              {activeCR}% CR ACTIVE
            </Badge>
          </div>
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">

        {/* Volatility score */}
        <Card>
          <CardHeader><CardTitle>📊 Volatility Score</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <div>
              <div className="flex justify-between items-center mb-2">
                <span className="text-sm text-muted-bright">Rolling Avg Δ (25 ticks)</span>
                <span className="font-orbitron font-bold text-lg" style={{ color: scoreColor }}>
                  {vol.toFixed(2)}%
                </span>
              </div>
              <Progress value={scorePct} indicatorColor={scoreColor} className="h-3" />
              <div className="flex justify-between text-[0.65rem] text-muted font-mono mt-1.5">
                <span>Calm (0–3%)</span><span>Elevated (3–7%)</span><span>🔴 Red (7%+)</span>
              </div>
            </div>

            <div className="grid grid-cols-3 gap-2 pt-2">
              {[["Base CR", "150%", "#39FF14"], ["Active CR", `${activeCR}%`, isRed ? "#ff4d4d" : "#39FF14"], ["Max Mint", `${maxMint.toFixed(0)} sUSD`, "#00f2ff"]].map(([l, v, c]) => (
                <div key={l} className="text-center p-3 bg-white/[0.02] border border-panel-border rounded-xl">
                  <div className="font-orbitron font-black text-lg" style={{ color: c }}>{v}</div>
                  <div className="text-[0.6rem] text-muted mt-0.5">{l}</div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>

        {/* 24h pulse bars */}
        <Card>
          <CardHeader><CardTitle>📈 24h Volatility Pulse</CardTitle></CardHeader>
          <CardContent>
            {priceHistory.length < 5 ? (
  <div className="flex gap-1 items-end h-20 mb-3">
    <div className="flex-1 flex items-center justify-center text-muted text-xs font-mono">
      Collecting price ticks… ({priceHistory.length}/5)
    </div>
  </div>
) : (
  <div className="flex gap-1 items-end h-20 mb-3">
    {priceHistory.slice(1).map((p, i) => {
      const pct = Math.abs((p - priceHistory[i]) / priceHistory[i]) * 100;
      const h   = Math.max(4, (pct / (maxVol || 1)) * 72);
      const col = pct >= VOL_RED_FLAG ? "#ff4d4d" : pct >= VOL_CAUTION ? "#ffd700" : "#39FF14";
      return (
        <div key={i} className="flex-1 rounded-sm transition-all duration-300"
          style={{ height: `${h}px`, background: col, opacity: 0.4 + 0.6 * (pct / (maxVol || 1)) }} />
      );
    })}
  </div>
)}
            <div className="text-xs text-muted font-mono">Each bar = one Pyth price tick. Color: 🟢 calm / 🟡 elevated / 🔴 red flag</div>
          </CardContent>
        </Card>
      </div>

      {/* How it works */}
      <Card>
        <CardHeader><CardTitle>⚙️ How Aegis Works</CardTitle></CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 text-sm text-muted-bright leading-relaxed">
            {[
              ["1. Read History", "Pyth pushes DOT/USD price every 30s. Aegis tracks the last 25 ticks (≈12.5 minutes).", "#ffd700"],
              ["2. Compute Score", "Average absolute % change between consecutive prices = volatility score. Simple but effective.", "#00f2ff"],
              ["3. Adjust CR", "Score ≥ 7% → CR lifts to 170%. Score drops below → CR returns to 150%. Fully automatic.", "#39FF14"],
            ].map(([title, desc, color]) => (
              <div key={title} className="p-4 bg-white/[0.02] border border-panel-border rounded-2xl">
                <div className="font-orbitron font-bold text-xs uppercase mb-2" style={{ color }}>{title}</div>
                {desc}
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Alert box */}
      {isRed ? (
        <div className="p-4 bg-red/10 border border-red/30 rounded-2xl text-sm text-red leading-relaxed">
          🚨 <b>RED FLAG MARKET CONDITIONS DETECTED.</b> Aegis has raised the minimum collateral ratio to <b>170%</b>.
          Your mint capacity is reduced. To restore capacity: add more DOT/USDT collateral or reduce sUSD debt.
        </div>
      ) : (
        <div className="p-4 bg-neon/5 border border-neon/20 rounded-2xl text-sm text-neon leading-relaxed">
          ✅ <b>AEGIS CALM.</b> Volatility is within normal range. Standard 150% CR applies. Your position is protected under normal market conditions.
        </div>
      )}
    </div>
  );
}
