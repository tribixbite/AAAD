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
  /**
   * Shizuku was not ready, and an unattended install refuses to fall back to a dialog nobody is
   * there to tap. Separate from `failed` because nothing is broken — the run just cannot proceed
   * until Shizuku is started, and a run that reports this needs a different response from one
   * that reports a download error.
   */
  | { kind: "needs-shizuku" }
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

/**
 * Maps one `RESULT=` logcat line to a verdict, or null when the line carries none.
 *
 * Pure and exported so every result the receiver can emit is checkable without a device. A
 * `RESULT=` the receiver logs but this does not recognise matches nothing, and the caller then
 * polls until its timeout — a five-minute stall that looks like a hung install rather than an
 * unhandled case. That is worth a test, not vigilance.
 */
export function parseResultLine(line: string): ActionResult | null {
  if (!line.includes("RESULT=")) return null;

  if (line.includes("RESULT=ATTRIBUTED")) {
    return { kind: "attributed", version: /version=(\S+)/.exec(line)?.[1] ?? "" };
  }
  if (line.includes("RESULT=CONVERTED")) {
    return { kind: "converted", packageName: line.trim().split(/\s+/).pop() ?? "" };
  }
  if (line.includes("RESULT=SYSTEM_INSTALLER")) return { kind: "system-installer" };
  if (line.includes("RESULT=NEEDS_SHIZUKU")) return { kind: "needs-shizuku" };
  if (line.includes("RESULT=FAILED") || line.includes("RESULT=ERROR")) {
    return { kind: "failed", message: line.split("RESULT=")[1]?.trim() ?? "unknown" };
  }
  if (line.includes("RESULT=TIMEOUT")) return { kind: "timeout" };
  return null;
}

/** Polls logcat until the receiver reports a verdict, or gives up. */
async function awaitResult(serial: string, timeoutMs: number): Promise<ActionResult> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await Bun.sleep(2_000);
    const { stdout } = await shell(serial, `logcat -d -s ${LOG_TAG}:V`, 30_000);
    // Newest first: a retried action leaves the earlier verdict in the buffer.
    const verdict = stdout
      .split("\n")
      .reverse()
      .map(parseResultLine)
      .find((result) => result !== null);
    if (verdict) return verdict;
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
