/**
 * Version comparison for the dash, mirroring the app's `utils/VersionCompare.kt`.
 *
 * Two implementations of the same rule is a cost, but the alternative is worse: the dash would
 * either badge updates by string inequality — claiming an update whenever an installed version
 * merely *differs* from the published one — or the app would have to report deltas the harness
 * could read, which makes the app responsible for the dashboard's job.
 *
 * The contract is the same on both sides, and it is deliberately narrow: **null unless both sides
 * are confidently comparable**. Callers show nothing rather than something wrong, because a
 * phantom update badge teaches people to ignore the badge.
 */

/** Leading dotted-numeric run — the only part of a release name that can be ordered. */
const NUMERIC_PREFIX = /^([0-9]+(?:\.[0-9]+)*)/;

/**
 * Strips the decorations that carry no ordering information (a `v` prefix, a `B` or `-beta`
 * suffix) and keeps the numeric prefix. Null when there is no leading number at all — `beta1.1`,
 * or an untagged release hash — since guessing at those produces false updates.
 */
export function normalize(raw: string | null | undefined): string | null {
  if (!raw || !raw.trim()) return null;
  const trimmed = raw.trim().replace(/^[vV]/, "");
  return NUMERIC_PREFIX.exec(trimmed)?.[1] ?? null;
}

/** Segment-wise numeric comparison; missing trailing segments count as zero, so 1.2 === 1.2.0. */
function compare(a: string, b: string): number {
  const aParts = a.split(".");
  const bParts = b.split(".");
  for (let i = 0; i < Math.max(aParts.length, bParts.length); i++) {
    const left = Number(aParts[i] ?? 0);
    const right = Number(bParts[i] ?? 0);
    if (left !== right) return left > right ? 1 : -1;
  }
  return 0;
}

/**
 * True when `available` is strictly newer than `installed`, false when it is not, and **null when
 * either side cannot be compared** — which callers must treat as unknown, not as "no update".
 */
export function isNewer(
  installed: string | null | undefined,
  available: string | null | undefined,
): boolean | null {
  const left = normalize(installed);
  const right = normalize(available);
  if (left === null || right === null) return null;
  return compare(right, left) > 0;
}
