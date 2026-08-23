import { describe, expect, test } from "bun:test";
import { installCreateCommand, parseApkPaths, parseSessionId } from "./attribute.ts";

describe("parseApkPaths", () => {
  test("reads a single-APK app", () => {
    const out = "package:/data/app/~~jqw==/com.sec.android.app.popupcalculator-5GV4==/base.apk\n";
    expect(parseApkPaths(out)).toEqual([
      "/data/app/~~jqw==/com.sec.android.app.popupcalculator-5GV4==/base.apk",
    ]);
  });

  test("reads every APK of a split app", () => {
    // All of them must be re-staged: a session holding only the base either fails to commit or
    // produces an app missing its resources.
    const out = [
      "package:/data/app/~~a==/com.example-b==/base.apk",
      "package:/data/app/~~a==/com.example-b==/split_config.arm64_v8a.apk",
      "package:/data/app/~~a==/com.example-b==/split_config.xxhdpi.apk",
    ].join("\n");
    expect(parseApkPaths(out)).toHaveLength(3);
  });

  test("an uninstalled package yields nothing", () => {
    expect(parseApkPaths("")).toEqual([]);
    expect(parseApkPaths("\n\n")).toEqual([]);
  });
});

describe("installCreateCommand", () => {
  test("always declares the Play Store as installer", () => {
    // The whole point: attribution can only be set at session creation.
    expect(installCreateCommand(33)).toContain("-i com.android.vending");
    expect(installCreateCommand(33)).toContain("--install-reason 0");
  });

  test("adds the low-target-sdk bypass from API 34", () => {
    expect(installCreateCommand(33)).not.toContain("--bypass-low-target-sdk-block");
    expect(installCreateCommand(34)).toContain("--bypass-low-target-sdk-block");
    expect(installCreateCommand(36)).toContain("--bypass-low-target-sdk-block");
  });
});

describe("parseSessionId", () => {
  test("takes the id out of pm's prose", () => {
    expect(parseSessionId("Success: created install session [1660368340]")).toBe(1660368340);
  });

  test("returns null when pm did not create one", () => {
    expect(parseSessionId("Error: java.lang.SecurityException")).toBeNull();
    expect(parseSessionId("")).toBeNull();
  });
});
