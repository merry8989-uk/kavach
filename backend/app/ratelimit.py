"""Rate limiting that survives more than one server.

The limiter used to be a module-level dict. That is correct on a single process
and quietly wrong the moment there are two: each replica counts separately, so
the effective limit is `limit × replicas`. Nobody notices until someone abuses
it, because the limit still *appears* to work in testing.

So there are two implementations behind one interface:

  * **Redis** — a shared sliding window, correct across replicas.
  * **In-process** — the fallback, used when no Redis URL is configured, and
    honest about being per-process.

`backend_status()` reports which one is live, so the answer to "is my rate limit
actually shared?" is a field you can read rather than a guess.
"""
from __future__ import annotations

import time
from dataclasses import dataclass

from .config import Settings


@dataclass
class LimitDecision:
    allowed: bool
    remaining: int
    retry_after: int
    backend: str


class RateLimiter:
    """Interface. `check` counts one request against `key`."""

    name = "unknown"

    def check(self, key: str, limit: int, window_seconds: int = 60) -> LimitDecision:
        raise NotImplementedError


class InProcessLimiter(RateLimiter):
    """Per-process. Correct for one replica, and only for one."""

    name = "in-process"

    def __init__(self) -> None:
        self._buckets: dict[str, list[float]] = {}

    def check(self, key: str, limit: int, window_seconds: int = 60) -> LimitDecision:
        now = time.time()
        window = self._buckets.setdefault(key, [])
        window[:] = [t for t in window if now - t < window_seconds]

        if len(window) >= limit:
            oldest = window[0]
            retry = max(1, int(window_seconds - (now - oldest)) + 1)
            return LimitDecision(False, 0, retry, self.name)

        window.append(now)
        return LimitDecision(True, max(0, limit - len(window)), 0, self.name)

    def reset(self) -> None:
        self._buckets.clear()


class RedisLimiter(RateLimiter):
    """A shared fixed window, which is the standard Redis rate-limit shape.

    `INCR` then `EXPIRE` on first write. Two round trips rather than a Lua script,
    which is a deliberate trade: a slightly larger window under a race is a much
    smaller problem than an unreadable implementation.
    """

    name = "redis"

    def __init__(self, client, prefix: str = "kavach:rl:") -> None:
        self._client = client
        self._prefix = prefix

    def check(self, key: str, limit: int, window_seconds: int = 60) -> LimitDecision:
        bucket = f"{self._prefix}{key}:{int(time.time() // window_seconds)}"
        try:
            count = self._client.incr(bucket)
            if count == 1:
                self._client.expire(bucket, window_seconds)
        except Exception:
            # Redis down: fail open rather than taking the whole API down with
            # it, and say so in the backend name so it is visible.
            return LimitDecision(True, limit, 0, "redis-unavailable")

        if count > limit:
            return LimitDecision(False, 0, window_seconds, self.name)
        return LimitDecision(True, max(0, limit - count), 0, self.name)


_limiter: RateLimiter | None = None


def build_limiter(settings: Settings) -> RateLimiter:
    """Redis when configured, in-process otherwise."""
    url = (settings.redis_url or "").strip()
    if not url:
        return InProcessLimiter()
    try:
        import redis  # imported lazily so the dependency is optional at runtime

        client = redis.Redis.from_url(url, socket_timeout=1.0, socket_connect_timeout=1.0)
        client.ping()
        return RedisLimiter(client)
    except Exception:
        # Configured but unreachable: start, but on the honest fallback.
        return InProcessLimiter()


def get_limiter(settings: Settings | None = None) -> RateLimiter:
    global _limiter
    if _limiter is None:
        from .config import get_settings

        _limiter = build_limiter(settings or get_settings())
    return _limiter


def set_limiter(limiter: RateLimiter | None) -> None:
    """Test seam."""
    global _limiter
    _limiter = limiter


def backend_status(settings: Settings | None = None) -> dict:
    """What is actually enforcing the limit, for a health or status endpoint."""
    limiter = get_limiter(settings)
    shared = limiter.name == "redis"
    return {
        "backend": limiter.name,
        "shared_across_replicas": shared,
        "note": (
            "Shared across every replica."
            if shared
            else "Per-process. With more than one replica the effective limit is "
            "the configured limit multiplied by the number of replicas. Set "
            "REDIS_URL to make it shared."
        ),
    }
