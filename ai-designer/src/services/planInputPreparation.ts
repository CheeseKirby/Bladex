import type { DraggedModule, ModuleType } from '../types/plan';

const MODULE_TYPES = new Set<ModuleType>(['ENTITY', 'API', 'PAGE', 'FLOW', 'JOB', 'FEIGN', 'EXCEL', 'CONFIG']);
const MODULE_COLORS: Record<ModuleType, string> = {
  ENTITY: '#1890ff', API: '#52c41a', PAGE: '#fa8c16', FLOW: '#722ed1',
  JOB: '#eb2f96', FEIGN: '#13c2c2', EXCEL: '#2f54eb', CONFIG: '#595959',
};
const JAVA_TYPE = /\b(?:String|Long|Integer|Date|Boolean|BigDecimal|LocalDateTime|LocalDate|LocalTime)\b/g;

export function needsAutomaticInputPreparation(requirement: string, modules: DraggedModule[]): boolean {
  const configuredEntities = modules.filter((module) => module.type === 'ENTITY'
    && nonBlank(module.config?.moduleName) && nonBlank(module.config?.entityName) && nonBlank(module.config?.tableName));
  if (configuredEntities.length !== 1) return true;
  const configuredFields = configuredEntities[0].config?.fields;
  if (Array.isArray(configuredFields) && configuredFields.length >= 3) return false;
  return (requirement.match(JAVA_TYPE) ?? []).length < 3;
}

export function createSuggestedModules(suggestions: unknown): DraggedModule[] {
  if (!Array.isArray(suggestions)) return [];
  return suggestions.flatMap((candidate, index) => {
    if (!isRecord(candidate) || typeof candidate.type !== 'string' || !MODULE_TYPES.has(candidate.type as ModuleType)) {
      return [];
    }
    const type = candidate.type as ModuleType;
    const config = isRecord(candidate.config) ? candidate.config : {};
    return [{
      id: `prepared_${type}_${Date.now()}_${index}`,
      type,
      name: typeof candidate.name === 'string' && candidate.name.trim() ? candidate.name.trim() : type,
      icon: typeof candidate.icon === 'string' && candidate.icon.trim() ? candidate.icon : '\ud83d\udce6',
      color: MODULE_COLORS[type],
      config,
    } as DraggedModule];
  });
}

function nonBlank(value: unknown): boolean {
  return typeof value === 'string' && Boolean(value.trim());
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
