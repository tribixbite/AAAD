/**
 * Reads the catalog the app ships, so the harness can map a catalog id to the package it will
 * install as.
 *
 * Needed because the app's own status only lists packages that are *already installed* — the
 * whole point of an install run is that they are not. Deriving the package name from the app's
 * pre-run state silently reported "not Play-attributed" for installs that were attributed
 * perfectly well.
 */

import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const CATALOG_PATH = join(HERE, "..", "..", "app", "src", "main", "assets", "catalog.json");

export interface CatalogEntry {
  id: string;
  name: string;
  /** Empty for user-added entries, whose package is only known after a first install. */
  packageName: string;
}

export async function loadCatalog(): Promise<CatalogEntry[]> {
  const raw = await readFile(CATALOG_PATH, "utf8");
  const parsed = JSON.parse(raw) as { apps?: CatalogEntry[] };
  return (parsed.apps ?? []).map((app) => ({
    id: app.id,
    name: app.name,
    packageName: app.packageName ?? "",
  }));
}

/** Every catalog id, for a run that wants the whole matrix. */
export async function allIds(): Promise<string[]> {
  return (await loadCatalog()).map((entry) => entry.id);
}

export async function packageNameFor(id: string): Promise<string | null> {
  const entry = (await loadCatalog()).find((app) => app.id === id);
  return entry?.packageName || null;
}
