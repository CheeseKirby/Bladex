export interface OneShotModuleSuggestion {
  type: string;
  name: string;
  icon?: string;
  config?: Record<string, unknown>;
}

/**
 * Canonical Plan Draft currently has one generation identity. Keep one configured ENTITY so
 * the LLM never receives mutually exclusive immutable entity/table constraints.
 */
export function normalizeOneShotSuggestions(value: unknown[]): OneShotModuleSuggestion[] {
  const result: OneShotModuleSuggestion[] = [];
  let entitySeen = false;
  for (const candidate of value) {
    if (!isRecord(candidate) || typeof candidate.type !== 'string' || typeof candidate.name !== 'string') continue;
    const type = candidate.type.trim().toUpperCase();
    if (!type || !candidate.name.trim()) continue;
    if (type === 'ENTITY') {
      if (entitySeen) continue;
      entitySeen = true;
    }
    result.push({
      type,
      name: candidate.name.trim(),
      ...(typeof candidate.icon === 'string' ? { icon: candidate.icon } : {}),
      ...(isRecord(candidate.config) ? { config: candidate.config } : {}),
    });
  }
  return result;
}

export function assertSingleConfiguredEntity(modules: Array<{ type?: unknown; name?: unknown }>): void {
  const entities = modules.filter((module) => module.type === 'ENTITY');
  if (entities.length <= 1) return;
  const names = entities.map((module) => typeof module.name === 'string' ? module.name : '(unnamed)').join(', ');
  throw new Error(`PLAN_INPUT_CONFLICT: Canonical generation accepts one ENTITY identity, but received ${entities.length}: ${names}. Re-run one-click completion or split the feature before generation.`);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
