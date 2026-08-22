/**
 * Local dashboard over the harness.
 *
 * Deliberately a thin reader, not a second brain: it renders what
 * `harness/runs/*` and `harness/baselines/*` already contain, plus live state read through the
 * harness's own modules. The harness owns running things and every non-trivial computation; if
 * the dash ever needs to compute a verdict, that logic belongs in the harness where it is
 * testable. `/api/catalog` is live rather than archival — it queries the attached devices and
 * (cached) GitHub — which is the point: run history says what happened, the catalog view says
 * what is on the phone now.
 *
 * Binds to localhost only — it is a personal tool on a personal device, with no auth and no
 * business being reachable from the network.
 *
 *   cd dash && bun run src/server.ts     # http://127.0.0.1:18980
 */

import { readdir, readFile, stat } from "node:fs/promises";
import { dirname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import { describeDevice, onlineSerials } from "../../harness/src/adb.ts";
import { deviceInventory } from "../../harness/src/inventory.ts";
import { latestReleases } from "../../harness/src/releases.ts";
import { isNewer } from "../../harness/src/version.ts";

const HERE = dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = join(HERE, "..", "public");
const RUNS_DIR = join(HERE, "..", "..", "harness", "runs");
const BASELINES_DIR = join(HERE, "..", "..", "harness", "baselines");

const PORT = Number(Bun.env.AAAD_DASH_PORT ?? 18980);

interface RunRecord {
  startedAt: string;
  device: string;
  model: string;
  sdk: number;
  appId: string;
  packageName: string | null;
  outcome: string;
  detail: string;
  installer: string | null;
  playAttributed: boolean;
  androidAutoVisible: string;
  screenshot: string | null;
}

interface Run {
  id: string;
  records: RunRecord[];
}

/** Newest first — a dashboard that opens on the oldest run is answering the wrong question. */
async function listRuns(): Promise<Run[]> {
  const entries = await readdir(RUNS_DIR).catch(() => [] as string[]);
  const runs: Run[] = [];
  for (const id of entries.sort().reverse()) {
    const raw = await readFile(join(RUNS_DIR, id, "results.jsonl"), "utf8").catch(() => null);
    if (!raw) continue;
    const records = raw
      .split("\n")
      .filter((line) => line.trim().length > 0)
      .flatMap((line) => {
        try {
          return [JSON.parse(line) as RunRecord];
        } catch {
          // A truncated final line means a run was interrupted; show the rest rather than
          // failing the whole page.
          return [];
        }
      });
    runs.push({ id, records });
  }
  return runs;
}

async function listBaselines(): Promise<unknown[]> {
  const entries = await readdir(BASELINES_DIR).catch(() => [] as string[]);
  const baselines = [];
  for (const file of entries.filter((name) => name.endsWith(".json"))) {
    const raw = await readFile(join(BASELINES_DIR, file), "utf8").catch(() => null);
    if (raw) baselines.push(JSON.parse(raw));
  }
  return baselines;
}

/** Live, because a device list that lies is worse than no device list. */
async function listDevices() {
  const serials = await onlineSerials().catch(() => [] as string[]);
  return Promise.all(
    serials.map((serial) =>
      describeDevice(serial).catch(() => ({
        serial,
        model: "unknown",
        androidRelease: "?",
        sdk: 0,
      })),
    ),
  );
}

/**
 * Catalog state across every attached device, plus the latest published version of each entry.
 *
 * `?refresh=1` forces a release lookup; a plain load uses the cache, so opening the page does not
 * spend the hourly GitHub rate limit. A device that cannot be read is reported with its error
 * rather than dropped, since "the phone went away" is the thing worth seeing.
 */
async function catalogView(url: URL) {
  const [latest, serials] = await Promise.all([
    latestReleases(url.searchParams.get("refresh") === "1"),
    onlineSerials().catch(() => [] as string[]),
  ]);

  const published = new Map(latest.releases.map((release) => [release.id, release.latestVersion]));

  const devices = await Promise.all(
    serials.map(async (serial) => {
      const [info, inventory] = await Promise.all([
        describeDevice(serial).catch(() => null),
        deviceInventory(serial).catch((error: Error) => ({ serial, entries: [], error: error.message })),
      ]);
      const entries = inventory.entries.map((entry) => {
        const latestVersion = published.get(entry.id) ?? null;
        return {
          ...entry,
          latestVersion,
          // null means "cannot be compared" and is rendered as such. Collapsing it to false would
          // quietly claim an app is current when nothing was actually established.
          updateAvailable: isNewer(entry.installedVersion, latestVersion),
        };
      });
      return { ...inventory, entries, model: info?.model ?? "unknown", sdk: info?.sdk ?? 0 };
    }),
  );

  return { latest, devices };
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

/**
 * Serves a screenshot out of a run directory.
 *
 * The path is rebuilt from its parts and re-checked against the runs root, so a crafted
 * `../` cannot walk out of it even though this only ever listens on loopback.
 */
async function serveScreenshot(runId: string, file: string): Promise<Response> {
  const target = normalize(join(RUNS_DIR, runId, "screenshots", file));
  if (!target.startsWith(normalize(RUNS_DIR))) return new Response("no", { status: 400 });
  const info = await stat(target).catch(() => null);
  if (!info?.isFile()) return new Response("not found", { status: 404 });
  return new Response(Bun.file(target));
}

const server = Bun.serve({
  hostname: "127.0.0.1",
  port: PORT,
  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/") return new Response(Bun.file(join(PUBLIC_DIR, "index.html")));
    if (url.pathname === "/api/devices") return json(await listDevices());
    if (url.pathname === "/api/runs") return json(await listRuns());
    if (url.pathname === "/api/baselines") return json(await listBaselines());
    if (url.pathname === "/api/catalog") return json(await catalogView(url));

    const shot = /^\/screenshots\/([^/]+)\/([^/]+)$/.exec(url.pathname);
    if (shot) return serveScreenshot(decodeURIComponent(shot[1]!), decodeURIComponent(shot[2]!));

    return new Response("not found", { status: 404 });
  },
});

console.log(`AAAD dash on http://127.0.0.1:${server.port}`);
