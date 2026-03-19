"use client";
import { useState,useEffect} from "react";
import { useAccount, usePublicClient, useWalletClient } from "wagmi";
import { parseEther, parseUnits, maxUint256 } from "viem";
import { toast } from "sonner";
import { useStore } from "@/store";
import { useVaultPosition } from "@/hooks";
import { VAULT, SUSD, USDT, VAULT_ABI, ERC20_ABI, EXPLORER } from "@/lib/contracts";
import { Card, CardHeader, CardTitle, CardContent } from "./ui/card";
import { Button } from "./ui/button";
import { Input, Label } from "./ui/input";
import { Badge } from "./ui/badge";
import { Flame, Droplet } from "lucide-react";



function TxButton({ label, variant, loading, onClick }: {
  label: string; variant: "primary" | "destructive" | "secondary";
  loading: boolean; onClick: () => void;
}) {
  return (
    <Button variant={variant} size="md" onClick={onClick} disabled={loading} className="w-full sm:w-auto">
      {loading
        ? <span className="flex items-center gap-2"><span className="w-3.5 h-3.5 border-2 border-current/30 border-t-current rounded-full animate-spin" />{label}</span>
        : label}
    </Button>
  );
}

function MaxLink({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <span
      onClick={onClick}
      className="text-gold cursor-pointer underline font-sans font-normal normal-case tracking-normal text-xs hover:text-yellow-300 transition-colors"
    >
      {label}
    </span>
  );
}

function VaultSection({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">{icon}{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">{children}</CardContent>
    </Card>
  );
}

