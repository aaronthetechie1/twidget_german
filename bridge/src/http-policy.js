import crypto from "node:crypto";

export function rateLimit(limiter, max) {
  return async (req, res, next) => {
    const key = req.ip || req.socket?.remoteAddress || "unknown";
    try {
      const result = await limiter.take(key);
      if (result.capacityReached) {
        res.setHeader("Retry-After", String(Math.ceil(result.retryMs / 1000)));
        res.status(503).json({ error: "rate_limiter_capacity_reached" });
        return;
      }
      const resetSeconds = Math.max(1, Math.ceil(result.retryMs / 1000));
      res.setHeader("RateLimit-Limit", String(max));
      res.setHeader("RateLimit-Remaining", String(Math.max(0, max - result.count)));
      res.setHeader("RateLimit-Reset", String(resetSeconds));
      if (result.count > max) {
        res.setHeader("Retry-After", String(resetSeconds));
        res.status(429).json({ error: "rate_limited" });
        return;
      }
      next();
    } catch (error) {
      // A configured shared limiter must fail closed. Falling back locally
      // during a Redis outage would multiply the request budget per replica.
      console.warn("Rate limiter unavailable:", error?.message || error);
      res.setHeader("Retry-After", "5");
      res.status(503).json({ error: "rate_limiter_unavailable" });
    }
  };
}

export function requestToken(req) {
  const authorization = String(req.get("authorization") || "");
  const bearer = /^Bearer\s+(.+)$/i.exec(authorization)?.[1];
  return String(bearer || req.get("x-rettiwt-api-key") || "").trim();
}

export function tokenMatches(received, expected) {
  const left = Buffer.from(String(received));
  const right = Buffer.from(String(expected));
  return left.length === right.length && left.length > 0 && crypto.timingSafeEqual(left, right);
}

export class ServiceBusyError extends Error {}

/** Each bridge instance owns one bounded upstream gate. Slots are released even after failure. */
export function upstreamGate(maxConcurrent) {
  let active = 0;
  return async task => {
    if (active >= maxConcurrent) throw new ServiceBusyError("Upstream concurrency limit reached");
    active += 1;
    try { return await task(); } finally { active -= 1; }
  };
}
