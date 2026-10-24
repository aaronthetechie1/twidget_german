import test from 'node:test';
import assert from 'node:assert/strict';
import { upstreamGate, ServiceBusyError } from '../src/http-policy.js';

test('upstream gate rejects overload and returns its slot after failed work', async () => {
  const gate = upstreamGate(1);
  let release;
  const first = gate(() => new Promise(resolve => { release = resolve; }));
  await assert.rejects(gate(() => 2), ServiceBusyError);
  release(1);
  assert.equal(await first, 1);
  await assert.rejects(gate(() => { throw new Error('upstream failure'); }), /upstream failure/);
  assert.equal(await gate(() => 3), 3);
});
