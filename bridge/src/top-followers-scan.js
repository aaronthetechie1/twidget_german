import { TopFollowersProviderError } from "./top-followers-provider.js";

export function topFollowersFailureCode(error) {
  if (error instanceof TopFollowersProviderError) {
    if (error.status === 401 || error.status === 403) return "provider_key_rejected";
    if (error.status === 402) return "provider_credit_empty";
    if (error.status === 400) return "invalid_username";
    if (error.status === 404) return "followers_unavailable";
    if (error.status === 429) return "provider_rate_limited";
    return "provider_unavailable";
  }
  if (["TimeoutError", "AbortError"].includes(error?.name)) return "provider_timeout";
  return String(error?.message || "scan_failed").slice(0, 160);
}

export async function runTopFollowersScan({ repository, key, scan: initial, fetchPage, beforeRequest = async () => {}, onFailure = async () => {}, maxPages = 6250, overlap = 100 }) {
  let scan = initial;
  try {
    while (scan.pages < maxPages) {
      await beforeRequest();
      const page = await fetchPage(scan.cursor);
      if (!page.users.length) {
        if (!scan.scanned) throw new Error("top_followers_unavailable");
        return await repository.completeTopFollowersScan(key, scan.scanId);
      }
      if (page.nextCursor && page.nextCursor === scan.cursor) throw new Error("top_followers_pagination_stalled");
      scan = await repository.appendTopFollowersPage(key, scan.scanId, page.users, page.nextCursor);
      if (!scan) return null;
      if (!page.nextCursor || (scan.mode === "incremental" && scan.processed >= scan.targetRows && scan.knownStreak >= overlap)) {
        return await repository.completeTopFollowersScan(key, scan.scanId);
      }
    }
    throw new Error("top_followers_page_limit_reached");
  } catch (error) {
    const code = topFollowersFailureCode(error);
    await onFailure(code);
    return await repository.failTopFollowersScan(key, scan.scanId, code);
  }
}
