import { describe, expect, test } from "bun:test";
import { parseTasks, rollup } from "./tasks.ts";

const SAMPLE = `
# AAAD tasks

## Phase 1 — Foundations

- [x] **T-01** Did the thing.
  Rationale that must not be parsed as a task.
- [ ] **T-02** Pending with \`code\` and **bold** and a [link](docs/x.md).

## Phase 2 — Later

- [ ] **T-10** Another one.
`;

describe("parseTasks", () => {
  test("reads id, state and phase", () => {
    const tasks = parseTasks(SAMPLE);
    expect(tasks).toHaveLength(3);
    expect(tasks[0]).toEqual({ id: "T-01", done: true, title: "Did the thing.", phase: "Phase 1 — Foundations" });
    expect(tasks[2]!.phase).toBe("Phase 2 — Later");
  });

  test("strips inline markdown from the title", () => {
    // The dash renders text, so leftover backticks and link syntax would show up verbatim.
    expect(parseTasks(SAMPLE)[1]!.title).toBe("Pending with code and bold and a link.");
  });

  test("indented rationale is not a task", () => {
    // Every task in this backlog carries paragraphs beneath it; treating those as rows would
    // bury the actual list.
    expect(parseTasks(SAMPLE).map((t) => t.id)).toEqual(["T-01", "T-02", "T-10"]);
  });
});

describe("rollup", () => {
  test("counts per phase, in file order", () => {
    const phases = rollup(parseTasks(SAMPLE));
    expect(phases.map((p) => p.phase)).toEqual(["Phase 1 — Foundations", "Phase 2 — Later"]);
    expect(phases[0]).toMatchObject({ done: 1, total: 2 });
    expect(phases[1]).toMatchObject({ done: 0, total: 1 });
  });
});
