import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold border transition-colors font-mono",
  {
    variants: {
      variant: {
        safe:    "bg-neon/15 text-neon border-neon/30",
        warning: "bg-gold/15 text-gold border-gold/30",
        danger:  "bg-red/15 text-red border-red/30",
        nodebt:  "bg-muted/15 text-muted-bright border-muted/30",
        cyan:    "bg-cyan/10 text-cyan border-cyan/30",
        outline: "bg-transparent text-muted-bright border-panel-border",
      },
    },
    defaultVariants: { variant: "outline" },
  }
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

export function Badge({ className, variant, ...props }: BadgeProps) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />;
}
