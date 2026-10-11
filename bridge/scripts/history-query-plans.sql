-- Read-only plan collection for a representative completed scan. Use psql:
-- psql "$READ_ONLY_DATABASE_URL" -v username=account -f scripts/history-query-plans.sql
-- No index changes should be made without comparing realistic scan/page sizes and write costs.
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT scan_id FROM twidget_top_follower_scans
WHERE username = :'username' AND status = 'complete'
ORDER BY completed_at DESC LIMIT 1;

EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT follower_id, follower_username, display_name, followers, scan_index
FROM twidget_top_followers
WHERE scan_id = (
  SELECT scan_id FROM twidget_top_follower_scans WHERE username = :'username' AND status = 'complete'
  ORDER BY completed_at DESC LIMIT 1
)
ORDER BY followers DESC, lower(display_name), lower(follower_username), follower_id, scan_index
OFFSET 0 LIMIT 250;
