/**
 * Reads `TASKS.md` into a structure the dash can render.
 *
 * The backlog is authored as prose because that is how it is actually read — in an editor, with
 * the reasoning attached. Parsing it rather than keeping a second machine-readable copy means the
 * two can never disagree, and the file stays the single source of truth.
 *
 * Only the checkbox line is parsed. Everything indented under it is rationale, and the dash shows
 * a count and a title, not an essay.
 */

import { readFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const TASKS_PATH = join(HERE, "..", "..", "TASKS.md");

export interface Task {
  id: string;
  done: boolean;
  title: string;
  /** The `## ` heading it sits under, e.g. "Phase 5 — App changes worth making". */
  phase: string;
}

export interface PhaseRollup {
  phase: string;
  done: number;
  total: number;
  tasks: Task[];
}

/** `- [x] **T-12** Some title that runs on…` */
const TASK_LINE = /^-\s*\[([ xX])\]\s*\*\*(T-[0-9a-z-]+)\*\*\s*(.*)$/;
const HEADING = /^##\s+(.*)$/;

export function parseTasks(markdown: string): Task[] {
  const tasks: Task[] = [];
  let phase = "Backlog";

  for (const line of markdown.split("\n")) {
    const heading = HEADING.exec(line);
    if (heading) {
      phase = heading[1]!.trim();
      continue;
    }
    const match = TASK_LINE.exec(line);
    if (!match) continue;

    // Titles carry inline markdown and often run into the rationale sentence; keep the first
    // sentence-ish chunk so a row stays one line.
    const title = match[3]!
      .replace(/\*\*/g, "")
      .replace(/`/g, "")
      .replace(/\[([^\]]+)\]\([^)]+\)/g, "$1")
      .trim();

    tasks.push({
      id: match[2]!,
      done: match[1]!.toLowerCase() === "x",
      title: title.length > 160 ? `${title.slice(0, 157)}…` : title,
      phase,
    });
  }
  return tasks;
}

/** Grouped in file order, because the file is ordered by priority. */
export function rollup(tasks: Task[]): PhaseRollup[] {
  const phases: PhaseRollup[] = [];
  for (const task of tasks) {
    let entry = phases.find((p) => p.phase === task.phase);
    if (!entry) {
      entry = { phase: task.phase, done: 0, total: 0, tasks: [] };
      phases.push(entry);
    }
    entry.total++;
    if (task.done) entry.done++;
    entry.tasks.push(task);
  }
  return phases;
}

export async function loadTasks(): Promise<PhaseRollup[]> {
  const raw = await readFile(TASKS_PATH, "utf8").catch(() => "");
  return rollup(parseTasks(raw));
}
