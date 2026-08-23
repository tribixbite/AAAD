/**
 * Converts an installed app to Play Store attribution over **adb**, with no Shizuku involved.
 *
 * Shizuku runs as the shell uid (2000) — exactly what `adb shell` already is — so everything
 * `ShizukuInstaller` does on the phone can be done from here. That matters more than it sounds:
 * on this project's host device `shizuku_server` is a child of `adbd`, and the wireless-debugging
 * port rotates, so every rotation restarts adbd and kills Shizuku. Anything that depends on
 * Shizuku staying up is unreliable there; this path has nothing to lose.
 *
 * It is the same session dance the app performs: create a session declaring the Play Store as
 * installer, stream every APK the package owns into it, commit. Same signature, so it lands as an
 * update over the top and app data survives. Attribution can only be declared at session creation
 * — `pm set-installer` fails with a same-certificate SecurityException — so a fresh session is the
 * only route. See `docs/aa-visibility.md`.
 */

import { shell } from "./adb.ts";

const PLAY_STORE = "com.android.vending";
const PLAY_URI = "https://play.google.com/store";

export interface ConversionResult {
  packageName: string;
  ok: boolean;
  message: string;
  /** APKs re-staged. More than one means a split app. */
  apkCount: number;
}

/**
 * Parses `pm path <pkg>` output.
 *
 * Split apps print one `package:` line per APK and **all** of them must be re-staged: committing a
 * session holding only the base of a split app either fails or produces an app missing its
 * resources.
 */
export function parseApkPaths(stdout: string): string[] {
  return stdout
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.startsWith("package:"))
    .map((line) => line.slice("package:".length).trim())
    .filter((path) => path.length > 0);
}

/**
 * Builds the `pm install-create` command.
 *
 * `--bypass-low-target-sdk-block` is required from API 34 onward, where the platform otherwise
 * refuses to install anything targeting an old SDK — which many of these apps do.
 */
export function installCreateCommand(sdk: number): string {
  const bypass = sdk >= 34 ? " --bypass-low-target-sdk-block" : "";
  return (
    `pm install-create -r -i ${PLAY_STORE} ` +
    `--originating-uri '${PLAY_URI}' --install-reason 0${bypass}`
  );
}

/** `pm install-create` answers with the session id embedded in prose; take the last number. */
export function parseSessionId(stdout: string): number | null {
  const matches = stdout.match(/\d+/g);
  if (!matches?.length) return null;
  return Number(matches[matches.length - 1]);
}

export async function currentInstaller(serial: string, packageName: string): Promise<string | null> {
  const { stdout } = await shell(serial, `pm list packages -i ${packageName}`, 30_000);
  const line = stdout.split("\n").find((l) => l.includes(`package:${packageName} `));
  const installer = /installer=(\S+)/.exec(line ?? "")?.[1] ?? null;
  return installer === "null" ? null : installer;
}

export async function convertViaAdb(
  serial: string,
  packageName: string,
  sdk: number,
): Promise<ConversionResult> {
  const paths = parseApkPaths((await shell(serial, `pm path ${packageName}`, 30_000)).stdout);
  if (paths.length === 0) {
    return { packageName, ok: false, message: "not installed", apkCount: 0 };
  }

  const created = await shell(serial, installCreateCommand(sdk), 60_000);
  const session = parseSessionId(created.stdout);
  if (session === null) {
    return { packageName, ok: false, message: `install-create failed: ${created.stdout.trim()}`, apkCount: paths.length };
  }

  for (const [index, path] of paths.entries()) {
    const size = (await shell(serial, `stat -c%s ${path}`, 30_000)).stdout.trim();
    if (!/^\d+$/.test(size)) {
      await shell(serial, `pm install-abandon ${session}`, 30_000);
      return { packageName, ok: false, message: `cannot size ${path}`, apkCount: paths.length };
    }
    // Split names must be distinct within a session; the base is conventionally named first.
    const name = index === 0 ? "base.apk" : `split_${index}.apk`;
    const written = await shell(serial, `pm install-write -S ${size} ${session} ${name} ${path}`, 300_000);
    if (!written.stdout.includes("Success")) {
      await shell(serial, `pm install-abandon ${session}`, 30_000);
      return { packageName, ok: false, message: `install-write failed: ${written.stdout.trim()}`, apkCount: paths.length };
    }
  }

  const committed = await shell(serial, `pm install-commit ${session}`, 300_000);
  const ok = committed.stdout.includes("Success");
  return {
    packageName,
    ok,
    message: ok ? "converted" : committed.stdout.trim() || committed.stderr.trim(),
    apkCount: paths.length,
  };
}
