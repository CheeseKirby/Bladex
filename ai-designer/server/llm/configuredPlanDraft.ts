import type { PlanDraftV2 } from './planDraft';

interface ModuleSummary { type: string; name: string; config?: unknown }
const BASE_FIELDS = new Set(['id', 'tenantId', 'createUser', 'createDept', 'createTime', 'updateUser', 'updateTime', 'status', 'isDeleted']);
const JAVA_TYPES = 'String|Long|Integer|Date|Boolean|BigDecimal|LocalDateTime|LocalDate|LocalTime';

export function compileConfiguredPlanDraft(userInput: string, modules: ModuleSummary[]): PlanDraftV2 | null {
  const entityModules = modules.filter((module) => module.type === 'ENTITY');
  if (entityModules.length !== 1) return null;
  const config = isRecord(entityModules[0].config) ? entityModules[0].config : {};
  const moduleName = text(config.moduleName);
  const entityName = text(config.entityName);
  const tableName = text(config.tableName);
  if (!moduleName || !entityName || !tableName) return null;
  const fields = extractFields(userInput);
  if (fields.length < 3) return null;
  const excelExplicitlyDisabled = config.needExcel === false
    || /\u65e0\u9700\s*Excel|\u4e0d\u9700\u8981\s*Excel|Excel\s*[:\uff1a]\s*\u5426/i.test(userInput);
  const needExcel = !excelExplicitlyDisabled
    && (config.needExcel === true || /\bExcel\b|\u5bfc\u5165\u5bfc\u51fa|\u6279\u91cf\u5bfc\u5165/i.test(userInput));
  const needFeign = !/\u65e0\u9700\s*Feign|\u4e0d\u9700\u8981\s*Feign|Feign\s*[:\uff1a]\s*\u5426/i.test(userInput)
    && /\bFeign\b|\u8fdc\u7a0b\u8c03\u7528/i.test(userInput);
  const deliverables: PlanDraftV2['deliverables'] = [
    { kind: 'DDL', moduleSide: 'DOC', action: 'CREATE' },
    { kind: 'ENTITY', className: entityName, moduleSide: 'API', action: 'CREATE' },
    { kind: 'VO', className: `${entityName}VO`, moduleSide: 'API', action: 'CREATE' },
    { kind: 'MAPPER', className: `${entityName}Mapper`, moduleSide: 'IMPL', action: 'CREATE' },
    { kind: 'SERVICE', className: `I${entityName}Service`, moduleSide: 'IMPL', action: 'CREATE' },
    { kind: 'CONTROLLER', className: `${entityName}Controller`, moduleSide: 'IMPL', action: 'CREATE' },
  ];
  if (needFeign) deliverables.push({ kind: 'FEIGN', className: `I${entityName}Client`, moduleSide: 'API', action: 'CREATE' });
  if (needExcel) deliverables.push({ kind: 'EXCEL', className: `${entityName}Excel`, moduleSide: 'IMPL', action: 'CREATE' });
  const states: PlanDraftV2['states'] = /\u72b6\u6001\u673a/.test(userInput) && /\bstatus\b/i.test(userInput)
    ? [{ name: `${entityName}Status`, values: ['0', '1', '2'], transitions: [
      { from: '0', to: '1', trigger: 'enable' }, { from: '1', to: '2', trigger: 'disable' },
      { from: '2', to: '1', trigger: 'enable' },
    ], referenceField: 'status' }]
    : [];
  return {
    identity: { moduleName, entityName, tableName, basePackage: `org.springblade.${moduleName.replace(/[^A-Za-z0-9]/g, '')}` },
    title: `${entityModules[0].name || entityName} implementation plan`,
    requirementSummary: userInput.trim(), fields, states,
    integrations: [
      { type: 'API', sourceModule: moduleName, entrypoint: `${entityName}Controller.list` },
      ...(needFeign ? [{ type: 'FEIGN' as const, sourceModule: moduleName, entrypoint: `I${entityName}Client.matchSpecialPeriod` }] : []),
    ],
    deliverables, architectureDecisions: [],
  };
}

function extractFields(input: string): PlanDraftV2['fields'] {
  const fields: PlanDraftV2['fields'] = [];
  const addField = (name: string, javaType: string, requiredMarker: string, description: string) => {
    if (BASE_FIELDS.has(name) || fields.some((field) => field.name === name)) return;
    fields.push({
      name,
      columnName: snake(name),
      javaType,
      required: requiredMarker === '\u662f' || requiredMarker === '\u5fc5\u586b',
      role: 'PERSISTENT',
      description: description.trim(),
    });
  };

  // The one-click completer emits one field per line without mandatory numbering or slash delimiters.
  // Parse that authoritative format first and keep the compact single-line formats for compatibility.
  const fieldLine = new RegExp(
    `^\\s*(?:[-*\\u2022]\\s*)?(?:\\d+[.\\u3001)]\\s*)?([A-Za-z][A-Za-z0-9]*)`
      + `\\s*(?:/\\s*|\\s+)(${JAVA_TYPES})\\s*(?:/\\s*|\\s+)`
      + `(\\u662f|\\u5426|\\u5fc5\\u586b|\\u975e\\u5fc5\\u586b|\\u9009\\u586b|\\u53ef\\u9009)`
      + `\\s*(?:/\\s*|\\s+)(.+?)\\s*$`,
  );
  for (const line of input.split(/\r?\n/)) {
    const match = fieldLine.exec(line);
    if (match) addField(match[1], match[2], match[3], match[4]);
  }

  const normalized = input.replace(/\s+/g, ' ');
  const marker = '\\u662f|\\u5426|\\u5fc5\\u586b|\\u975e\\u5fc5\\u586b|\\u9009\\u586b|\\u53ef\\u9009';
  const numbered = new RegExp(`(?:^|\\s)\\d+\\.\\s*([A-Za-z][A-Za-z0-9]*)\\s+(${JAVA_TYPES})\\s+(${marker})\\s+(.+?)(?=\\s+\\d+\\.\\s*[A-Za-z]|\\s+\\u4e09\\u3001|\\s+\\u56db\\u3001|\\s+\\u4e94\\u3001|$)`, 'g');
  const slashDelimited = new RegExp(`(?:^|\\s)([A-Za-z][A-Za-z0-9]*)\\s*\\/\\s*(${JAVA_TYPES})\\s*\\/\\s*(${marker})\\s*\\/\\s*(.+?)(?=\\s+[A-Za-z][A-Za-z0-9]*\\s*\\/\\s*(?:${JAVA_TYPES})\\s*\\/|\\s+\\d+\\.\\s*(?:\\u4e1a\\u52a1\\u72b6\\u6001\\u673a|\\u5173\\u952e\\u4e1a\\u52a1\\u89c4\\u5219|\\u662f\\u5426\\u9700\\u8981)|$)`, 'g');
  for (const pattern of [numbered, slashDelimited]) {
    for (const match of normalized.matchAll(pattern)) addField(match[1], match[2], match[3], match[4]);
  }
  return fields;
}

function snake(value: string): string { return value.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase(); }
function text(value: unknown): string { return typeof value === 'string' ? value.trim() : ''; }
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null && !Array.isArray(value); }
