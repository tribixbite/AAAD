import { describe, expect, test } from "bun:test";
import { parseInventoryLine } from "./inventory.ts";
import { isFresh } from "./releases.ts";

describe("parseInventoryLine", () => {
  test("reads version and installer from a normal line", () => {
    const line =
      "ENTRY|nl.frankkie.nav2contacts|   versionName=1.0.3|" +
      "package:nl.frankkie.nav2contacts  installer=com.android.vending";
    expect(parseInventoryLine(line)).toEqual({
      packageName: "nl.frankkie.nav2contacts",
      installedVersion: "1.0.3",
      installer: "com.android.vending",
    });
  });

  test("treats the literal string null as no installer", () => {
    // This is the state the whole project is about: installed, but unattributed, so Android Auto
    // will not list it. Reporting an installer *named* "null" would hide that.
    const line =
      "ENTRY|de.nsvb.android.auto.widget|   versionName=0.2.2|" +
      "package:de.nsvb.android.auto.widget  installer=null";
    expect(parseInventoryLine(line)).toEqual({
      packageName: "de.nsvb.android.auto.widget",
      installedVersion: "0.2.2",
      installer: null,
    });
  });

  test("an absent package yields empty fields, not a missing entry", () => {
    expect(parseInventoryLine("ENTRY|com.does.not.exist||")).toEqual({
      packageName: "com.does.not.exist",
      installedVersion: null,
      installer: null,
    });
  });

  test("ignores unrelated shell output", () => {
    expect(parseInventoryLine("")).toBeNull();
    expect(parseInventoryLine("bash: dumpsys: not found")).toBeNull();
  });
});

describe("isFresh", () => {
  const now = Date.parse("2026-08-22T12:00:00.000Z");

  test("a snapshot from ten minutes ago is fresh", () => {
    expect(isFresh({ fetchedAt: "2026-08-22T11:50:00.000Z", releases: [] }, now)).toBe(true);
  });

  test("a snapshot from two hours ago is stale", () => {
    expect(isFresh({ fetchedAt: "2026-08-22T10:00:00.000Z", releases: [] }, now)).toBe(false);
  });

  test("no snapshot is not fresh", () => {
    expect(isFresh(null, now)).toBe(false);
  });

  test("a future timestamp is not treated as fresh", () => {
    // A clock change should force a re-fetch rather than pin a cache that never expires.
    expect(isFresh({ fetchedAt: "2026-08-23T00:00:00.000Z", releases: [] }, now)).toBe(false);
  });

  test("an unparseable timestamp is not fresh", () => {
    expect(isFresh({ fetchedAt: "whenever", releases: [] }, now)).toBe(false);
  });
});
