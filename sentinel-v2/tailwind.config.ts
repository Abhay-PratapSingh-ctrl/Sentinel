import type { Config } from "tailwindcss";
const config: Config = {
  darkMode: ["class"],
  content: ["./pages/**/*.{ts,tsx}","./components/**/*.{ts,tsx}","./app/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        bg: "#07090f",
        panel: "#0d1117",
        "panel-border": "rgba(255,215,0,0.12)",
        "panel-border-bright": "rgba(255,215,0,0.35)",
        gold: "#ffd700",
        "gold-dim": "#b8860b",
        cyan: "#00f2ff",
        "cyan-dim": "rgba(0,242,255,0.15)",
        neon: "#39FF14",
        red: "#ff4d4d",
        muted: "#4a5568",
        "muted-bright": "#718096",
        "text-base": "#e2e8f0",
      },
      fontFamily: {
        orbitron: ["Orbitron","sans-serif"],
        mono: ['"JetBrains Mono"',"monospace"],
        sans: ["Inter","sans-serif"],
      },
      boxShadow: {
        gold: "0 0 20px rgba(255,215,0,0.25)",
        "gold-sm": "0 0 10px rgba(255,215,0,0.15)",
        cyan: "0 0 20px rgba(0,242,255,0.2)",
        neon: "0 0 15px rgba(57,255,20,0.2)",
        red: "0 0 15px rgba(255,77,77,0.2)",
        "inner-gold": "inset 0 1px 0 rgba(255,215,0,0.1)",
      },
      keyframes: {
        pulse2: { "0%,100%": {opacity:"1"}, "50%": {opacity:"0.3"} },
        blink: { "0%,100%": {opacity:"1"}, "50%": {opacity:"0"} },
        scanline: { "0%": {transform:"translateY(-100%)"}, "100%": {transform:"translateY(100vh)"} },
        "fade-up": { from:{opacity:"0",transform:"translateY(8px)"}, to:{opacity:"1",transform:"translateY(0)"} },
        "tab-in": { from:{opacity:"0",transform:"translateX(6px)"}, to:{opacity:"1",transform:"translateX(0)"} },
      },
      animation: {
        pulse2: "pulse2 2s infinite",
        blink: "blink 1s infinite",
        "fade-up": "fade-up 0.35s ease",
        "tab-in": "tab-in 0.25s ease",
      },
    },
  },
  plugins: [],
};
export default config;
