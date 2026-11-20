import * as React from "react";
import { Dialog as DialogPrimitive } from "radix-ui";
import { X } from "lucide-react";
import { cn } from "@/lib/cn";
import { Button } from "./button";

function Dialog(props: React.ComponentProps<typeof DialogPrimitive.Root>) {
  return <DialogPrimitive.Root data-slot="dialog" {...props} />;
}

function DialogOverlay({ className, ...props }: React.ComponentProps<typeof DialogPrimitive.Overlay>) {
  return (
    <DialogPrimitive.Overlay
      data-slot="dialog-overlay"
      className={cn(
        "fixed inset-0 z-50 bg-overlay backdrop-blur-[3px] data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=closed]:animate-out data-[state=closed]:fade-out-0",
        className,
      )}
      {...props}
    />
  );
}

/**
 * A centered dialog on wide screens and a bottom sheet on phones (860px and less).
 * The content scrolls inside; the header stays in place.
 */
function DialogContent({
  className,
  children,
  wide,
  ...props
}: React.ComponentProps<typeof DialogPrimitive.Content> & { wide?: boolean }) {
  return (
    <DialogPrimitive.Portal>
      <DialogOverlay />
      <DialogPrimitive.Content
        data-slot="dialog-content"
        className={cn(
          "fixed z-50 flex flex-col border border-border bg-popover text-popover-foreground shadow-lg outline-none",
          "top-[8vh] left-1/2 max-h-[84vh] w-[calc(100%-2rem)] -translate-x-1/2 rounded-xl",
          wide ? "max-w-[640px]" : "max-w-[460px]",
          "data-[state=open]:animate-in data-[state=open]:fade-in-0 data-[state=open]:zoom-in-[0.98] data-[state=open]:slide-in-from-top-2",
          "max-[860px]:top-auto max-[860px]:bottom-0 max-[860px]:max-h-[94vh] max-[860px]:w-full max-[860px]:max-w-none max-[860px]:rounded-none max-[860px]:rounded-t-xl max-[860px]:border-x-0 max-[860px]:border-b-0",
          "max-[860px]:data-[state=open]:zoom-in-100 max-[860px]:data-[state=open]:slide-in-from-bottom-1/4",
          className,
        )}
        {...props}
      >
        {children}
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  );
}

function DialogHeader({ className, children, ...props }: React.ComponentProps<"header">) {
  return (
    <header data-slot="dialog-header" className={cn("flex items-start justify-between gap-3 pt-5 pr-4 pb-3 pl-6 max-[860px]:pl-5", className)} {...props}>
      <div className="min-w-0">{children}</div>
      <DialogPrimitive.Close asChild>
        <Button variant="ghost" size="icon" className="-mt-1" aria-label="Close">
          <X size={18} />
        </Button>
      </DialogPrimitive.Close>
    </header>
  );
}

function DialogTitle({ className, ...props }: React.ComponentProps<typeof DialogPrimitive.Title>) {
  return <DialogPrimitive.Title data-slot="dialog-title" className={cn("m-0 text-[1.0625rem] font-semibold tracking-tight", className)} {...props} />;
}

function DialogDescription({ className, ...props }: React.ComponentProps<typeof DialogPrimitive.Description>) {
  return <DialogPrimitive.Description data-slot="dialog-description" className={cn("mt-0.5 text-[0.78125rem] text-muted-foreground", className)} {...props} />;
}

function DialogBody({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="dialog-body"
      className={cn("min-h-0 flex-1 overflow-y-auto px-6 pt-1 pb-6 max-[860px]:px-5 max-[860px]:pb-[calc(1.5rem+env(safe-area-inset-bottom))]", className)}
      {...props}
    />
  );
}

export { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogBody };
