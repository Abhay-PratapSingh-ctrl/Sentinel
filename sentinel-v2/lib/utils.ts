import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";
export function cn(...inputs: ClassValue[]) { return twMerge(clsx(inputs)); }
export const shortAddr = (a: string) => a.slice(0,6)+"…"+a.slice(-4);
export const fmtE = (v: bigint, p = 2) => parseFloat((Number(v) / 1e18).toFixed(p));
export const fmtU = (v: bigint, p = 2) => parseFloat((Number(v) / 1e6).toFixed(p));
export function hfColor(hf: number) {
  if (hf >= 150) return "#39FF14";
  if (hf >= 130) return "#ffd700";
  return "#ff4d4d";
}
export function hfVariant(hf: number): "safe" | "warning" | "danger" | "nodebt" {
  if (hf === Infinity) return "nodebt";
  if (hf >= 150) return "safe";
  if (hf >= 130) return "warning";
  return "danger";
}
