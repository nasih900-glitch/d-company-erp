/**
 * Shimmering placeholder instead of a bare spinner. A spinner says "the app
 * is doing something"; a skeleton shaped like the content that's about to
 * arrive says "this is what's coming" and avoids the layout jump when it
 * lands — reads as considered rather than "still loading, please wait."
 *
 *   <Skeleton className="h-4 w-32" />
 *   <SkeletonCard lines={3} />
 */
export function Skeleton({ className = '' }: { className?: string }) {
  return (
    <div
      className={`animate-pulse rounded-lg bg-gradient-to-r from-bg-raised via-bg-border/60 to-bg-raised bg-[length:200%_100%] ${className}`}
      style={{ animation: 'shimmer 1.4s ease-in-out infinite' }}
    />
  );
}

/** Same `.card` shell every "Loading…" spinner in this app already used —
 * drop-in replacement for the repeated
 * `<div className="card ..."><Loader2 className="animate-spin" .../> Loading…</div>`
 * pattern that had accumulated across ~20 screens. */
export function SkeletonCard({ lines = 3 }: { lines?: number }) {
  return (
    <div className="card space-y-3">
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton key={i} className={`h-4 ${i === 0 ? 'w-1/3' : 'w-full'}`} />
      ))}
    </div>
  );
}

/** For a grid of cards (menu tiles, station tiles, etc.) still loading. */
export function SkeletonGrid({ count = 6 }: { count?: number }) {
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="card space-y-2">
          <Skeleton className="h-4 w-2/3" />
          <Skeleton className="h-3 w-1/3" />
        </div>
      ))}
    </div>
  );
}
