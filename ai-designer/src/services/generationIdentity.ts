import type { GenerationIdentityPayload } from '../types/api';
import type { Project } from '../types/plan';

const RESERVED = new Set([
  'pom', 'parent', 'entity', 'vo', 'dto', 'mapper', 'service', 'controller',
  'wrapper', 'excel', 'feign', 'api', 'impl', 'config', 'sql', 'database', 'core',
]);

function firstMatch(content: string, patterns: RegExp[]): string | undefined {
  for (const pattern of patterns) {
    const match = content.match(pattern);
    if (match?.[1]) return match[1];
  }
  return undefined;
}

export function normalizeModuleName(raw: string | undefined): string {
  let value = (raw || '').trim().toLowerCase().replace(/-/g, '_')
    .replace(/[^a-z0-9_]/g, '_').replace(/_+/g, '_').replace(/^_+|_+$/g, '');
  value = value.replace(/^blade_/, '').replace(/_api$/, '').replace(/_service$/, '');
  if (!/^[a-z][a-z0-9_]{1,49}$/.test(value) || RESERVED.has(value)) {
    throw new Error(`Invalid or reserved module name: ${raw || '(empty)'}`);
  }
  return value;
}

function normalizeEntityName(raw: string | undefined, moduleName: string): string {
  let value = (raw || '').trim().replace(/[^A-Za-z0-9]/g, '');
  if (!value) {
    value = moduleName.split('_').filter(Boolean)
      .map((part) => part[0].toUpperCase() + part.slice(1)).join('');
  }
  if (value && /^[a-z]/.test(value)) value = value[0].toUpperCase() + value.slice(1);
  if (!/^[A-Z][A-Za-z0-9]{1,99}$/.test(value) || value === 'Entity' || value === 'BaseEntity') {
    throw new Error(`Invalid entity name: ${raw || '(empty)'}`);
  }
  return value;
}

export function deriveGenerationIdentity(project: Project): GenerationIdentityPayload {
  const content = [
    project.masterPlan?.reviewedContent,
    project.masterPlan?.planContent,
    ...project.subPlans.flatMap((subPlan) => [subPlan.reviewedContent, subPlan.planContent]),
  ].filter(Boolean).join('\n');
  const entityModule = project.modules.find((module) => module.type === 'ENTITY');

  const declaredPackage = firstMatch(content, [
    /(?:basePackage|package|package path|\u5305\u8def\u5f84)\s*[:\uff1a=]?\s*`?([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)+)`?/i,
  ]);
  const packageModule = declaredPackage?.match(/^org\.springblade\.([a-z][a-z0-9_]*)/)?.[1];
  const detectedModule = firstMatch(content, [
    /(?:moduleName|module\s*name|module|\u6a21\u5757\u540d?)\s*[:\uff1a=]?\s*`?([a-z][a-z0-9_-]*)`?/i,
  ]);
  const detectedEntity = firstMatch(content, [
    /(?:entityName|entity\s*name|class\s*name|\u5b9e\u4f53\u7c7b\u540d|\u5b9e\u4f53\u540d|\u7c7b\u540d)\s*[:\uff1a=]?\s*`?([A-Z][A-Za-z0-9]*)`?/i,
    /class\s+([A-Z][A-Za-z0-9]*)\s+extends\s+(?:BaseEntity|TenantEntity|BladeEntity)/,
  ]);
  const detectedTable = firstMatch(content, [
    /(?:tableName|table\s*name|\u8868\u540d)\s*[:\uff1a=]?\s*`?([a-z][a-z0-9_]*)`?/i,
    /\b(blade_[a-z][a-z0-9_]*)\b/,
  ]);

  const configuredModule = entityModule?.config.moduleName;
  const configuredEntity = entityModule?.config.entityName;
  let moduleName: string;
  try {
    moduleName = normalizeModuleName(configuredModule || detectedModule || packageModule);
  } catch {
    const entityFallback = configuredEntity || detectedEntity;
    moduleName = normalizeModuleName(entityFallback ? entityFallback.toLowerCase() : 'business');
  }
  const entityName = normalizeEntityName(configuredEntity || detectedEntity, moduleName);
  const tableName = (entityModule?.config.tableName || detectedTable || `blade_${moduleName}`)
    .trim().toLowerCase().replace(/-/g, '_').replace(/[^a-z0-9_]/g, '_');
  const basePackage = `org.springblade.${moduleName}`;

  return {
    moduleName,
    entityName,
    tableName,
    basePackage,
    apiModuleName: `blade-${moduleName}-api`,
    serviceModuleName: `blade-${moduleName}`,
    serviceName: `blade-${moduleName}`,
  };
}
