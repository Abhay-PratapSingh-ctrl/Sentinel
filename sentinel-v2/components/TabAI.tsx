"use client";
import { useState, useRef, useEffect } from "react";
import { useAegis } from "@/hooks";
import { useStore } from "@/store";
import { Card, CardHeader, CardTitle, CardContent } from "./ui/card";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Badge } from "./ui/badge";
import { Send, Bot, User } from "lucide-react";

interface Msg { role: "user" | "assistant"; content: string; }

const QUICK = [
  "Why is my risk level Danger?",
  "Should I add more USDT collateral?",
  "Explain my health factor",
  "Is Aegis in red flag mode?",
  "What if DOT drops 40%?",
];

export function TabAI() {
  const { dotPrice, position, priceHistory, addLog } = useStore();
  const { activeCR, vol } = useAegis();
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const chatRef = useRef<HTMLDivElement>(null);

  useEffect(() => { chatRef.current?.scrollTo({ top: chatRef.current.scrollHeight, behavior: "smooth" }); }, [msgs]);
  
  // Ping backend on mount to wake up Render (Cold Start fix)
  useEffect(() => {
const botUrl = "https://sentineljavaagent.onrender.com";   
 fetch(`${botUrl}/actuator/health`).catch(() => {});
  }, []);

  async function ask(q?: string) {
    const question = q || input.trim();
    if (!question) return;
    setInput("");
    const newMsgs: Msg[] = [...msgs, { role: "user", content: question }];
    setMsgs(newMsgs);
    setLoading(true);

    const p = position;
    const collUSD = p?.collUSD ?? 0;
    const debt    = p?.minted  ?? 0;
    const hf      = debt > 0 ? ((collUSD / debt) * 100).toFixed(1) : "N/A";

    const sys = `You are Sentinel, an AI DeFi guardian for a multi-collateral CDP vault on Polkadot Hub.
LIVE VAULT STATE: DOT=$${dotPrice.toFixed(4)}, Collateral=$${collUSD.toFixed(2)}, Debt=${debt.toFixed(2)} sUSD, HF=${hf}%, Required CR=${activeCR}%, Vol Score=${vol.toFixed(2)}%.
Answer in 3–5 sentences. Be direct, technical, and concise. Always reference live data.`;

    const botUrl = "https://sentineljavaagent.onrender.com";    
    console.log("Sentinel AI calling:", `${botUrl}/api/ask`);
    try {
      const res = await fetch(`${botUrl}/api/ask`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          model: "claude-3-5-sonnet-20240620",
          max_tokens: 400,
          system: sys,
          messages: newMsgs.slice(-6),
        }),
      });
      const data = await res.json();
      const answer = data?.content?.[0]?.text ?? "Sentinel could not generate a response.";
      setMsgs((m) => [...m, { role: "assistant", content: answer }]);
      addLog("LLM", "Risk report generated.");
    } catch {
      setMsgs((m) => [...m, { role: "assistant", content: "⚠️ LLM analyst is currently offline. Please try again later." }]);
    }
    setLoading(false);
  }

  return (
    <div className="page-enter space-y-4">

      <Card className="glow-cyan">
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle className="flex items-center gap-2 text-cyan">
              <Bot className="w-4 h-4" /> AI Risk Analyst
            </CardTitle>
            <Badge variant="cyan" className="text-[0.65rem]">⚡ GROQ POWERED</Badge>
          </div>
        </CardHeader>
        <CardContent>
          <p className="text-xs text-muted-bright mb-4 leading-relaxed">
            Sentinel uses live Pyth oracle data, your vault position, and Aegis volatility score to generate
            natural language risk reports in real time — same AI as the Telegram guardian bot.
          </p>

          {/* Quick prompts */}
          <div className="flex flex-wrap gap-2 mb-4">
            {QUICK.map((q) => (
              <button key={q} onClick={() => ask(q)} disabled={loading}
                className="px-3 py-1.5 rounded-full border border-cyan/20 bg-cyan/5 text-cyan text-xs hover:bg-cyan/12 hover:border-cyan transition-all disabled:opacity-40 font-mono">
                {q}
              </button>
            ))}
          </div>

          {/* Chat area */}
          <div ref={chatRef}
            className="bg-[#05070a] border border-panel-border rounded-2xl p-4 h-72 overflow-y-auto space-y-4 mb-4">
            {msgs.length === 0 ? (
              <div className="h-full flex items-center justify-center text-muted text-sm italic">
                Ask Sentinel anything about your vault position ↓
              </div>
            ) : msgs.map((m, i) => (
              <div key={i} className={`flex gap-3 ${m.role === "user" ? "flex-row-reverse" : ""}`}>
                <div className={`w-7 h-7 rounded-full flex items-center justify-center flex-shrink-0 ${
                  m.role === "user" ? "bg-gold/20 border border-gold/40" : "bg-cyan/10 border border-cyan/30"
                }`}>
                  {m.role === "user"
                    ? <User className="w-3.5 h-3.5 text-gold" />
                    : <Bot className="w-3.5 h-3.5 text-cyan" />}
                </div>
                <div className={`max-w-[80%] rounded-2xl px-4 py-3 text-sm leading-relaxed ${
                  m.role === "user"
                    ? "bg-gold/10 border border-gold/20 text-text-base rounded-tr-sm"
                    : "bg-cyan/5 border border-cyan/15 text-text-base rounded-tl-sm"
                }`}>
                  {m.role === "assistant" && (
                    <div className="font-orbitron text-[0.6rem] text-cyan mb-1.5 uppercase tracking-wider">🤖 Sentinel</div>
                  )}
                  <span className="whitespace-pre-wrap">{m.content}</span>
                </div>
              </div>
            ))}
            {loading && (
              <div className="flex gap-3">
                <div className="w-7 h-7 rounded-full bg-cyan/10 border border-cyan/30 flex items-center justify-center">
                  <Bot className="w-3.5 h-3.5 text-cyan" />
                </div>
                <div className="bg-cyan/5 border border-cyan/15 rounded-2xl rounded-tl-sm px-4 py-3">
                  <div className="flex gap-1 items-center">
                    {[0, 1, 2].map((i) => (
                      <div key={i} className="w-1.5 h-1.5 bg-cyan rounded-full animate-pulse2"
                        style={{ animationDelay: `${i * 0.2}s` }} />
                    ))}
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* Input */}
          <div className="flex gap-2">
            <Input
              value={input} onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && !loading && ask()}
              placeholder="Ask about your health factor, Aegis status, what-if scenarios…"
            />
            <Button variant="secondary" size="icon" onClick={() => ask()} disabled={loading || !input.trim()}>
              <Send className="w-4 h-4" />
            </Button>
          </div>
          <div className="text-[0.6rem] text-muted mt-2 font-mono">
            Context: live Pyth · Aegis vol score · vault position · Sentinel Agent
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
