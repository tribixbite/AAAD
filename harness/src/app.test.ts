import { describe, expect, test } from "bun:test";
import { parseResultLine } from "./app.ts";

/**
 * These are real logcat lines, prefix and all. The parser reads whole lines out of
 * `logcat -d -s AAAD/DebugAutomation:V`, so testing it against bare `RESULT=` fragments would
 * pass while the real thing failed on the timestamp and pid columns.
 */
const prefix = "08-22 13:41:48.998  9384  9384 I AAAD/DebugAutomation: ";

describe("parseResultLine", () => {
  test("reads an attributed install and its version", () => {
    expect(parseResultLine(`${prefix}RESULT=ATTRIBUTED version=untagged-7666cf8b031e67be69d2`)).toEqual({
      kind: "attributed",
      version: "untagged-7666cf8b031e67be69d2",
    });
  });

  test("reads a conversion", () => {
    expect(parseResultLine(`${prefix}RESULT=CONVERTED nl.frankkie.nav2contacts`)).toEqual({
      kind: "converted",
      packageName: "nl.frankkie.nav2contacts",
    });
  });

  test("reads a system-installer handoff", () => {
    expect(parseResultLine(`${prefix}RESULT=SYSTEM_INSTALLER (not Play-attributed)`)).toEqual({
      kind: "system-installer",
    });
  });

  /**
   * The case this test file exists for. NEEDS_SHIZUKU is logged at error level like FAILED; if it
   * had no branch it would match nothing, and the poll would spin to its five-minute timeout and
   * look like a hung install rather than a precondition that was not met.
   */
  test("reads an unattended install that needed Shizuku", () => {
    const line =
      `${prefix}RESULT=NEEDS_SHIZUKU Shizuku is not ready; unattended install ` +
      "cannot fall back to the system installer";
    expect(parseResultLine(line)).toEqual({ kind: "needs-shizuku" });
  });

  test("reads a failure with its message", () => {
    expect(parseResultLine(`${prefix}RESULT=FAILED No asset matching \\.apk$ in owner/repo`)).toEqual({
      kind: "failed",
      message: "FAILED No asset matching \\.apk$ in owner/repo",
    });
  });

  test("reads an error the same way as a failure", () => {
    expect(parseResultLine(`${prefix}RESULT=ERROR missing --es id`)).toEqual({
      kind: "failed",
      message: "ERROR missing --es id",
    });
  });

  test("reads the receiver's own timeout", () => {
    expect(parseResultLine(`${prefix}RESULT=TIMEOUT`)).toEqual({ kind: "timeout" });
  });

  test("ignores lines that carry no verdict", () => {
    expect(parseResultLine(`${prefix}progress: Downloading(fraction=0.5)`)).toBeNull();
    expect(parseResultLine("")).toBeNull();
  });

  test("returns null for an unrecognised RESULT rather than guessing", () => {
    // A verdict this parser does not know must not be silently mapped onto one it does; null
    // keeps polling, and the caller's timeout is the honest outcome.
    expect(parseResultLine(`${prefix}RESULT=SOMETHING_NEW`)).toBeNull();
  });
});
