/**
 * Drives the AAAD debug build through its adb automation hook.
 *
 * Why not UI automation: simulated taps need an unlocked screen, a known scroll offset and stable
 * pixel coordinates, none of which survive a lock screen or a layout change — and all of which
 * are incidental to what is being tested. The debug receiver
 * (`app/src/debug/.../DebugAutomationReceiver.kt`, debug builds only) takes the action as an
 * intent and reports `RESULT=…` to logcat instead.
 */

import { shell, type AdbResult } from "./adb.ts";

export const APP_ID = "sksa.aa.customapps.dev";
const LOG_TAG = "AAAD/DebugAutomation";
const PLAY_STORE = "com.android.vending";

/**
 * FLAG_INCLUDE_STOPPED_PACKAGES. Without it a broadcast silently does nothing after
 * `am force-stop`, because a stopped app receives no manifest broadcasts — the receiver never
 * runs and `am` still prints `result=0`.
 */
const INCLUDE_STOPPED = "0x00000020";

export type ActionResult =
  | { kind: "attributed"; version: string }
  | { kind: "converted"; packageName: string }
  | { kind: "system-installer" }
  | { kind: "failed"; message: string }
  | { kind: "timeout" };

/** Clears logcat so the next read cannot pick up a previous run's verdict. */
export async function clearLog(serial: string): Promise<void> {
  await shell(serial, "logcat -c", 20_000);
}

async function broadcast(serial: string, action: string, extras = ""): Promise<AdbResult> {
  return shell(
    serial,
    `am broadcast -f ${INCLUDE_STOPPED} -p ${APP_ID} -a com.legs.appsforaa.${action} ${extras}`.trim(),
    60_000,
  );
}

/** Polls logcat until the receiver reports a verdict, or gives up. */
async function awaitResult(serial: string, timeoutMs: number): Promise<ActionResult> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await Bun.sleep(2_000);
    const { stdout } = await shell(serial, `logcat -d -s ${LOG_TAG}:V`, 30_000);
    const line = stdout.split("\n").reverse().find((l) => l.includes("RESULT="));
    if (!line) continue;

    if (line.includes("RESULT=ATTRIBUTED")) {
      return { kind: "attributed", version: /version=(\S+)/.exec(line)?.[1] ?? "" };
    }
    if (line.includes("RESULT=CONVERTED")) {
      return { kind: "converted", packageName: line.trim().split(/\s+/).pop() ?? "" };
    }
    if (line.includes("RESULT=SYSTEM_INSTALLER")) return { kind: "system-installer" };
    if (line.includes("RESULT=FAILED") || line.includes("RESULT=ERROR")) {
      return { kind: "failed", message: line.split("RESULT=")[1]?.trim() ?? "unknown" };
    }
    if (line.includes("RESULT=TIMEOUT")) return { kind: "timeout" };
  }
  return { kind: "timeout" };
}

export async function install(
  serial: string,
  entryId: string,
  timeoutMs = 300_000,
): Promise<ActionResult> {
  await clearLog(serial);
  await broadcast(serial, "DEBUG_INSTALL", `--es id ${entryId}`);
  return awaitResult(serial, timeoutMs);
}

export async function convert(
  serial: string,
  packageName: string,
  timeoutMs = 180_000,
): Promise<ActionResult> {
  await clearLog(serial);
  await broadcast(serial, "DEBUG_CONVERT", `--es package ${packageName}`);
  return awaitResult(serial, timeoutMs);
}

export interface AppStatus {
  shizuku: string;
  catalogApps: number;
  aaCapableInstalled: number;
  convertible: number;
  installed: { packageName: string; state: string; installer: string }[];
}

export async function status(serial: string, timeoutMs = 60_000): Promise<AppStatus | null> {
  await clearLog(serial);
  await broadcast(serial, "DEBUG_STATUS");

  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await Bun.sleep(2_000);
    const { stdout } = await shell(serial, `logcat -d -s ${LOG_TAG}:V`, 30_000);
    const summary = stdout.split("\n").find((l) => l.includes("RESULT=STATUS"));
    if (!summary) continue;

    const num = (key: string) => Number(new RegExp(`${key}=(-?\\d+)`).exec(summary)?.[1] ?? -1);
    const installed = stdout
      .split("\n")
      .filter((l) => l.includes("  installed: "))
      .map((l) => {
        const body = l.split("installed: ")[1] ?? "";
        return {
          packageName: body.split(/\s+/)[0] ?? "",
          state: /state=(\S+)/.exec(body)?.[1] ?? "",
          installer: /installer=(\S+)/.exec(body)?.[1] ?? "",
        };
      });

    return {
      shizuku: /availability=(\S+)/.exec(summary)?.[1] ?? "unknown",
      catalogApps: num("catalogApps"),
      aaCapableInstalled: num("aaCapableInstalled"),
      convertible: num("convertible"),
      installed,
    };
  }
  return null;
}

/**
 * The installer package recorded for `packageName`, or null when it is not installed.
 *
 * This is the ground truth the harness can actually check. Whether Android Auto then *lists* the
 * app is a separate question that needs a projection session — see docs/aa-visibility.md.
 */
export async function installerOf(serial: string, packageName: string): Promise<string | null> {
  const { stdout } = await shell(serial, `pm list packages -i ${packageName}`, 30_000);
  const line = stdout.split("\n").find((l) => l.includes(`package:${packageName} `));
  return /installer=(\S+)/.exec(line ?? "")?.[1] ?? null;
}

export function isPlayAttributed(installer: string | null): boolean {
  return installer === PLAY_STORE;
}
