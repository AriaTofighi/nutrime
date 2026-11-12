/** NutriMe mark: a ring that's partly filled, drawn in the current accent. */
export function Logo({ size = 28 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden className="logo">
      <rect width="32" height="32" rx="9" fill="var(--accent)" />
      <circle cx="16" cy="16" r="8.5" fill="none" stroke="var(--on-accent)" strokeOpacity="0.35" strokeWidth="3.2" />
      <path d="M16 7.5a8.5 8.5 0 1 1-8.05 11.25" fill="none" stroke="var(--on-accent)" strokeWidth="3.2" strokeLinecap="round" />
    </svg>
  );
}
