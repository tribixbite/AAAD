/**
 * Harness entry point.
 *
 *   bun run src/cli.ts devices
 *   bun run src/cli.ts status
 *   bun run src/cli.ts matrix [--host 192.168.1.243] [--apps id,id]
 *
 * Results are appended to `runs/<timestamp>/results.jsonl` — one JSON object per (device, app)
 * so runs diff cleanly and the dash can read them without parsing prose.
 */

import { mkdir } from "node:fs/promises";
import { describeDevice, resolveSerial, type DeviceInfo } from "./adb.ts";
import { install, installerOf, isPlayAttributed, status, APP_ID } from "./app.ts";
import { allIds, packageNameFor } from "./catalog.ts";
import { capture, launch } from "./capture.ts";
import * as baseline from "./baseline.ts";

interface Options {
  host?: string;
  apps?: string[];
  serial?: string;
  /** Write this run's outcomes over the device's baseline. */
  accept?: boolean;
}

function parseArgs(argv: string[]): { command: string; options: Options } {
  const [command = "status", ...rest] = argv;
  const options: Options = {};
  for (let i = 0; i < rest.length; i += 2) {
    const key = rest[i];
    const value = rest[i + 1];
    if (!value) continue;
    if (key === "--host") options.host = value;
    if (key === "--apps") options.apps = value.split(",").map((s) => s.trim()).filter(Boolean);
    if (key === "--serial") options.serial = value;
    if (key === "--accept") options.accept = value !== "false";
  }
  return { command, options };
}

async function requireDevice(options: Options): Promise<DeviceInfo> {
  let serial: string | null;
  try {
    serial = await resolveSerial(options.host, options.serial);
  } catch (error) {
    console.error((error as Error).message);
    process.exit(1);
  }
  if (!serial) {
    console.error(
      "No device. Connect one, pass --serial <serial>, or --host <ip> to rescan a rotated " +
        "wireless-debugging port.",
    );
    process.exit(1);
  }
  return describeDevice(serial);
}

async function cmdDevices(options: Options): Promise<void> {
  const device = await requireDevice(options);
  console.log(`${device.serial}  ${device.model}  Android ${device.androidRelease} (SDK ${device.sdk})`);
}

async function cmdStatus(options: Options): Promise<void> {
  const device = await requireDevice(options);
  const result = await status(device.serial);
  if (!result) {
    console.error(`No status from ${APP_ID}. Is the debug build installed?`);
    process.exit(1);
  }
  console.log(`${device.model} (SDK ${device.sdk})`);
  console.log(`  shizuku:     ${result.shizuku}`);
  console.log(`  catalog:     ${result.catalogApps} apps`);
  console.log(`  AA-capable:  ${result.aaCapableInstalled} installed, ${result.convertible} convertible`);
  for (const app of result.installed) {
    console.log(`    ${app.packageName}  ${app.state}  installer=${app.installer}`);
  }
}

/**
 * Installs each requested app and records whether it ended up Play-attributed.
 *
 * Android Auto visibility is deliberately reported as `unknown`: it cannot be observed without a
 * live projection session (docs/aa-visibility.md), and a harness that guessed here would be
 * asserting the one thing it cannot see.
 */