export function TabVault() {
  const { address } = useAccount();
  const client = usePublicClient();
  const { data: wc } = useWalletClient();
  const pos = useStore((s) => s.position);
  const dotPrice = useStore((s) => s.dotPrice);
  const { refresh } = useVaultPosition();

  const [dotDep,   setDotDep]   = useState("");
  const [dotWith,  setDotWith]  = useState("");
  const [usdtDep,  setUsdtDep]  = useState("");
  const [usdtWith, setUsdtWith] = useState("");
  const [mintAmt,  setMintAmt]  = useState("");
  const [repayAmt, setRepayAmt] = useState("");
  const [loading,  setLoading]  = useState<Record<string, boolean>>({});



  const setLoad = (k: string, v: boolean) => setLoading((l) => ({ ...l, [k]: v }));
  const txLink  = (hash: `0x${string}`) =>
    toast.success("Transaction confirmed!", {
      action: { label: "Blockscout ↗", onClick: () => window.open(`${EXPLORER}/tx/${hash}`) },
    });

  const p  = pos;
  const cr = p?.currentCR ?? 150;

  // ── Max helpers ──────────────────────────────────────────────────────────
  function getMaxDotWithdraw(): number {
    if (!p) return 0;
    if (p.minted <= 0) return p.collDOT;
    const minCollUSD = p.minted * cr / 100;
    const remainingAfterUSDT = minCollUSD - p.collUSDT;
    const minDOT = remainingAfterUSDT > 0 ? remainingAfterUSDT / dotPrice : 0;
    return Math.max(0, p.collDOT - minDOT);
  }

  function getMaxUSDTWithdraw(): number {
    if (!p) return 0;
    if (p.minted <= 0) return p.collUSDT;
    const minCollUSD  = p.minted * cr / 100;
    const dotValueUSD = p.collDOT * dotPrice;
    return Math.max(0, p.collUSDT - Math.max(0, minCollUSD - dotValueUSD));
  }

  function getMaxMint(): number {
    if (!p) return 0;
    return Math.max(0, (p.collUSD * 100 / cr) - p.minted);
  }

  function getMaxRepay(): number {
    if (!p) return 0;
    return Math.min(p.minted, p.susdBal);
  }

  // ── Transactions ─────────────────────────────────────────────────────────
  async function depositDOT() {
    if (!wc || !client) return;
    const amt = parseFloat(dotDep); if (!amt) return toast.error("Enter a valid amount");
    setLoad("dotDep", true);
    try {
      toast.info("Sending transaction…");
      const hash = await wc.writeContract({ address: VAULT, abi: VAULT_ABI, functionName: "depositCollateral", value: parseEther(String(amt)) });
      await client.waitForTransactionReceipt({ hash });
      txLink(hash); setDotDep(""); await refresh();
    } catch (e: any) { toast.error(e.shortMessage || e.message || "Failed"); }
    finally { setLoad("dotDep", false); }
  }

  async function withdrawDOT() {
    if (!wc || !client) return;
    const amt = parseFloat(dotWith); if (!amt) return toast.error("Enter a valid amount");
    setLoad("dotWith", true);
    try {
      const hash = await wc.writeContract({ address: VAULT, abi: VAULT_ABI, functionName: "withdrawCollateral", args: [parseEther(String(amt))] });
      toast.info("Waiting for confirmation…");
      await client.waitForTransactionReceipt({ hash });
      txLink(hash); setDotWith(""); await refresh();
    } catch (e: any) { toast.error(e.shortMessage || e.message || "Failed (check HF)"); }
    finally { setLoad("dotWith", false); }
  }

  async function depositUSDT() {
    if (!wc || !client || !address) return;
    if (!p?.usdtAvailable) return toast.error("USDT not available on this network");
    const amt = parseFloat(usdtDep); if (!amt) return toast.error("Enter a valid amount");
    setLoad("usdtDep", true);
    try {
      const amtWei = parseUnits(String(amt), 6);
      const allow  = await client.readContract({ address: USDT, abi: ERC20_ABI, functionName: "allowance", args: [address, VAULT] }) as bigint;
      if (allow < amtWei) {
        toast.info("Approving USDT…");
        const ah = await wc.writeContract({ address: USDT, abi: ERC20_ABI, functionName: "approve", args: [VAULT, maxUint256] });
        await client.waitForTransactionReceipt({ hash: ah });
      }
      const hash = await wc.writeContract({ address: VAULT, abi: VAULT_ABI, functionName: "depositUSDT", args: [amtWei] });
      toast.info("Depositing USDT…");
      await client.waitForTransactionReceipt({ hash });
      txLink(hash); setUsdtDep(""); await refresh();
    } catch (e: any) { toast.error(e.shortMessage || e.message || "Failed"); }
    finally { setLoad("usdtDep", false); }
  }

  async function withdrawUSDT() {
    if (!wc || !client) return;
    const amt = parseFloat(usdtWith); if (!amt) return toast.error("Enter a valid amount");
    setLoad("usdtWith", true);
    try {
      const hash = await wc.writeContract({ address: VAULT, abi: VAULT_ABI, functionName: "withdrawUSDT", args: [parseUnits(String(amt), 6)] });
      toast.info("Waiting for confirmation…");
      await client.waitForTransactionReceipt({ hash });
      txLink(hash); setUsdtWith(""); await refresh();
    } catch (e: any) { toast.error(e.shortMessage || e.message || "Failed"); }
    finally { setLoad("usdtWith", false); }
  }

  async function mintSUSD() {
    if (!wc || !client) return;
    const amt = parseFloat(mintAmt); if (!amt) return toast.error("Enter a valid amount");
    setLoad("mint", true);
    try {
      const hash = await wc.writeContract({ address: VAULT, abi: VAULT_ABI, functionName: "mintStablecoin", args: [parseEther(String(amt))] });
      toast.info("Minting sUSD…");
      await client.waitForTransactionReceipt({ hash });
      txLink(hash); setMintAmt(""); await refresh();
    } catch (e: any) { toast.error(e.shortMessage || e.message || "Failed"); }
    finally { setLoad("mint", false); }
  }

  async function repaySUSD() {
    if (!wc || !client || !address) return;
    const amt = parseFloat(repayAmt); if (!amt) return toast.error("Enter a valid amount");
    setLoad("repay", true);
    try {
      const amtWei = parseEther(String(amt));
      const allow  = await client.readContract({ address: SUSD, abi: ERC20_ABI, functionName: "allowance", args: [address, VAULT] }) as bigint;
      if (allow < amtWei) {
        toast.info("Approving sUSD…");
        const ah = await wc.writeContract({ address: SUSD, abi: ERC20_ABI, functionName: "approve", args: [VAULT, maxUint256] });
        await client.waitForTransactionReceipt({ hash: ah });
      }
      const hash = await wc.writeContract({ address: VAULT, abi: VAULT_ABI, functionName: "burnStablecoin", args: [amtWei] });
      toast.info("Repaying sUSD…");
      await client.waitForTransactionReceipt({ hash });
      txLink(hash); setRepayAmt(""); await refresh();
    } catch (e: any) { toast.error(e.shortMessage || e.message || "Failed"); }
    finally { setLoad("repay", false); }
  }


  // ── Render ───────────────────────────────────────────────────────────────
  return (
    <div className="page-enter grid grid-cols-1 md:grid-cols-2 gap-4">

      {/* DOT Collateral */}
      <VaultSection title="DOT Collateral Armor" icon={<span className="text-base">💎</span>}>
        <div className="flex gap-2 text-xs text-muted-bright font-mono mb-1">
          <span>Wallet: <b className="text-gold">{p?.walBal.toFixed(3) ?? "—"} DOT</b></span>
          <span>·</span>
          <span>Deposited: <b className="text-text-base">{p?.collDOT.toFixed(2) ?? "—"} DOT</b></span>
        </div>
        <div className="space-y-3">
          <div>
            <Label className="flex justify-between items-center">
              Deposit DOT
              <MaxLink label={`Use max (${p?.walBal.toFixed(3) ?? "—"} DOT)`} onClick={() => setDotDep(String(p?.walBal.toFixed(4) ?? ""))} />
            </Label>
            <div className="flex gap-2">
              <Input type="number" placeholder="e.g. 10" value={dotDep} onChange={(e) => setDotDep(e.target.value)} />
              <TxButton label="Deposit" variant="primary" loading={!!loading.dotDep} onClick={depositDOT} />
            </div>
          </div>
          <div>
            <Label className="flex justify-between items-center">
              Withdraw DOT
              <MaxLink label={`Max safe (${getMaxDotWithdraw().toFixed(4)} DOT)`} onClick={() => setDotWith(getMaxDotWithdraw().toFixed(4))} />
            </Label>
            <div className="flex gap-2">
              <Input type="number" placeholder="e.g. 5" value={dotWith} onChange={(e) => setDotWith(e.target.value)} />
              <TxButton label="Withdraw" variant="destructive" loading={!!loading.dotWith} onClick={withdrawDOT} />
            </div>
          </div>
        </div>
      </VaultSection>

      {/* USDT Collateral */}
      <VaultSection title="USDT Collateral Shield" icon={<span className="text-base">🛡️</span>}>
        <div className="flex items-center gap-2 mb-1">
          <Badge variant="cyan" className="text-[0.6rem]">⛓️ ERC20Mock Testnet</Badge>
          {!p?.usdtAvailable && <Badge variant="danger" className="text-[0.6rem]">Unavailable</Badge>}
        </div>
        <div className="text-xs text-muted font-mono mb-3 bg-white/[0.02] rounded-lg p-2 border border-panel-border">
          Mock USDT: <span className="text-cyan">0xAfBDeD…402c5</span> · Mainnet: real Asset Hub precompile
        </div>
        <div className="space-y-3">
          <div>
            <Label className="flex justify-between items-center">
              Deposit USDT
              <MaxLink label={`Use max (${p?.walUSDTBal.toFixed(2) ?? "—"} USDT)`} onClick={() => setUsdtDep(String(p?.walUSDTBal.toFixed(2) ?? ""))} />
            </Label>
            <div className="flex gap-2">
              <Input type="number" placeholder="e.g. 100" value={usdtDep} onChange={(e) => setUsdtDep(e.target.value)} disabled={!p?.usdtAvailable} />
              <TxButton label="Deposit" variant="primary" loading={!!loading.usdtDep} onClick={depositUSDT} />
            </div>
          </div>
          <div>
            <Label className="flex justify-between items-center">
              Withdraw USDT
              <MaxLink label={`Max safe (${getMaxUSDTWithdraw().toFixed(2)} USDT)`} onClick={() => setUsdtWith(getMaxUSDTWithdraw().toFixed(2))} />
            </Label>
            <div className="flex gap-2">
              <Input type="number" placeholder="e.g. 50" value={usdtWith} onChange={(e) => setUsdtWith(e.target.value)} disabled={!p?.usdtAvailable} />
              <TxButton label="Withdraw" variant="destructive" loading={!!loading.usdtWith} onClick={withdrawUSDT} />
            </div>
          </div>
          <div className="text-xs text-muted font-mono">
            Wallet: <b className="text-text-base">{p?.walUSDTBal.toFixed(2) ?? "—"} USDT</b>
            {" · "}
            Deposited: <b className="text-text-base">{p?.collUSDT.toFixed(2) ?? "—"} USDT</b>
          </div>
        </div>
      </VaultSection>

      {/* Mint sUSD */}
      <VaultSection title="Mint sUSD" icon={<Flame className="w-4 h-4 text-gold" />}>
        <div className="text-xs text-muted font-mono mb-2">
          Current debt: <b className="text-red">{p?.minted.toFixed(2) ?? "—"} sUSD</b>
        </div>
        <Label className="flex justify-between items-center">
          Amount to Mint
          <MaxLink label={`Max safe (${getMaxMint().toFixed(2)} sUSD)`} onClick={() => setMintAmt(getMaxMint().toFixed(2))} />
        </Label>
        <div className="flex gap-2">
          <Input type="number" placeholder="e.g. 60" value={mintAmt} onChange={(e) => setMintAmt(e.target.value)} />
          <TxButton label="Mint" variant="secondary" loading={!!loading.mint} onClick={mintSUSD} />
        </div>
        <p className="text-xs text-muted mt-1">
          Mints sUSD against your combined collateral. Respects current CR ({cr}%).
        </p>
      </VaultSection>

      {/* Repay sUSD */}
      <VaultSection title="Repay sUSD" icon={<Droplet className="w-4 h-4 text-cyan" />}>
        <div className="text-xs text-muted font-mono mb-2">
          Wallet sUSD: <b className="text-neon">{p?.susdBal.toFixed(2) ?? "—"} sUSD</b>
        </div>
        <Label className="flex justify-between items-center">
          Amount to Repay
          <MaxLink label={`Full debt (${getMaxRepay().toFixed(2)} sUSD)`} onClick={() => setRepayAmt(getMaxRepay().toFixed(2))} />
        </Label>
        <div className="flex gap-2">
          <Input type="number" placeholder="e.g. 100" value={repayAmt} onChange={(e) => setRepayAmt(e.target.value)} />
          <TxButton label="Repay" variant="secondary" loading={!!loading.repay} onClick={repaySUSD} />
        </div>
        <p className="text-xs text-muted mt-1">
          Approve + burn sUSD to reduce debt and improve health factor.
        </p>
      </VaultSection>


    </div>
  );
}