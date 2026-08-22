/**
 * What the catalog looks like *on a given device*.
 *
 * This answers the question the run history cannot: a run says what happened during that run,
 * whereas this says what is on the phone right now — installed version, who the system thinks
 * installed it, and whether that attribution is the one Android Auto requires.
 *
 * It reads the device with a single `adb shell` call per device rather than one per package.
 * Seven packages meant seven round trips over wifi, which is slow enough to be noticeable in a
 * dashboard that refreshes.
 */

import { shell } from "./adb.ts";
import { loadCatalog, type CatalogEntry } from "./catalog.ts";

const PLAY_STORE = "com.android.vending";

export interface EntryState {
  id: string;
  name: string;
  packageName: string;
  /** Null when the app is not installed on this device. */
  installedVersion: string | null;
  /** Null when not installed, or when the system recorded no installer at all. */
  installer: string | null;
  playAttributed: boolean;
}

export interface DeviceInventory {
  serial: string;
  entries: EntryState[];
}

/**
 * Parses the `ENTRY|pkg|versionName=X|package:pkg installer=Y` lines produced by [queryDevice].
 *
 * Split out and exported for tests: the shape of `dumpsys`/`pm` output is exactly the kind of
 * thing that changes between Android versions, and it should be checkable without a phone.
 */
export function parseInventoryLine(line: string): {
  packageName: string;
  installedVersion: string | null;
  installer: string | null;
} | null {
  if (!line.startsWith("ENTRY|")) return null;
  const [, packageName = "", versionField = "", installerField = ""] = line.split("|");
  if (!packageName) return null;

  const version = /versionName=(\S+)/.exec(versionField)?.[1] ?? null;
  const rawInstaller = /installer=(\S+)/.exec(installerField)?.[1] ?? null;

  return {
    packageName,
    installedVersion: version,
    // `pm list packages -i` prints the literal string "null" for an app the system has no
    // installer record for. Left as-is it would render as an installer named "null", which is
    // precisely the state that matters here — an unattributed install.
    installer: rawInstaller === "null" ? null : rawInstaller,
  };
}

async function queryDevice(serial: string, packages: string[]): Promise<Map<string, ReturnType<typeof parseInventoryLine>>> {
  const found = new Map<string, ReturnType<typeof parseInventoryLine>>();
  if (packages.length === 0) return found;

  // `pm list packages -i <name>` matches by prefix, so the installer line is re-checked against
  // an exact `package:<name> ` before it is believed.
  const script = packages
    .map(
      (pkg) =>
        `v=$(dumpsys package "${pkg}" 2>/dev/null | grep -m1 versionName=); ` +
        `i=$(pm list packages -i "${pkg}" 2>/dev/null | grep -m1 "package:${pkg} "); ` +
        `echo "ENTRY|${pkg}|$v|$i"`,
    )
    .join("; ");

  const { stdout } = await shell(serial, script, 60_000);
  for (const line of stdout.split("\n")) {
    const parsed = parseInventoryLine(line.trim());
    if (parsed) found.set(parsed.packageName, parsed);
  }
  return found;
}

/** Catalog state on one device. Entries with no package name yet are reported as not installed. */
export async function deviceInventory(
  serial: string,
  entries?: CatalogEntry[],
): Promise<DeviceInventory> {
  const catalog = entries ?? (await loadCatalog());
  const packages = catalog.map((e) => e.packageName).filter((p) => p.length > 0);
  const found = await queryDevice(serial, packages);

  return {
    serial,
    entries: catalog.map((entry) => {
      const state = entry.packageName ? found.get(entry.packageName) : undefined;
      const installer = state?.installer ?? null;
      return {
        id: entry.id,
        name: entry.name,
        packageName: entry.packageName,
        installedVersion: state?.installedVersion ?? null,
        installer,
        playAttributed: installer === PLAY_STORE,
      };
    }),
  };
}
