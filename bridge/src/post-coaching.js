/** Bounded public content for local coaching. Drafts are never handled by the bridge. */
export function coachingFields(status) {
  const text = String(status.text || status.raw_text?.text || '').trim();
  const id = String(status.id || '');
  const conversation = status.conversation_id == null ? '' : String(status.conversation_id);
  return {
    fullText: text.length <= 10000 && !status.truncated ? text : null,
    isReply: Boolean(status.replying_to || status.in_reply_to_status_id || status.in_reply_to_status_id_str ||
      (id && conversation && conversation !== id)),
    viewsKnown: status.views != null && Number.isFinite(Number(status.views)),
    quotedPost: quotedContext(status.quote),
  };
}

export function coachingReplies(candidates) {
  // Keep quiet replies in the baseline too. The client decides which replies
  // merit a review; selecting only successes here would bias its comparisons.
  return candidates.filter(post => post.isReply)
    .sort((a, b) => b.ts - a.ts).slice(0, 50);
}

// One bounded source level. Its counts never become the subject post's counts.
function quotedContext(source) {
  if (!source || typeof source !== 'object' || Array.isArray(source)) return null;
  const text = String(source.text || source.raw_text?.text || '').trim();
  const web = value => { try { const u = new URL(String(value || '')); return ['https:', 'http:'].includes(u.protocol) ? u.href : ''; } catch { return ''; } };
  const url = web(source.url);
  const media = (Array.isArray(source.media?.all) ? source.media.all : []).slice(0, 4).map(item => ({
    type: String(item.type || ''), url: web(item.thumbnail_url || item.url), alt: String(item.altText || item.alt || '').slice(0, 500),
    width: Math.max(0, Number(item.width) || 0), height: Math.max(0, Number(item.height) || 0),
  })).filter(item => item.url);
  if (!url && !text && !media.length) return null;
  const rawTime = Number(source.created_timestamp || source.timestamp || 0);
  const parsedTime = rawTime > 0 ? (rawTime < 1e12 ? rawTime * 1000 : rawTime) : Date.parse(source.created_at || '');
  const ts = Number.isFinite(parsedTime) && parsedTime > 0 ? parsedTime : 0;
  return { url, ts, text: text.slice(0, 10000), textComplete: text.length <= 10000 && !source.truncated,
    authorName: String(source.author?.name || ''), authorUserName: String(source.author?.screen_name || ''),
    authorAvatar: web(source.author?.avatar_url), media };
}