async function cmdMatrix(options: Options): Promise<void> {
  const device = await requireDevice(options);
  const current = await status(device.serial);
  if (!current) {
    console.error(`No status from ${APP_ID}. Is the debug build installed?`);
    process.exit(1);
  }
  if (current.shizuku !== "Ready") {
    console.error(
      `Shizuku is ${current.shizuku}. Installs would fall back to the system installer, which ` +
        `cannot set Play attribution — the matrix would measure nothing. Start and authorize ` +
        `Shizuku first.`,
    );
    process.exit(1);
  }

  // No --apps means the whole catalog, which is what a matrix run normally wants.
  const appIds = options.apps ?? (await allIds());
  if (appIds.length === 0) {
    console.error("Catalog is empty; nothing to run.");
    process.exit(1);
  }

  const startedAt = new Date().toISOString().replace(/[:.]/g, "-");
  const runDir = `runs/${startedAt}`;
  await mkdir(runDir, { recursive: true });
  const resultsPath = `${runDir}/results.jsonl`;
  const lines: string[] = [];

  for (const appId of appIds) {
    process.stdout.write(`${appId} … `);
    const outcome = await install(device.serial, appId);
    // The app reports what it believes happened; pm reports what actually did. Record both.
    //
    // The package name comes from the catalog, NOT from the app's pre-run status: before an
    // install the package is by definition absent from that list, which previously made every
    // successful install record installer=null / playAttributed=false.
    const packageName = await packageNameFor(appId);
    const installer = packageName ? await installerOf(device.serial, packageName) : null;

    // Visual evidence of what the run actually produced. Captured after the install so the
    // catalog card shows its resulting state, and recorded as null when the screen was off or
    // locked rather than failing a run whose real assertions do not need pixels.
    // LauncherActivity, not MainActivityNew: it is the real MAIN/LAUNCHER entry point and the
    // one `am start` will accept, and routing through it is what a user actually does.
    const foreground = await launch(
      device.serial,
      `${APP_ID}/com.legs.appsforaa.LauncherActivity`,
      APP_ID,
    );
    const shot = foreground
      ? await capture(device.serial, `${runDir}/screenshots`, appId)
      : null;

    const record = {
      startedAt,
      device: device.serial,
      model: device.model,
      sdk: device.sdk,
      appId,
      packageName,
      outcome: outcome.kind,
      detail: "version" in outcome ? outcome.version : "message" in outcome ? outcome.message : "",
      installer,
      playAttributed: isPlayAttributed(installer),
      androidAutoVisible: "unknown",
      screenshot: shot ? `screenshots/${appId}.png` : null,
    };
    lines.push(JSON.stringify(record));
    console.log(
      `${outcome.kind}${installer ? ` (installer=${installer})` : ""}` +
        `${shot ? ` [${shot.width}x${shot.height}]` : foreground ? " [no screenshot]" : " [app never focused]"}`,
    );
  }

  await Bun.write(resultsPath, lines.join("\n") + "\n");
  console.log(`\n${lines.length} result(s) -> ${resultsPath}`);

  // Compare against what this device did last time. A run that only records results answers
  // "what happened"; the baseline answers "what changed", which is the question between runs.
  const entries: baseline.BaselineEntry[] = lines
    .map((line) => JSON.parse(line))
    .map((record) => ({
      appId: record.appId,
      outcome: record.outcome,
      playAttributed: record.playAttributed,
    }));
  const previous = await baseline.load(device.model);
  const changes = baseline.diff(previous, entries);

  if (!previous) {
    console.log(`\nNo baseline for ${device.model} yet — re-run with --accept true to record one.`);
  } else if (changes.length === 0) {
    console.log(`\nNo change against the ${device.model} baseline.`);
  } else {
    console.log(`\n${changes.length} change(s) against the ${device.model} baseline:`);
    for (const change of changes) console.log(`  ${baseline.describe(change)}`);
  }

  if (options.accept) {
    const written = await baseline.save({
      device: device.serial,
      model: device.model,
      sdk: device.sdk,
      updated: startedAt,
      entries,
    });
    console.log(`Baseline updated -> ${written}`);
  }

  console.log("\nandroidAutoVisible is 'unknown' by design — see docs/aa-visibility.md (T-22).");

  // A regression is the reason this exists; make it visible to a shell, not just to a reader.
  if (changes.some((change) => change.kind === "broken")) process.exitCode = 1;
}

const { command, options } = parseArgs(Bun.argv.slice(2));
switch (command) {
  case "devices":
    await cmdDevices(options);
    break;
  case "status":
    await cmdStatus(options);
    break;
  case "matrix":
    await cmdMatrix(options);
    break;
  default:
    console.error(`Unknown command: ${command}. Try devices | status | matrix.`);
    process.exit(1);
}
