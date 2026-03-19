import { create } from "zustand";

export interface LogEntry { id: number; ts: string; tag: string; msg: string; }

export interface Position {
  collDOT: number; collUSDT: number; minted: number;
  collUSD: number; hf: number; aegisActive: boolean;
  currentCR: number; walBal: number; walUSDTBal: number;
  susdBal: number; usdtAvailable: boolean;
}

interface Store {
  dotPrice: number;
  setDotPrice: (p: number) => void;
  priceHistory: number[];
  addPrice: (p: number) => void;
  position: Position | null;
  setPosition: (p: Position) => void;
  log: LogEntry[];
  addLog: (tag: string, msg: string) => void;
  aegisActiveCR: number;
  setAegisActiveCR: (cr: number) => void;
}

let _id = 3;

export const useStore = create<Store>((set) => ({
  dotPrice: 5.0,
  setDotPrice: (p) => set({ dotPrice: p }),
  priceHistory: [],
addPrice: (p) => set((s) => {
  const prev = s.priceHistory;
  if (prev.length > 0 && prev[prev.length - 1] === p) return {};
  const h = [...prev, p];
  if (h.length > 25) h.shift();
  return { priceHistory: h };
}),
  position: null,
  setPosition: (p) => set({ position: p }),
  log: [
    { id:0, ts:"BOOT", tag:"CORE",   msg:"Neural processors online. Spiral Shield protocol loading…" },
    { id:1, ts:"BOOT", tag:"SHIELD", msg:"Multi-collateral defense matrix initialised. Strategy: STABLE-FIRST." },
    { id:2, ts:"BOOT", tag:"ARMOR",  msg:"USDT buffer layer armed. DOT sell pressure suppression: ACTIVE." },
  ],
  addLog: (tag, msg) => set((s) => {
    const e = { id: ++_id, ts: new Date().toLocaleTimeString(), tag, msg };
    const log = [...s.log, e];
    if (log.length > 50) log.shift();
    return { log };
  }),
  aegisActiveCR: 150,
  setAegisActiveCR: (cr) => set({ aegisActiveCR: cr }),
}));
