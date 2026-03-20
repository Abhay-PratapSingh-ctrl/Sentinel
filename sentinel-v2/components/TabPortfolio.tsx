"use client";
import { useState, useEffect } from "react";
import { useAccount, usePublicClient, useWalletClient } from "wagmi";
import { formatEther, formatUnits, parseEther } from "viem";
import { toast } from "sonner";
import { useStore } from "@/store";
import { useVaultPosition, useAegis } from "@/hooks";
import { SUSD, USDT, ERC20_ABI, EXPLORER } from "@/lib/contracts";
import { Card, CardHeader, CardTitle, CardContent } from "./ui/card";
import { Button } from "./ui/button";
import { Input, Label, Textarea } from "./ui/input";
import { Badge } from "./ui/badge";
import { Progress } from "./ui/progress";
import { ArrowDown, User, ShieldCheck, TrendingDown, Info } from "lucide-react";

export function TabPortfolio() {
  const { address } = useAccount();
  const client = usePublicClient();
  const { data: wc } = useWalletClient();
  const { dotPrice, position, addLog } = useStore();
  const { refresh } = useVaultPosition();
  const { activeCR, maxMint } = useAegis();

  const [bals, setBals] = useState({ dot: 0, usdt: 0, susd: 0 });
  const [swapAmt, setSwapAmt] = useState("");
  const [swapLoading, setSwapLoading] = useState(false);
  const [cooldown, setCooldown] = useState<string | null>(null);
  const [name, setName] = useState(() => typeof window !== "undefined" ? localStorage.getItem("s_name") || "" : "");
  const [bio, setBio] = useState(() => typeof window !== "undefined" ? localStorage.getItem("s_bio") || "" : "");

  const save = () => {
    localStorage.setItem("s_name", name);
    localStorage.setItem("s_bio", bio);
  };

  useEffect(() => {
    if (!client || !address) return;
    (async () => {
      const dotBal  = await client.getBalance({ address });
      const susdBal = await client.readContract({ address: SUSD, abi: ERC20_ABI, functionName: "balanceOf", args: [address] }) as bigint;
      const usdtBal = await client.readContract({ address: USDT, abi: ERC20_ABI, functionName: "balanceOf", args: [address] }) as bigint;
      setBals({ dot: parseFloat(formatEther(dotBal)), usdt: parseFloat(formatUnits(usdtBal, 6)), susd: parseFloat(formatEther(susdBal)) });
      // cooldown
      try {
        const last = await client.readContract({ address: USDT, abi: ERC20_ABI, functionName: "lastFaucetTime", args: [address] }) as bigint;
        const cd   = await client.readContract({ address: USDT, abi: ERC20_ABI, functionName: "FAUCET_COOLDOWN", args: [] }) as bigint;
        const next = Number(last) + Number(cd);
        const now  = Math.floor(Date.now() / 1000);
        if (now < next && Number(last) > 0) {
          const r = next - now;
          setCooldown(`${Math.floor(r/3600)}h ${Math.floor((r%3600)/60)}m`);
        } else setCooldown(null);
      } catch { /* silent */ }
    })();
  }, [client, address, position]);

  const p = position;
 const totalDOT  = bals.dot + (p?.collDOT ?? 0);
const totalUSDT = bals.usdt + (p?.collUSDT ?? 0);
// Only count collateral value — sUSD is borrowed debt, not net worth
const totalUSD  = totalDOT * dotPrice + totalUSDT;
  const netPos   = (p?.collUSD ?? 0) - (p?.minted ?? 0);
  const hfVal    = p && p.minted > 0 ? (p.collUSD / p.minted) * 100 : 100;
  const score    = Math.min(100, Math.round((hfVal / 200) * 100));
  const scoreColor = score >= 70 ? "#39FF14" : score >= 40 ? "#ffd700" : "#ff4d4d";
  const usdtOut  = Math.min((parseFloat(swapAmt) || 0) * dotPrice, 10000);

  // Vault Safe Values
  const collDOT = p?.collDOT ?? 0;
  const collUSDT = p?.collUSDT ?? 0;
  const debt = p?.minted ?? 0;
  const rawLiqPrice = debt > 0 ? (debt * (activeCR / 100) - collUSDT) / (collDOT || 1) : 0;
  const liqPrice = Math.max(0, rawLiqPrice);
  const isUSDTProtected = debt > 0 && rawLiqPrice <= 0;
  const safeWithDOT = debt > 0 ? Math.max(0, collDOT - (debt * (activeCR / 100) - collUSDT) / dotPrice) : collDOT;
  const safeWithUSDT = debt > 0 ? Math.max(0, (collDOT * dotPrice + collUSDT) - debt * (activeCR / 100)) : collUSDT;

  async function executeSwap() {
    if (!wc || !client || !swapAmt) return;
    setSwapLoading(true);
    try {
      const amt = parseEther(swapAmt);
      const hash = await wc.writeContract({
        address: USDT, abi: ERC20_ABI, functionName: "swap",
        value: amt
      });
      toast.info(`Swapping ${swapAmt} DOT for USDT…`, { action: { label: "Blockscout ↗", onClick: () => window.open(`${EXPLORER}/tx/${hash}`) } });
      await client.waitForTransactionReceipt({ hash });
      
      const expectedUSDT = (parseFloat(swapAmt) * 1.5).toFixed(2); // Based on mock price of 1.5
      toast.success(`Swap complete! Received approx ${expectedUSDT} USDT.`);
      addLog("TRADE", `Swap ${swapAmt} DOT → ${expectedUSDT} USDT`);
      setSwapAmt(""); await refresh();
    } catch (e: any) { toast.error(e.shortMessage || e.message || "Swap failed"); }
    finally { setSwapLoading(false); }
  }

  return (
    <div className="page-enter space-y-4">

      {/* Profile */}
      <Card>
        <CardContent className="pt-6">
          <div className="flex items-start gap-5 flex-wrap">
            <div className="w-16 h-16 rounded-full border-2 border-gold bg-gradient-to-br from-gold/20 to-cyan/10 flex items-center justify-center text-2xl font-orbitron font-black flex-shrink-0 shadow-gold">
              {name.trim() ? name.trim()[0].toUpperCase() : <User className="w-6 h-6 text-gold" />}
            </div>
            <div className="flex-1 min-w-0 space-y-2">
              <div className="text-[0.6rem] text-muted uppercase tracking-widest">Vault Operator</div>
              <Input
                value={name}
                onChange={(e) => { setName(e.target.value); save(); }}
                placeholder="Enter your name…"
                className="font-orbitron font-bold text-base h-10 border-0 border-b border-gold/30 rounded-none bg-transparent px-0 focus:border-gold"
              />
              <div className="font-mono text-xs text-muted truncate">{address}</div>
              <Textarea rows={2} value={bio} onChange={(e) => { setBio(e.target.value); save(); }}
                placeholder="Tell the network about yourself… (e.g. DeFi researcher, NIT Trichy)"
                className="text-xs mt-1"
              />
            </div>
            <div className="text-center px-4 py-3 rounded-2xl border border-panel-border bg-white/[0.02]">
              <div className="text-[0.6rem] text-muted uppercase tracking-widest mb-1">Vault Score</div>
              <div className="font-orbitron text-3xl font-black" style={{ color: scoreColor }}>{score}</div>
              <div className="text-[0.6rem] text-muted">health index</div>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Portfolio cards */}
      <div className="grid grid-cols-3 gap-3">
        {[
          { icon: "💎", label: "DOT", val: `${totalDOT.toFixed(3)}`, usd: (totalDOT * dotPrice).toFixed(2), color: "text-gold", bg: "from-gold/8", sub: "Wallet + Deposited" },
         { icon: "🛡️", label: "USDT", val: `${(p?.collUSDT ?? 0).toFixed(2)}`, usd: (p?.collUSDT ?? 0).toFixed(2), color: "text-cyan", bg: "from-cyan/8", sub: "Deposited in Vault" },
          { icon: "🪙", label: "sUSD", val: `${bals.susd.toFixed(2)}`, usd: bals.susd.toFixed(2), color: "text-neon", bg: "from-neon/8", sub: "Minted Stablecoin" },
        ].map(({ icon, label, val, usd, color, bg, sub }) => (
          <Card key={label} className={`bg-gradient-to-b ${bg} to-transparent`}>
            <CardContent className="pt-5 text-center">
              <div className="text-3xl mb-2">{icon}</div>
              <div className="font-mono text-[0.65rem] text-muted mb-1">{label}</div>
              <div className={`font-mono font-bold text-xl ${color}`}>{val}</div>
              <div className="text-xs text-muted mt-1">≈ ${usd}</div>
              <div className={`mt-2 text-[0.6rem] px-2 py-0.5 rounded-full ${color} bg-current/10 border border-current/20 inline-block`}>{sub}</div>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Safe Values */}
        <Card className="glow-neon border-neon/20">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-neon text-sm">
              <ShieldCheck className="w-4 h-4" /> Vault Safety & Capacity
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex justify-between items-center text-xs">
              <span className="text-muted-bright">Max Additional Mint</span>
              <span className="font-mono font-bold text-neon">{maxMint.toFixed(2)} sUSD</span>
            </div>
            <div className="flex justify-between items-center text-xs">
              <span className="text-muted-bright">Safe to Withdraw (DOT)</span>
              <span className="font-mono font-bold text-gold">{safeWithDOT.toFixed(3)} DOT</span>
            </div>
            <div className="flex justify-between items-center text-xs">
              <span className="text-muted-bright">Safe to Withdraw (USDT)</span>
              <span className="font-mono font-bold text-cyan">${safeWithUSDT.toFixed(2)}</span>
            </div>
            <div className="pt-2 border-t border-panel-border">
              <div className="flex justify-between items-center text-[0.65rem]">
                <span className="text-muted">Target CR (Aegis Protected)</span>
                <span className="font-mono text-text-base">{activeCR}%</span>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Risk Simulation Explanation */}
        <Card className="glow-gold">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-gold text-sm">
              <TrendingDown className="w-4 h-4" /> Risk Analysis & Simulation
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="bg-white/[0.03] rounded-xl p-3 border border-panel-border">
              <div className="text-[0.65rem] text-gold uppercase font-bold mb-1 flex items-center gap-1">
                <Info className="w-3 h-3" /> How the Buffer Works
              </div>
              <p className="text-[0.68rem] text-muted-bright leading-relaxed">
                Your <span className="text-cyan font-bold">USDT Stable Buffer</span> acts as a primary shield. In a price crash, Sentinel uses USDT to repay debt trustlessly, preserving your DOT.
              </p>
            </div>
            <div className="flex justify-between items-baseline pt-1">
              <span className="text-xs text-muted-bright font-medium">Liquidation DOT Price</span>
              <div className="text-right">
                {isUSDTProtected ? (
                  <>
                    <div className="font-mono font-bold text-neon text-lg">∞ PROTECTED</div>
                    <div className="text-[0.6rem] text-muted uppercase tracking-tighter">USDT buffer covers all debt</div>
                  </>
                ) : (
                  <>
                    <div className={`font-mono font-bold text-lg ${liqPrice < dotPrice * 0.5 ? "text-red" : "text-gold"}`}>
                      ${liqPrice.toFixed(4)}
                    </div>
    <div className="text-[0.6rem] text-muted uppercase tracking-tighter">Vault breach threshold</div>
  </>
)}
              </div>
            </div>
            <div className="text-[0.65rem] text-center text-muted italic">
              "Maintain a high USDT buffer to survive 40%+ price swings."
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Totals */}
      <Card>
        <CardContent className="pt-5 flex justify-between items-center flex-wrap gap-4">
          <div>
            <div className="text-[0.6rem] text-muted uppercase tracking-widest mb-1 font-orbitron">Total Portfolio Value</div>
            <div className="font-orbitron text-3xl font-black text-gold">${totalUSD.toFixed(2)}</div>
          </div>
          <div className="text-right">
            <div className="text-[0.6rem] text-muted mb-1">Net Position (Collateral − Debt)</div>
            <div className={`font-mono font-bold text-xl ${netPos >= 0 ? "text-neon" : "text-red"}`}>
              {netPos >= 0 ? "+" : ""}${netPos.toFixed(2)}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Swap / Faucet */}
      <Card className="glow-cyan">
        <CardHeader><CardTitle className="flex items-center gap-2"><span>💱</span>Get USDT — Testnet Faucet</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <p className="text-xs text-muted-bright leading-relaxed">
            Swap DOT for USDT to use as a <span className="text-cyan font-semibold">stable collateral buffer</span> in your vault.
            Powered by the Sentinel testnet faucet — 10,000 USDT available every 24 hours.
          </p>

          {/* From */}
          <div className="bg-white/[0.03] border border-panel-border rounded-2xl p-4 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <span className="text-2xl">💎</span>
              <div>
                <div className="font-bold text-sm">DOT</div>
                <div className="text-[0.65rem] text-muted">Balance: {bals.dot.toFixed(3)}</div>
              </div>
            </div>
            <Input type="number" placeholder="0.00" value={swapAmt} onChange={(e) => setSwapAmt(e.target.value)}
              className="w-32 text-right bg-transparent border-0 text-lg font-bold font-mono p-0 focus:border-0" />
          </div>

          <div className="text-center text-gold text-2xl"><ArrowDown className="w-5 h-5 mx-auto" /></div>

          {/* To */}
          <div className="bg-white/[0.03] border border-cyan/20 rounded-2xl p-4 flex items-center justify-between">
            <div className="flex items-center gap-3">
              <span className="text-2xl">🛡️</span>
              <div>
                <div className="font-bold text-sm text-cyan">USDT</div>
                <div className="text-[0.65rem] text-muted">Balance: {bals.usdt.toFixed(2)}</div>
              </div>
            </div>
            <div className="text-right">
              <div className="font-mono font-bold text-xl text-cyan">{usdtOut > 0 ? usdtOut.toFixed(2) : "—"}</div>
              <div className="text-[0.65rem] text-muted">≈ ${usdtOut.toFixed(2)}</div>
            </div>
          </div>

          <div className="flex justify-between text-xs text-muted font-mono">
            <span>Rate: 1 DOT = {dotPrice.toFixed(2)} USDT</span>
            <span>10,000 USDT cap / 24h</span>
          </div>

          {cooldown && (
            <div className="text-xs text-red font-mono bg-red/10 border border-red/20 rounded-xl px-3 py-2">
              ⏳ Faucet cooldown active — available in {cooldown}
            </div>
          )}

          <Button
            className="w-full" size="lg"
            onClick={executeSwap}
            disabled={swapLoading || !!cooldown}
          >
            {swapLoading
              ? <span className="flex items-center gap-2"><span className="w-4 h-4 border-2 border-black/30 border-t-black rounded-full animate-spin" />Swapping…</span>
              : "💱 SWAP DOT → USDT"}
          </Button>
          <p className="text-[0.6rem] text-center text-muted">Testnet only · Faucet mint · No real DEX</p>
        </CardContent>
      </Card>
    </div>
  );
}
