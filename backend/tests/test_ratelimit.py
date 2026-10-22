"""Rate limiting must be shared once there is more than one server.

The old limiter was a module-level dict, so each replica counted separately and
the effective limit was `limit × replicas`. That is the failure this pins down.
"""
import pytest

from app.config import Settings
from app.ratelimit import (
    InProcessLimiter,
    RedisLimiter,
    backend_status,
    build_limiter,
    get_limiter,
    set_limiter,
)


class FakeRedis:
    """Just enough of the Redis surface the limiter uses."""

    def __init__(self) -> None:
        self.store: dict[str, int] = {}
        self.expires: dict[str, int] = {}
        self.calls = 0

    def incr(self, key: str) -> int:
        self.calls += 1
        self.store[key] = self.store.get(key, 0) + 1
        return self.store[key]

    def expire(self, key: str, seconds: int) -> None:
        self.expires[key] = seconds

    def ping(self) -> bool:
        return True


class BrokenRedis(FakeRedis):
    def incr(self, key: str) -> int:
        raise ConnectionError("redis is down")


# ------------------------------------------------------------- in-process --

def test_the_in_process_limiter_allows_up_to_the_limit():
    limiter = InProcessLimiter()
    for _ in range(3):
        assert limiter.check("k", limit=3).allowed
    assert not limiter.check("k", limit=3).allowed


def test_the_in_process_limiter_counts_keys_separately():
    limiter = InProcessLimiter()
    assert limiter.check("a", limit=1).allowed
    assert limiter.check("b", limit=1).allowed


def test_a_refusal_says_how_long_to_wait():
    limiter = InProcessLimiter()
    limiter.check("k", limit=1)
    decision = limiter.check("k", limit=1)
    assert not decision.allowed
    assert decision.retry_after >= 1


# ------------------------------------------------------------------- redis --

def test_the_redis_limiter_counts_in_a_shared_store():
    client = FakeRedis()
    limiter = RedisLimiter(client)
    for _ in range(2):
        assert limiter.check("k", limit=2).allowed
    assert not limiter.check("k", limit=2).allowed


def test_two_redis_limiters_share_one_budget():
    """The point of the whole exercise: two replicas, one limit."""
    client = FakeRedis()
    replica_a = RedisLimiter(client)
    replica_b = RedisLimiter(client)

    assert replica_a.check("same-caller", limit=2).allowed
    assert replica_b.check("same-caller", limit=2).allowed
    # The third request is refused even though it hits the other replica.
    assert not replica_a.check("same-caller", limit=2).allowed


def test_the_window_expires_in_redis():
    client = FakeRedis()
    RedisLimiter(client).check("k", limit=5, window_seconds=30)
    assert list(client.expires.values()) == [30]


def test_a_broken_redis_fails_open_and_says_so():
    limiter = RedisLimiter(BrokenRedis())
    decision = limiter.check("k", limit=1)
    # Refusing every request because the cache is down would be worse than
    # briefly exceeding the limit.
    assert decision.allowed
    assert decision.backend == "redis-unavailable"


# ---------------------------------------------------------------- selection --

def test_no_redis_url_gives_the_in_process_limiter():
    limiter = build_limiter(Settings(redis_url=""))
    assert isinstance(limiter, InProcessLimiter)


def test_an_unreachable_redis_falls_back_rather_than_failing_to_start():
    # A bad URL must not stop the API booting.
    limiter = build_limiter(Settings(redis_url="redis://127.0.0.1:1/0"))
    assert isinstance(limiter, InProcessLimiter)


# ------------------------------------------------------------------ status --

def test_the_status_says_when_the_limit_is_not_shared():
    set_limiter(InProcessLimiter())
    try:
        status = backend_status()
        assert status["shared_across_replicas"] is False
        # And it explains the consequence rather than leaving it implicit.
        assert "replicas" in status["note"]
    finally:
        set_limiter(None)


def test_the_status_says_when_the_limit_is_shared():
    set_limiter(RedisLimiter(FakeRedis()))
    try:
        assert backend_status()["shared_across_replicas"] is True
    finally:
        set_limiter(None)


def test_the_health_endpoint_reports_the_limiter(client):
    response = client.get("/health/ready")
    assert response.status_code == 200
    assert "rate_limit" in response.json()
    assert "shared_across_replicas" in response.json()["rate_limit"]
