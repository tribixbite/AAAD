/**
 * Latest published version per catalog entry, so the dash can show update deltas.
 *
 * Cached on disk with a TTL for one reason: GitHub's unauthenticated rate limit is 60 requests
 * per hour per IP, and the catalog has seven entries. Without a cache, eight dashboard refreshes
 * in an hour would exhaust it and every panel would start reporting failures that have nothing to
 * do with the device under test.
 *
 * A stale cache is preferred to a failed lookup: if the network is down, the last known versions
 * are still the most useful thing to show, marked with when they were fetched.
 */

import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { loadCatalog, type CatalogEntry } from "./catalog.ts";

const HERE = dirname(fileURLToPath(import.meta.url));
const CACHE_PATH = join(HERE, "..", "cache", "releases.json");

/** An hour. Publishers do not ship releases faster than that, and the rate limit resets hourly. */
const TTL_MS = 60 * 60 * 1000;

export interface ReleaseInfo {
  id: string;
  /** Null when the source published nothing usable, or the lookup failed. */
  latestVersion: string | null;
  /** Why [latestVersion] is null, when it is. */
  note: string | null;
}

export interface ReleaseSnapshot {
  fetchedAt: string;
  releases: ReleaseInfo[];
}

async function readCache(): Promise<ReleaseSnapshot | null> {
  const raw = await readFile(CACHE_PATH, "utf8").catch(() => null);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as ReleaseSnapshot;
  } catch {
    return null;
  }
}

export function isFresh(snapshot: ReleaseSnapshot | null, now = Date.now()): boolean {
  if (!snapshot) return false;
  const age = now - Date.parse(snapshot.fetchedAt);
  return Number.isFinite(age) && age >= 0 && age < TTL_MS;
}

async function resolveOne(entry: CatalogEntry): Promise<ReleaseInfo> {
  const { type, repo } = entry.source;
  if (type !== "github-release" || !repo) {
    return { id: entry.id, latestVersion: null, note: `source type "${type}" is not resolvable` };
  }

  try {
    const response = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
      headers: { accept: "application/vnd.github+json" },
    });
    if (response.status === 404) {
      // Same ambiguity the app's ReleaseResolver handles: `releases/latest` skips prereleases.
      return { id: entry.id, latestVersion: null, note: "no published (non-prerelease) release" };
    }
    if (!response.ok) {
      return { id: entry.id, latestVersion: null, note: `GitHub HTTP ${response.status}` };
    }
    const body = (await response.json()) as { tag_name?: string };
    const version = (body.tag_name ?? "").replace(/^v/, "");
    return version
      ? { id: entry.id, latestVersion: version, note: null }
      : { id: entry.id, latestVersion: null, note: "release has no tag" };
  } catch (error) {
    return { id: entry.id, latestVersion: null, note: (error as Error).message };
  }
}

/**
 * @param force ignores a fresh cache. Used by an explicit refresh, never by a page load.
 */
export async function latestReleases(force = false): Promise<ReleaseSnapshot> {
  const cached = await readCache();
  if (!force && isFresh(cached)) return cached!;

  const entries = await loadCatalog();
  const releases = await Promise.all(entries.map(resolveOne));

  // Every entry failing at once means the network is down, not that seven publishers deleted
  // their releases. Keeping the previous snapshot is more honest than replacing it with nulls.
  if (cached && releases.every((r) => r.latestVersion === null)) return cached;

  const snapshot: ReleaseSnapshot = { fetchedAt: new Date().toISOString(), releases };
  await mkdir(dirname(CACHE_PATH), { recursive: true });
  await writeFile(CACHE_PATH, JSON.stringify(snapshot, null, 2));
  return snapshot;
}
