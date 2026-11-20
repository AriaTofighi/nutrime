import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";
import { cn } from "@/lib/cn";

const badgeVariants = cva("inline-flex w-fit shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[0.6875rem] font-medium whitespace-nowrap [&>svg]:size-3", {
  variants: {
    variant: {
      default: "bg-brand-soft text-primary",
      secondary: "bg-secondary text-muted-foreground",
      outline: "border border-border-strong text-muted-foreground",
      warning: "bg-warning/15 text-warning",
    },
  },
  defaultVariants: { variant: "default" },
});

function Badge({ className, variant, ...props }: React.ComponentProps<"span"> & VariantProps<typeof badgeVariants>) {
  return <span data-slot="badge" className={cn(badgeVariants({ variant }), className)} {...props} />;
}

export { Badge, badgeVariants };
