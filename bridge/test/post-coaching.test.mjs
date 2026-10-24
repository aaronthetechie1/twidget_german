import assert from 'node:assert/strict';
import test from 'node:test';
import { coachingFields, coachingReplies } from '../src/post-coaching.js';

test('preserves full public text and identifies replies through explicit fields or conversation ids', () => {
  const text = 'A detailed example. '.repeat(20);
  assert.equal(coachingFields({ id: '1', text, conversation_id: '1' }).fullText, text.trim());
  assert.equal(coachingFields({ id: '2', conversation_id: '1' }).isReply, true);
  assert.equal(coachingFields({ id: '1', conversation_id: '1' }).isReply, false);
  assert.equal(coachingFields({ replying_to: 'someone' }).isReply, true);
  assert.equal(coachingFields({ text, truncated: true }).fullText, null);
  assert.equal(coachingFields({ text: 'a'.repeat(10001) }).fullText, null);
});

test('keeps replies separate and bounds their cache without mutating the input', () => {
  const candidates = [
    { isReply: false, engagements: 1000 },
    { isReply: true, engagements: 0, ts: 100 },
    ...Array.from({ length: 55 }, (_, n) => ({ isReply: true, engagements: 10 + n, ts: n })),
  ];
  const before = [...candidates];
  const replies = coachingReplies(candidates);
  assert.equal(replies.length, 50);
  assert.equal(replies[0].engagements, 0);
  assert.ok(replies.every(post => post.isReply));
  assert.deepEqual(candidates, before);
});

test('quoted tweet context stays separate, bounded and one level deep', () => {
  const post = coachingFields({ text: 'My reaction', views: 100, quote: { url: 'https://x.com/source/status/9',
    text: 'A detailed source. '.repeat(1000), created_timestamp: 1799900000, views: 999999, author: { name: 'Source', screen_name: 'source' },
    quote: { text: 'nested source' }, media: { all: [{ type: 'photo', url: 'https://example.com/source.jpg' },
      { type: 'photo', url: 'javascript:bad' }] } } });
  assert.equal(post.fullText, 'My reaction');
  assert.equal(post.quotedPost.authorUserName, 'source');
  assert.equal(post.quotedPost.ts, 1799900000000);
  assert.equal(post.quotedPost.text.length, 10000);
  assert.equal(post.quotedPost.textComplete, false);
  assert.equal(post.quotedPost.media.length, 1);
  assert.equal(post.quotedPost.views, undefined);
  assert.equal(post.quotedPost.quote, undefined);
  assert.equal(coachingFields({ quote: {} }).quotedPost, null);
});
