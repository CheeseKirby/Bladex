import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { hashPlanBundle, hashPlanContract, type PlanBundleHashMaterial, type PlanContract } from './planContract';

test('shared canonical contract fixture keeps the cross-language structural hash', () => {
  const contract = JSON.parse(readFileSync('../contracts/fixtures/canonical-plan-contract-v2.json', 'utf8')) as PlanContract;
  const expected = readFileSync('../contracts/fixtures/canonical-plan-contract-v2.sha256', 'utf8').trim();
  assert.equal(hashPlanContract(contract), expected);
});


test('shared reviewed bundle fixture keeps the cross-language bundle hash', () => {
  const material = JSON.parse(readFileSync('../contracts/fixtures/canonical-plan-bundle-v2.json', 'utf8')) as PlanBundleHashMaterial;
  const expected = readFileSync('../contracts/fixtures/canonical-plan-bundle-v2.sha256', 'utf8').trim();
  assert.equal(hashPlanBundle(material), expected);
});
