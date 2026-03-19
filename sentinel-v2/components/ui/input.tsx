import * as React from "react";
import * as LabelPrimitive from "@radix-ui/react-label";
import { cn } from "@/lib/utils";

export const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input ref={ref} className={cn(
      "w-full px-4 py-3 rounded-xl bg-white/[0.04] border border-panel-border text-text-base font-sans text-sm outline-none",
      "focus:border-gold placeholder:text-muted transition-colors",
      "[appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none",
      className
    )} {...props} />
  )
);
Input.displayName = "Input";

export const Label = React.forwardRef<
  React.ElementRef<typeof LabelPrimitive.Root>,
  React.ComponentPropsWithoutRef<typeof LabelPrimitive.Root>
>(({ className, ...props }, ref) => (
  <LabelPrimitive.Root ref={ref} className={cn(
    "text-xs font-semibold text-muted-bright mb-1.5 block font-sans",
    className
  )} {...props} />
));
Label.displayName = "Label";

export const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...props }, ref) => (
    <textarea ref={ref} className={cn(
      "w-full px-4 py-3 rounded-xl bg-white/[0.04] border border-panel-border text-text-base font-sans text-sm outline-none resize-none",
      "focus:border-gold placeholder:text-muted transition-colors",
      className
    )} {...props} />
  )
);
Textarea.displayName = "Textarea";
