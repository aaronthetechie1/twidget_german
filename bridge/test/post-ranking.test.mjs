import test from 'node:test';
import assert from 'node:assert/strict';
import { ownFxPostEligible, weeklyWinners } from '../src/post-ranking.js';
import { bangerScore } from '../src/banger-score.js';
const now = Date.now();
const original = { type: 'status', id: '1', author: { screen_name: 'example' }, url: 'https://x.com/example/status/1', created_timestamp: (now - 2 * 86400000) / 1000, conversation_id: '1' };
test('own replies enter best and Banger candidates but not the original-post baseline', () => {
  for (const marker of [{ replying_to: 'someone' }, { in_reply_to_status_id: 'parent' }, { in_reply_to_status_id_str: 'parent' }, { conversation_id: 'parent' }]) {
    const reply = { ...original, ...marker };
    assert.equal(ownFxPostEligible(reply, 'example', { now }), false);
    assert.equal(ownFxPostEligible(reply, 'example', { now, includeReplies: true }), true);
    assert.equal(ownFxPostEligible({ ...reply, reposted_by: 'example' }, 'example', { now, includeReplies: true }), false);
    assert.equal(ownFxPostEligible(reply, 'other', { now, includeReplies: true }), false);
    assert.equal(ownFxPostEligible(reply, 'example', { now, windowStart: now, includeReplies: true }), false);
  }
});
test('a breakout reply wins while an unseen reply cannot be worst or satisfy original maturity', () => {
  const ordinary = { engagements: 10, views: 100, likes: 10, ts: now - 2 * 86400000 };
  const quiet = { ...ordinary, engagements: 2, likes: 2 };
  const breakout = { ...ordinary, engagements: 10000, views: 1000000, likes: 10000 };
  const unseen = { ...ordinary, engagements: 0, views: 1, likes: 0 };
  assert.deepEqual(weeklyWinners([ordinary, quiet], [ordinary, quiet, breakout, unseen], now), { best: breakout, worst: quiet });
  assert.deepEqual(weeklyWinners([], [breakout, unseen], now), { best: breakout, worst: null });
  assert.equal(weeklyWinners([ordinary], [ordinary, unseen], now).worst, null);
  assert.ok(bangerScore(breakout) > bangerScore(ordinary));
});
