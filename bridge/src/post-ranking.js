/** Replies may win best-post rankings, but never enter the original-post baseline. */
export function ownFxPostEligible(status, username, { windowStart = 1, now = Date.now(), includeReplies = false } = {}) {
  if (!status || status.type !== 'status') return false;
  if (String(status.author?.screen_name || '').toLowerCase() !== username.toLowerCase()) return false;
  if (status.reposted_by) return false;
  const id = String(status.id || '');
  const reply = status.replying_to || status.in_reply_to_status_id || status.in_reply_to_status_id_str ||
    (id && status.conversation_id != null && String(status.conversation_id) !== id);
  if (!includeReplies && reply) return false;
  const ts = Number(status.created_timestamp || 0) * 1000 || Date.parse(status.created_at || '') || 0;
  return ts > 0 && ts >= windowStart && ts <= now + 5 * 60 * 1000 &&
    String(status.url || '').toLowerCase().includes(`/${username.toLowerCase()}/status/`);
}

export function weeklyWinners(originals, candidates, now = Date.now()) {
  const mature = originals.filter(post => post.ts > 0 && now - post.ts >= 24 * 60 * 60 * 1000);
  return {
    best: candidates.reduce((best, post) => !best || post.engagements > best.engagements ? post : best, null),
    worst: mature.length < 2 ? null : mature.reduce((worst, post) => !worst || post.engagements < worst.engagements ? post : worst, null),
  };
}
