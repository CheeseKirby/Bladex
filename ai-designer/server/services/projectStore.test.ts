import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { ProjectStore } from './projectStore';

test('saved projects survive a new store instance', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'ai-designer-project-store-'));
  const filePath = path.join(directory, 'projects.json');
  try {
    const first = new ProjectStore(filePath);
    await first.save({ id: 'project-1', projectName: '作业票', status: 'reviewed' });

    const second = new ProjectStore(filePath);
    const restored = second.get('project-1');

    assert.equal(restored?.projectName, '作业票');
    assert.equal(restored?.status, 'reviewed');
    const persisted = JSON.parse(await readFile(filePath, 'utf8')) as { schemaVersion: number };
    assert.equal(persisted.schemaVersion, 1);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('corrupt JSON does not prevent startup and is replaced on the next save', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'ai-designer-project-store-corrupt-'));
  const filePath = path.join(directory, 'projects.json');
  try {
    await writeFile(filePath, '{broken', 'utf8');
    const store = new ProjectStore(filePath);

    assert.deepEqual(store.list(), []);
    await store.save({ id: 'recovered', projectName: '恢复项目' });

    const restored = new ProjectStore(filePath).get('recovered');
    assert.equal(restored?.projectName, '恢复项目');
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('concurrent saves are serialized without losing either project', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'ai-designer-project-store-concurrent-'));
  const filePath = path.join(directory, 'projects.json');
  try {
    const store = new ProjectStore(filePath);
    await Promise.all([
      store.save({ id: 'first', projectName: 'First' }),
      store.save({ id: 'second', projectName: 'Second' }),
    ]);

    const restored = new ProjectStore(filePath);
    assert.equal(restored.get('first')?.projectName, 'First');
    assert.equal(restored.get('second')?.projectName, 'Second');
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
