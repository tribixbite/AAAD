/**
 * Tests for the baseline comparator.
 *
 * This logic decides whether a run is reported as a regression, so getting it wrong is worse than
 * not having it: a false green hides exactly the breakage the harness exists to catch, and a false
 * red trains people to ignore it. It is also pure — no device, no network — so there is no excuse
 * for not testing it.
 *
 *   cd harness && bun test
 */

import { describe, expect, test } from "bun:test";
import { diff, type Baseline, type BaselineEntry } from "./baseline.ts";

const entry = (
  appId: string,
  outcome: string,
  playAttributed: boolean,
): BaselineEntry => ({ appId, outcome, playAttributed });

const baselineOf = (...entries: BaselineEntry[]): Baseline => ({
  device: "test:5555",
  model: "TestPhone",
  sdk: 33,
  updated: "2026-01-01",
  entries,
});

describe("diff", () => {
  test("treats everything as new when there is no baseline", () => {
    const changes = diff(null, [entry("a", "attributed", true)]);
    expect(changes).toHaveLength(1);
    expect(changes[0]!.kind).toBe("new");
  });

  test("reports nothing when a run matches its baseline", () => {
    const previous = baselineOf(entry("a", "attributed", true));
    expect(diff(previous, [entry("a", "attributed", true)])).toHaveLength(0);
  });

  test("losing Play attribution is broken, not merely changed", () => {
    const previous = baselineOf(entry("a", "attributed", true));
    const changes = diff(previous, [entry("a", "system-installer", false)]);
    expect(changes).toHaveLength(1);
    expect(changes[0]!.kind).toBe("broken");
  });

  test("gaining Play attribution is fixed", () => {
    const previous = baselineOf(entry("a", "system-installer", false));
    const changes = diff(previous, [entry("a", "attributed", true)]);
    expect(changes[0]!.kind).toBe("fixed");
  });

  test("an outcome change that keeps attribution is only 'changed'", () => {
    // Both attributed, so nothing regressed — this must not be reported as broken.
    const previous = baselineOf(entry("a", "attributed", true));
    const changes = diff(previous, [entry("a", "converted", true)]);
    expect(changes[0]!.kind).toBe("changed");
  });

  test("a failure that was never attributed does not masquerade as a regression", () => {
    // Failing twice in a row is not new breakage; reporting it as such would train people to
    // ignore the signal.
    const previous = baselineOf(entry("a", "failed", false));
    expect(diff(previous, [entry("a", "failed", false)])).toHaveLength(0);
  });

  test("an app missing from the run is not reported", () => {
    // Runs are commonly scoped with --apps; absence means "not tested", not "gone".
    const previous = baselineOf(entry("a", "attributed", true), entry("b", "attributed", true));
    expect(diff(previous, [entry("a", "attributed", true)])).toHaveLength(0);
  });

  test("reports each changed app independently", () => {
    const previous = baselineOf(
      entry("a", "attributed", true),
      entry("b", "attributed", true),
    );
    const changes = diff(previous, [
      entry("a", "attributed", true),
      entry("b", "failed", false),
      entry("c", "attributed", true),
    ]);
    expect(changes.map((change) => `${change.appId}:${change.kind}`).sort())
      .toEqual(["b:broken", "c:new"]);
  });
});
