import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { handler } from '../src/http.ts';

test('unconfigured server returns 503 and never grants application access', async () => {
  const previous = process.env.DATABASE_URL;
  delete process.env.DATABASE_URL;
  const server = createServer(handler);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  try {
    const addr = server.address();
    if (!addr || typeof addr === 'string') throw new Error('No address');
    const response = await fetch(`http://127.0.0.1:${addr.port}/api/session`, { method: 'POST', headers: {'Content-Type':'application/json'}, body:'{}' });
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: 'Service is not configured' });
    assert.equal(response.headers.get('cache-control'), 'no-store');
  } finally { await new Promise<void>(resolve => server.close(() => resolve())); if (previous) process.env.DATABASE_URL = previous; }
});
