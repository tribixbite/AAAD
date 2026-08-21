/**
 * Screenshot capture.
 *
 * Every image is downscaled before it lands on disk. The constraint is not cosmetic: images from
 * these runs get read by agents, and anything with a dimension at or above 2000 px, or over 4 MB,
 * is rejected. A 1080x2400 phone screenshot is already over the height limit, so capturing at
 * native size and "dealing with it later" means every single capture needs reprocessing.
 */

import { mkdir } from "node:fs/promises";
import { shell, adb } from "./adb.ts";

/** Hard ceiling per side. Below the 2000 px limit with room to spare. */
const MAX_EDGE = 1400;

/** Where the device stages a capture before it is pulled. */
const DEVICE_TMP = "/sdcard/aaad-harness-capture.png";

export interface CaptureResult {
  path: string;
  width: number;
  height: number;
  bytes: number;
}

/**
 * Captures the current screen to `<dir>/<name>.png`, downscaled.
 *
 * @returns null when the device produced nothing. A zero-byte capture is the normal symptom of a
 *   screen that is off or showing a secure lock screen — worth reporting as "no screenshot"
 *   rather than failing the whole run, since the run's real assertions do not need pixels.
 */
export async function capture(
  serial: string,
  dir: string,
  name: string,
): Promise<CaptureResult | null> {
  await mkdir(dir, { recursive: true });
  const localRaw = `${dir}/${name}.raw.png`;
  const localPath = `${dir}/${name}.png`;

  await shell(serial, `screencap -p ${DEVICE_TMP}`, 60_000);
  await adb(["-s", serial, "pull", DEVICE_TMP, localRaw], 90_000);
  await shell(serial, `rm -f ${DEVICE_TMP}`, 20_000);

  const raw = Bun.file(localRaw);
  if (!(await raw.exists()) || raw.size === 0) {
    await Bun.$`rm -f ${localRaw}`.quiet().nothrow();
    return null;
  }

  const resized = await downscale(localRaw, localPath);
  await Bun.$`rm -f ${localRaw}`.quiet().nothrow();
  return resized;
}

/**
 * Downscales with ImageMagick, which is present on this box. Falls back to keeping the original
 * if it is missing — an oversized screenshot is still better than none, and the caller is told
 * the real dimensions either way.
 */
async function downscale(from: string, to: string): Promise<CaptureResult> {
  const converted = await Bun.$`magick ${from} -resize ${MAX_EDGE}x${MAX_EDGE}\> ${to}`
    .quiet()
    .nothrow();
  if (converted.exitCode !== 0) {
    await Bun.$`cp ${from} ${to}`.quiet().nothrow();
  }

  const identify = await Bun.$`magick identify -format "%w %h" ${to}`.quiet().nothrow();
  const [width = 0, height = 0] = identify.stdout
    .toString()
    .trim()
    .split(/\s+/)
    .map(Number);

  return { path: to, width, height, bytes: Bun.file(to).size };
}

/**
 * Brings an app to the foreground and waits until it is actually there.
 *
 * Returns false if the app never takes focus, so the caller can skip the screenshot rather than
 * record one. A capture of whatever happened to be on screen — the launcher, a system dialog — is
 * misleading evidence, which is worse than no evidence.
 */
export async function launch(
  serial: string,
  component: string,
  expectedPackage: string,
  timeoutMs = 15_000,
): Promise<boolean> {
  await shell(serial, `am start -n ${component}`, 30_000);

  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await Bun.sleep(1_000);
    const { stdout } = await shell(
      serial,
      "dumpsys window | grep -m1 mCurrentFocus",
      20_000,
    );
    if (stdout.includes(expectedPackage)) return true;
  }
  return false;
}
