import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { createApp } from '../app';
import { ProjectStore } from '../services/projectStore';

async function withServer(store: ProjectStore, run: (baseUrl: string) => Promise<void>): Promise<void> {
  const server = createApp({ projectStore: store }).listen(0, '127.0.0.1');
  await new Promise<void>((resolve) => server.once('listening', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('No TCP address');
  try {
    await run(`http://127.0.0.1:${address.port}`);
  } finally {
    await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

function adminHeaders(): Record<string, string> {
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (process.env.BFF_ADMIN_TOKEN) headers['X-Admin-Token'] = process.env.BFF_ADMIN_TOKEN;
  return headers;
}

test('plan API restores saved design state after a BFF restart', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'ai-designer-plan-route-'));
  const filePath = path.join(directory, 'projects.json');
  try {
    await withServer(new ProjectStore(filePath), async (baseUrl) => {
      const response = await fetch(`${baseUrl}/api/plans/save`, {
        method: 'POST',
        headers: adminHeaders(),
        body: JSON.stringify({ id: 'restart-project', projectName: '重启恢复', status: 'reviewed' }),
      });
      assert.equal(response.status, 200);
    });

    await withServer(new ProjectStore(filePath), async (baseUrl) => {
      const response = await fetch(`${baseUrl}/api/plans/restart-project`, { headers: adminHeaders() });
      assert.equal(response.status, 200);
      const body = await response.json() as { data: { projectName: string; status: string } };
      assert.equal(body.data.projectName, '重启恢复');
      assert.equal(body.data.status, 'reviewed');
    });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
