/**
 * adb wrapper.
 *
 * Two device-specific realities shape this file, both learned the hard way:
 *
 * 1. On some phones the wireless-debugging port **rotates constantly**, so a remembered serial
 *    goes stale mid-run and every command fails with "device not found". Serials are therefore
 *    re-resolved on demand rather than cached, with a port rescan as the fallback.
 * 2. `adb shell` returns the *shell's* exit code, not the remote command's, and a pipeline
 *    reports the last stage. Callers get stdout/stderr and decide for themselves.
 */

export interface AdbResult {
  stdout: string;
  stderr: string;
  code: number;
}

export interface DeviceInfo {
  serial: string;
  model: string;
  androidRelease: string;
  sdk: number;
}

const DEFAULT_TIMEOUT_MS = 120_000;

/** Runs a raw adb command. `args` are passed through verbatim; no shell is involved. */
export async function adb(args: string[], timeoutMs = DEFAULT_TIMEOUT_MS): Promise<AdbResult> {
  const proc = Bun.spawn(["adb", ...args], { stdout: "pipe", stderr: "pipe" });
  const timer = setTimeout(() => proc.kill(), timeoutMs);
  try {
    const [stdout, stderr, code] = await Promise.all([
      new Response(proc.stdout).text(),
      new Response(proc.stderr).text(),
      proc.exited,
    ]);
    return { stdout, stderr, code };
  } finally {
    clearTimeout(timer);
  }
}

/** Runs a command on the device. Returns stdout even on failure — errors are often on stdout. */
export async function shell(
  serial: string,
  command: string,
  timeoutMs = DEFAULT_TIMEOUT_MS,
): Promise<AdbResult> {
  return adb(["-s", serial, "shell", command], timeoutMs);
}

/** Serials currently reporting `device` (not `offline`, not `unauthorized`). */
export async function onlineSerials(): Promise<string[]> {
  const { stdout } = await adb(["devices"], 15_000);
  return stdout
    .split("\n")
    .slice(1)
    .map((line) => line.trim().split(/\s+/))
    .filter((parts) => parts.length >= 2 && parts[1] === "device")
    .map((parts) => parts[0]);
}

/**
 * Finds a usable serial, rescanning `host` for a rotated wireless-debugging port if none is
 * online. Returns null when nothing answers.
 *
 * @param host dotted IP to rescan. Skipped entirely when omitted, since a port scan is slow.
 */
export async function resolveSerial(host?: string): Promise<string | null> {
  const online = await onlineSerials();
  if (online.length > 0) return online[0];
  if (!host) return null;

  // Stale offline entries for this host shadow a good connection; drop them first.
  const { stdout } = await adb(["devices"], 15_000);
  for (const line of stdout.split("\n")) {
    const serial = line.trim().split(/\s+/)[0];
    if (serial?.startsWith(`${host}:`)) await adb(["disconnect", serial], 10_000);
  }

  for (const port of await scanPorts(host)) {
    await adb(["connect", `${host}:${port}`], 10_000);
    const retry = await onlineSerials();
    if (retry.length > 0) return retry[0];
  }
  return null;
}

/** Open ports in the wireless-debugging range. Requires nmap; returns [] without it. */
async function scanPorts(host: string): Promise<number[]> {
  const proc = Bun.spawn(
    ["nmap", "-sT", "-p", "30000-65535", "--open", "-n", host],
    { stdout: "pipe", stderr: "pipe" },
  );
  const text = await new Response(proc.stdout).text();
  await proc.exited;
  return text
    .split("\n")
    .map((line) => /^(\d+)\/tcp\s+open/.exec(line.trim())?.[1])
    .filter((port): port is string => Boolean(port))
    .map(Number);
}

export async function describeDevice(serial: string): Promise<DeviceInfo> {
  const props = await shell(
    serial,
    "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk",
    20_000,
  );
  const [model = "unknown", androidRelease = "unknown", sdk = "0"] = props.stdout
    .trim()
    .split("\n")
    .map((line) => line.trim());
  return { serial, model, androidRelease, sdk: Number(sdk) || 0 };
}
