import test from 'node:test';
import assert from 'node:assert/strict';
import { watchIntervals } from '../src/monitoring.ts';

test('caps delayed heartbeats and attributes each second to the Dhaka day', () => {
  const last = new Date('2026-10-06T17:59:40Z');
  const now = new Date('2026-10-06T18:00:20Z');
  assert.deepEqual(watchIntervals(last, now, true), [
    { day: '2026-10-06', seconds: 20 }, { day: '2026-10-07', seconds: 20 }
  ]);
  assert.deepEqual(watchIntervals(last, new Date('2026-10-06T18:10:00Z'), true), [{ day: '2026-10-07', seconds: 60 }]);
});
test('paused, repeated or backwards heartbeats do not inflate watch time', () => {
  const now = new Date('2026-10-06T18:00:00Z');
  assert.deepEqual(watchIntervals(now, now, true), []);
  assert.deepEqual(watchIntervals(now, new Date(now.getTime() - 1000), true), []);
  assert.deepEqual(watchIntervals(now, new Date(now.getTime() + 60000), false), []);
});
