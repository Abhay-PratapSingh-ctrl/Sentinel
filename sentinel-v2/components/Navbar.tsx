"use client";
import { useAccount, useDisconnect } from "wagmi";
import { useState } from "react";
import { useStore } from "@/store";
import { shortAddr } from "@/lib/utils";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";
import { Wifi, WifiOff, ChevronDown, LogOut, RefreshCw } from "lucide-react";

interface NavbarProps { onConnect: () => void; }

export function Navbar({ onConnect }: NavbarProps) {
  const { address, isConnected } = useAccount();
  const { disconnect } = useDisconnect();
  const dotPrice = useStore((s) => s.dotPrice);
  const [open, setOpen] = useState(false);

  return (
    <nav className="sticky top-0 z-50 w-full border-b border-panel-border bg-bg/95 backdrop-blur-xl">
      {/* Top accent line */}
      <div className="h-[2px] w-full bg-gradient-to-r from-transparent via-gold to-transparent opacity-60" />

      <div className="max-w-[1200px] mx-auto flex items-center justify-between px-6 py-3">
        {/* Logo */}
        <div className="flex items-center gap-3">
          <div className="relative w-9 h-9 border-2 border-gold rounded-lg flex items-center justify-center shadow-gold-sm">
            <span className="text-lg">⚡</span>
            <div className="absolute -top-0.5 -right-0.5 w-2 h-2 bg-neon rounded-full animate-pulse2 shadow-neon" />
          </div>
          <div>
            <div className="font-orbitron font-black text-base bg-gradient-to-r from-gold to-white bg-clip-text text-transparent tracking-[3px]">
              SENTINEL
            </div>
            <div className="text-[0.55rem] text-muted font-mono tracking-[2px] -mt-0.5">
              CYBERTRON PROTOCOL v2.0
            </div>
          </div>
        </div>

        {/* Center — live price ticker */}
        <div className="hidden md:flex items-center gap-2 px-4 py-1.5 rounded-full bg-white/[0.03] border border-panel-border">
          <span className="w-1.5 h-1.5 rounded-full bg-cyan animate-pulse2" />
          <span className="text-xs text-muted-bright font-mono">DOT/USD</span>
          <span className="text-sm font-bold font-mono text-cyan">
            {dotPrice > 0 ? `$${dotPrice.toFixed(4)}` : "—"}
          </span>
          <span className="text-[0.6rem] text-muted font-mono">PYTH</span>
        </div>

        {/* Right — wallet */}
        <div className="flex items-center gap-3">
          {isConnected && address ? (
            <div className="relative">
              <button
                onClick={() => setOpen(!open)}
                className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-gold/10 border border-gold/25 hover:border-gold/50 hover:bg-gold/15 transition-all"
              >
                <Wifi className="w-3.5 h-3.5 text-neon" />
                <span className="text-xs font-mono text-gold">{shortAddr(address)}</span>
                <ChevronDown className={`w-3 h-3 text-muted transition-transform ${open ? "rotate-180" : ""}`} />
              </button>
              {open && (
                <div className="absolute right-0 top-11 w-52 bg-panel border border-panel-border rounded-2xl shadow-[0_8px_32px_rgba(0,0,0,0.6)] overflow-hidden z-50">
                  <div className="px-4 py-3 border-b border-panel-border">
                    <div className="text-[0.65rem] text-muted uppercase tracking-wider mb-1">Connected</div>
                    <div className="font-mono text-xs text-text-base truncate">{address}</div>
                  </div>
                  <button
                    onClick={() => { disconnect(); setOpen(false); }}
                    className="w-full flex items-center gap-2.5 px-4 py-3 text-xs text-red hover:bg-red/10 transition-colors font-orbitron uppercase tracking-wider"
                  >
                    <LogOut className="w-3.5 h-3.5" />
                    Disconnect
                  </button>
                </div>
              )}
            </div>
          ) : (
            <Button onClick={onConnect} size="md">
              INITIALIZE LINK
            </Button>
          )}
        </div>
      </div>
    </nav>
  );
}
