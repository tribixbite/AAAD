import { describe, expect, test } from "bun:test";
import { isNewer, normalize } from "./version.ts";

/**
 * Deliberately the same cases as `VersionCompareTest.kt`. Two implementations of one rule only
 * stay in agreement if they are held to the same examples — if these ever diverge, the dash and
 * the app would disagree about whether an app needs updating, and the dash would be the liar.
 */
describe("isNewer", () => {
  test("orders plain semantic versions", () => {
    expect(isNewer("1.0.3", "1.0.4")).toBe(true);
    expect(isNewer("1.0.4", "1.0.3")).toBe(false);
    expect(isNewer("1.0.3", "1.0.3")).toBe(false);
  });

  test("treats missing trailing segments as zero", () => {
    expect(isNewer("2.0", "2.0.0")).toBe(false);
    expect(isNewer("2.0.0", "2.0")).toBe(false);
    expect(isNewer("2.0", "2.0.1")).toBe(true);
  });

  test("compares segments numerically, not as text", () => {
    expect(isNewer("1.9.0", "1.10.0")).toBe(true);
    expect(isNewer("1.10.0", "1.9.0")).toBe(false);
  });

  test("strips a v prefix, as GitHub tags carry", () => {
    expect(normalize("v1.0.3")).toBe("1.0.3");
    expect(isNewer("1.0.2", "v1.0.3")).toBe(true);
  });

  test("keeps the numeric prefix of a decorated version", () => {
    expect(normalize("v0.88B")).toBe("0.88");
    expect(isNewer("0.87", "v0.88B")).toBe(true);
  });

  test("returns null rather than guessing at an uncomparable version", () => {
    expect(isNewer("1.0", "beta1.1")).toBeNull();
    expect(isNewer("1.0", "untagged-7666cf8b031e67be69d2")).toBeNull();
    expect(isNewer(null, "1.0")).toBeNull();
    expect(isNewer("1.0", "")).toBeNull();
  });

  test("normalize rejects strings with no leading number", () => {
    expect(normalize("beta1.1")).toBeNull();
    expect(normalize("untagged-abc123")).toBeNull();
    expect(normalize(null)).toBeNull();
  });
});
