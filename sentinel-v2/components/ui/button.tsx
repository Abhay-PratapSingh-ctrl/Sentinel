import * as React from "react";
import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const buttonVariants = cva(
  "inline-flex items-center justify-center whitespace-nowrap rounded-xl text-xs font-bold font-orbitron uppercase tracking-wider transition-all disabled:opacity-40 disabled:cursor-not-allowed focus-visible:outline-none",
  {
    variants: {
      variant: {
        primary:     "bg-gradient-to-r from-gold to-gold-dim text-black shadow-gold hover:shadow-[0_0_30px_rgba(255,215,0,0.5)] hover:-translate-y-0.5",
        secondary:   "bg-cyan/10 text-cyan border border-cyan/30 hover:bg-cyan/20 hover:border-cyan",
        destructive: "bg-red/10 text-red border border-red/30 hover:bg-red/20 hover:border-red",
        ghost:       "text-muted-bright hover:text-text-base hover:bg-white/5 border border-transparent hover:border-panel-border",
        outline:     "border border-panel-border-bright text-muted-bright hover:border-gold hover:text-gold bg-transparent",
      },
      size: {
        sm:   "h-8 px-3 text-[0.65rem]",
        md:   "h-10 px-5",
        lg:   "h-12 px-8 text-sm",
        icon: "h-9 w-9",
      },
    },
    defaultVariants: { variant: "primary", size: "md" },
  }
);

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button";
    return <Comp className={cn(buttonVariants({ variant, size, className }))} ref={ref} {...props} />;
  }
);
Button.displayName = "Button";
export { buttonVariants };
