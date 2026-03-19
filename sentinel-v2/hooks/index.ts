"use client";
import { useEffect, useCallback } from "react";
import { useAccount, usePublicClient } from "wagmi";
import { formatEther, formatUnits } from "viem";
import { useStore } from "@/store";
import { VAULT, SUSD, USDT, VAULT_ABI, ERC20_ABI, PYTH_URL } from "@/lib/contracts";

/* ─── Pyth Price ────────────────────────────────────────────────────────── */
export function usePythPrice() {
  const { setDotPrice, addPrice, addLog } = useStore();

  const fetch$ = useCallback(async () => {
    try {
      const r = await fetch(PYTH_URL);
      const d = await r.json();
      const p = d.parsed[0].price;
      const price = parseFloat(p.price) * Math.pow(10, p.expo);
      setDotPrice(price);
      addPrice(price);
      addLog("PYTH", `DOT/USD → $${price.toFixed(4)}`);
    } catch {
      addLog("WARN", "Pyth feed unavailable. Using cached price.");
    }
  }, [setDotPrice, addPrice, addLog]);

  useEffect(() => {
    fetch$();
    const id = setInterval(fetch$, 30_000);
    return () => clearInterval(id);
  }, [fetch$]);
}

/* ─── Vault Position ────────────────────────────────────────────────────── */
export function useVaultPosition() {
  const { address } = useAccount();
  const client = usePublicClient();
  const { dotPrice, setPosition, addLog } = useStore();

  const refresh = useCallback(async () => {
    if (!client || !address) return;
    try {
      const pos = await client.readContract({
        address: VAULT, abi: VAULT_ABI, functionName: "getPosition", args: [address],
      }) as readonly bigint[];

      const rawHF = await client.readContract({
        address: VAULT, abi: VAULT_ABI, functionName: "getHealthFactor", args: [address],
      }) as bigint;

      const walBalance = await client.getBalance({ address });
      const susdBal    = await client.readContract({ address: SUSD, abi: ERC20_ABI, functionName: "balanceOf", args: [address] }) as bigint;

      let walUSDTBal = 0, usdtAvailable = true;
      try {
        const usdtBal = await client.readContract({ address: USDT, abi: ERC20_ABI, functionName: "balanceOf", args: [address] }) as bigint;
        walUSDTBal = parseFloat(formatUnits(usdtBal, 6));
      } catch { usdtAvailable = false; }

      const MAX_HF = BigInt("0xFFFFFFFFFFFFFFFFFFFFFFFF");
      const hf = rawHF > MAX_HF ? Infinity : parseFloat(formatEther(rawHF)) * 100;

      setPosition({
        collDOT:       parseFloat(parseFloat(formatEther(pos[0])).toFixed(4)),
        collUSDT:      usdtAvailable ? parseFloat(formatUnits(pos[1], 6)) : 0,
        minted:        parseFloat(formatEther(pos[2])),
        collUSD:       parseFloat(formatEther(pos[3])),
        hf,
        aegisActive:   Boolean(pos[6]),
        currentCR:     pos[7] ? Number(pos[7]) : 150,
        walBal:        parseFloat(parseFloat(formatEther(walBalance)).toFixed(4)),
        walUSDTBal,
        susdBal:       parseFloat(formatEther(susdBal)),
        usdtAvailable,
      });
    } catch (e: unknown) {
      addLog("ERROR", e instanceof Error ? e.message : "Refresh failed");
    }
  }, [client, address, dotPrice, setPosition, addLog]);

  return { refresh };
}

/* ─── Aegis Volatility ──────────────────────────────────────────────────── */
export function useAegis() {
  const { priceHistory, position } = useStore();

  function computeVol(h: number[]): number {
    if (h.length < 5) return 0;
    const mean = h.reduce((a, b) => a + b, 0) / h.length;
    const variance = h.reduce((a, b) => a + (b - mean) ** 2, 0) / h.length;
    const stdDev = Math.sqrt(variance);
    return (stdDev / mean) * 100;
  }

  const vol      = computeVol(priceHistory);
  const isRed    = vol >= 7;
  const isCaution = vol >= 3 && !isRed;
  const activeCR  = isRed ? 170 : 150;
  const collUSD   = position?.collUSD ?? 0;
  const debt      = position?.minted  ?? 0;
  const maxMint   = collUSD > 0 ? Math.max(0, (collUSD * 100) / activeCR - debt) : 0;

  return { vol, isRed, isCaution, activeCR, maxMint, priceHistory };
}
