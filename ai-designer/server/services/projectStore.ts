import { mkdir, rename, unlink, writeFile } from 'node:fs/promises';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';

export type StoredProject = Record<string, unknown> & {
  id: string;
  updatedAt: string;
};

type StoreDocument = {
  schemaVersion: 1;
  projects: StoredProject[];
};

export function resolveProjectStorePath(configuredPath = process.env.BFF_PLAN_STORE_PATH): string {
  if (configuredPath?.trim()) return path.resolve(configuredPath.trim());
  return path.resolve(process.cwd(), '..', 'output', 'bff-projects.json');
}

/**
 * Small durable project store for the BFF.
 *
 * Writes are serialized and committed through a temporary file + rename so a process interruption cannot leave a
 * partially-written JSON document. The store intentionally remains file-backed: Part B owns workflow execution data,
 * while this file only preserves Part A's editable design state across BFF restarts.
 */
export class ProjectStore {
  private readonly projects = new Map<string, StoredProject>();
  private writeQueue: Promise<void> = Promise.resolve();

  constructor(public readonly filePath = resolveProjectStorePath()) {
    this.loadFromDisk();
  }

  async save(project: Record<string, unknown>): Promise<StoredProject> {
    const id = normalizeId(project.id);
    if (!id) throw new Error('缺少项目ID');

    return this.enqueue(async () => {
      const previous = this.projects.get(id);
      const saved: StoredProject = {
        ...project,
        id,
        updatedAt: new Date().toISOString(),
      };
      this.projects.set(id, saved);
      try {
        await this.persist();
      } catch (error) {
        if (previous) this.projects.set(id, previous);
        else this.projects.delete(id);
        throw error;
      }
      return { ...saved };
    });
  }

  get(id: string): StoredProject | undefined {
    const project = this.projects.get(id);
    return project ? { ...project } : undefined;
  }

  list(): StoredProject[] {
    return Array.from(this.projects.values())
      .map((project) => ({ ...project }))
      .sort((left, right) => right.updatedAt.localeCompare(left.updatedAt));
  }

  private loadFromDisk(): void {
    if (!existsSync(this.filePath)) return;
    try {
      const parsed = JSON.parse(readFileSync(this.filePath, 'utf8')) as unknown;
      for (const project of extractProjects(parsed)) {
        this.projects.set(project.id, project);
      }
    } catch (error) {
      console.warn(`[BFF] Ignoring unreadable project store ${this.filePath}: ${errorMessage(error)}`);
    }
  }

  private async persist(): Promise<void> {
    const directory = path.dirname(this.filePath);
    await mkdir(directory, { recursive: true });
    const tempPath = path.join(directory,
      `.${path.basename(this.filePath)}.${process.pid}.${Date.now()}.${Math.random().toString(16).slice(2)}.tmp`);
    const document: StoreDocument = {
      schemaVersion: 1,
      projects: Array.from(this.projects.values()),
    };
    try {
      await writeFile(tempPath, `${JSON.stringify(document, null, 2)}\n`, 'utf8');
      await rename(tempPath, this.filePath);
    } finally {
      await unlink(tempPath).catch((error: NodeJS.ErrnoException) => {
        if (error.code !== 'ENOENT') console.warn(`[BFF] Failed to clean project-store temp file: ${error.message}`);
      });
    }
  }

  private enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.writeQueue.then(operation, operation);
    this.writeQueue = result.then(() => undefined, () => undefined);
    return result;
  }
}

function extractProjects(value: unknown): StoredProject[] {
  let candidates: unknown[] = [];
  if (Array.isArray(value)) candidates = value;
  else if (isRecord(value) && Array.isArray(value.projects)) candidates = value.projects;
  else if (isRecord(value)) candidates = Object.values(value);

  const projects: StoredProject[] = [];
  for (const candidate of candidates) {
    if (!isRecord(candidate)) continue;
    const id = normalizeId(candidate.id);
    if (!id) continue;
    const updatedAt = typeof candidate.updatedAt === 'string' && candidate.updatedAt
      ? candidate.updatedAt
      : new Date(0).toISOString();
    projects.push({ ...candidate, id, updatedAt });
  }
  return projects;
}

function normalizeId(value: unknown): string | null {
  if (typeof value !== 'string' && typeof value !== 'number') return null;
  const id = String(value).trim();
  return id || null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

export const projectStore = new ProjectStore();
