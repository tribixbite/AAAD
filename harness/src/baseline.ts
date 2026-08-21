/**
 * Regression baselines.
 *
 * A run that only records results answers "what happened"; a baseline answers the question that
 * actually matters between runs — "what changed". Publishers move releases, Android tightens
 * install rules, and Shizuku stops being authorised; all three show up as an app that worked last
 * week and does not today.
 *
 * Baselines are per device, because the same catalog genuinely behaves differently across Android
 * versions — that difference is the point of a matrix, not noise to average away.
 */

import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const BASELINE_DIR = join(HERE, "..", "baselines");

/** The part of a result that is worth holding stable. Timings and paths are not. */
export interface BaselineEntry {
  appId: string;
  outcome: string;
  playAttributed: boolean;
}

export interface Baseline {
  device: string;
  model: string;
  sdk: number;
  updated: string;
  entries: BaselineEntry[];
}

export type ChangeKind = "new" | "fixed" | "broken" | "changed";

export interface Change {
  appId: string;
  kind: ChangeKind;
  from?: BaselineEntry;
  to: BaselineEntry;
}

/** Baseline filenames key off the model, not the serial: serials rotate, models do not. */
function pathFor(model: string): string {
  const safe = model.replace(/[^A-Za-z0-9._-]/g, "_");
  return join(BASELINE_DIR, `${safe}.json`);
}

export async function load(model: string): Promise<Baseline | null> {
  return readFile(pathFor(model), "utf8")
    .then((raw) => JSON.parse(raw) as Baseline)
    .catch(() => null);
}

export async function save(baseline: Baseline): Promise<string> {
  await mkdir(BASELINE_DIR, { recursive: true });
  const target = pathFor(baseline.model);
  await writeFile(target, `${JSON.stringify(baseline, null, 2)}\n`, "utf8");
  return target;
}

/**
 * Compares a run against a baseline.
 *
 * `broken` is deliberately narrow: an app that was Play-attributed and no longer is. That is the
 * regression this project exists to catch, and keeping it distinct from a generic "changed" stops
 * it being lost among version bumps.
 */
export function diff(baseline: Baseline | null, current: BaselineEntry[]): Change[] {
  if (!baseline) return current.map((entry) => ({ appId: entry.appId, kind: "new", to: entry }));

  const previous = new Map(baseline.entries.map((entry) => [entry.appId, entry]));
  const changes: Change[] = [];

  for (const entry of current) {
    const before = previous.get(entry.appId);
    if (!before) {
      changes.push({ appId: entry.appId, kind: "new", to: entry });
      continue;
    }
    if (before.playAttributed && !entry.playAttributed) {
      changes.push({ appId: entry.appId, kind: "broken", from: before, to: entry });
      continue;
    }
    if (!before.playAttributed && entry.playAttributed) {
      changes.push({ appId: entry.appId, kind: "fixed", from: before, to: entry });
      continue;
    }
    if (before.outcome !== entry.outcome) {
      changes.push({ appId: entry.appId, kind: "changed", from: before, to: entry });
    }
  }
  return changes;
}

export function describe(change: Change): string {
  const was = change.from ? `${change.from.outcome}/${change.from.playAttributed}` : "—";
  const now = `${change.to.outcome}/${change.to.playAttributed}`;
  return `${change.kind.toUpperCase().padEnd(8)} ${change.appId.padEnd(14)} ${was} -> ${now}`;
}
