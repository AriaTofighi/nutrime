import * as React from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/cn";

/** A styled native select: the phone's own picker, with shadcn styling. */
function NativeSelect({ className, ...props }: React.ComponentProps<"select">) {
  return (
    <div data-slot="native-select-wrapper" className="relative w-full min-w-0">
      <select
        data-slot="native-select"
        className={cn(
          "h-[34px] w-full min-w-0 cursor-pointer appearance-none rounded-md border border-input bg-card bg-none py-0 pr-8 pl-2.5 text-[0.8125rem] text-foreground shadow-none outline-none transition-[color,box-shadow] hover:border-[var(--gray-400)] focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/30 disabled:cursor-not-allowed disabled:opacity-50",
          className,
        )}
        {...props}
      />
      <ChevronDown aria-hidden className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-subtle-foreground" />
    </div>
  );
}

export { NativeSelect };
