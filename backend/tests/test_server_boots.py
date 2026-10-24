"""Does the server actually start?

This file exists because for a long time nothing checked. The suite had 168
tests and every one of them imported the app as a library — which proves the
modules parse, and proves nothing about whether the process comes up, serves
`/health`, answers a real request, or refuses to start with development secrets
in production.

Those are the failures that reach a deployment. So this boots a real uvicorn
server in a thread on a real port and talks to it over HTTP, the same way the
container health check and the Android client do.
"""
import socket
import threading
import time
import urllib.error
import urllib.request

import pytest
import uvicorn

from app.config import Settings


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class RunningServer:
    """A real uvicorn server, in a thread, on a real port."""

    def __init__(self, env: dict[str, str]):
        import os

        self.env_backup = {k: os.environ.get(k) for k in env}
        os.environ.update(env)

        # **The settings are cached, and that has to be cleared here.**
        #
        # `get_settings` is `@lru_cache`d, which is right for a process that reads
        # its configuration once. It is wrong for a harness that boots the server
        # several times with different configuration: the second boot silently
        # reuses the first one's settings, so a test of the production refusal
        # passes or fails depending on which test ran before it.
        from app.config import get_settings
        from app import db

        get_settings.cache_clear()
        # The engine is a module-level global too, and reusing the previous
        # boot's engine made the readiness check report a database it was not
        # configured to use.
        db.reset()

        from app.main import app

        self.port = _free_port()
        config = uvicorn.Config(
            app,
            host="127.0.0.1",
            port=self.port,
            log_level="warning",
            lifespan="on",
        )
        self.server = uvicorn.Server(config)
        self.thread = threading.Thread(target=self.server.run, daemon=True)

    def __enter__(self):
        self.thread.start()
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if getattr(self.server, "started", False):
                return self
            if not self.thread.is_alive():
                # uvicorn swallows a lifespan exception rather than propagating
                # it, so the only signal that startup was refused is the thread
                # ending. Re-raised here with the reason, because "it did not
                # start" is a much worse message than the actual refusal.
                #
                # Restored first: raising from __enter__ means __exit__ never
                # runs, so a refused boot would leave ENVIRONMENT=production in
                # the process and the next file's tests would inherit it.
                self._restore()
                raise RuntimeError(
                    "the server refused to start; see the lifespan error above "
                    "(uvicorn logs it and exits rather than raising here)",
                )
            time.sleep(0.05)
        self._restore()
        raise RuntimeError("the server did not start within 20 seconds")

    def _restore(self):
        """Put the process back the way it was found.

        Restoring the environment is not enough: the settings and the engine are
        both cached, so the *next* test inherits whatever this one booted with.
        Without this the telemetry tests, which run after these, inherited a
        production configuration and failed on the safety refusal — a fixture
        breaking a different file is the worst kind of test bug, because the
        failure points at the wrong code.
        """
        import os

        for key, value in self.env_backup.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

        from app.config import get_settings
        from app import db

        get_settings.cache_clear()
        db.reset()

    def __exit__(self, *exc):
        self.server.should_exit = True
        self.thread.join(timeout=10)
        self._restore()

    def get(self, path: str, token: str | None = None):
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}")
        if token:
            request.add_header("authorization", f"Bearer {token}")
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.read().decode()


@pytest.fixture
def server():
    """Development configuration, SQLite, no external services."""
    with RunningServer(
        {
            "ENVIRONMENT": "development",
            "DATABASE_URL": "sqlite:///./test-boot.db",
            "AUTO_CREATE_TABLES": "true",
            "API_TOKEN": "test-token-for-boot-checks-only",
        },
    ) as running:
        yield running


# ------------------------------------------------------------------ liveness --

def test_the_process_comes_up_and_answers(server):
    status, body = server.get("/health")
    assert status == 200
    assert "ok" in body.lower() or "status" in body.lower()


def test_readiness_is_separate_from_liveness(server):
    # A container that answers liveness but cannot reach Postgres must be taken
    # out of rotation rather than reported healthy — so these are two endpoints,
    # and the second has to exist.
    status, _ = server.get("/health/ready")
    assert status in (200, 503)


def test_the_openapi_document_is_served(server):
    # The Android client is written against this. If it stops being served, the
    # contract is only in the code.
    status, body = server.get("/openapi.json")
    assert status == 200
    assert "paths" in body


# -------------------------------------------------------------------- routes --

def test_a_protected_endpoint_refuses_an_anonymous_caller(server):
    with pytest.raises(urllib.error.HTTPError) as caught:
        server.get("/v1/app/register")
    assert caught.value.code in (401, 403, 405, 422)


def test_an_unknown_path_is_a_404_not_a_crash(server):
    with pytest.raises(urllib.error.HTTPError) as caught:
        server.get("/definitely-not-a-route")
    assert caught.value.code == 404


# -------------------------------------------------------------- the refusals --

def test_production_refuses_to_start_on_development_secrets():
    """The check that stops a bad deployment, exercised rather than trusted."""
    problems = Settings(
        environment="production",
        api_token="dev-token",
        phone_pepper="dev-pepper-change-me",
        jwt_secret="dev-jwt-secret-change-me",
        database_url="sqlite:///./x.db",
        auto_create_tables=True,
    ).validate_for_production()

    joined = " ".join(problems)
    assert "API_TOKEN" in joined
    assert "PHONE_PEPPER" in joined
    assert "JWT_SECRET" in joined
    assert "AUTO_CREATE_TABLES" in joined
    assert "SQLite" in joined


def test_a_proper_production_configuration_is_accepted():
    """And it must not refuse a good one, or the check gets worked around."""
    problems = Settings(
        environment="production",
        api_token="a" * 40,
        phone_pepper="b" * 32,
        jwt_secret="c" * 40,
        database_url="postgresql://user:pass@db:5432/kavach",
        auto_create_tables=False,
        allow_shared_token=False,
        otp_debug_return=False,
    ).unsafe_configuration()
    assert problems == [], f"a valid production config was refused: {problems}"


def test_a_missing_sms_provider_does_not_stop_the_server():
    """The correction of a real mistake.

    A missing SMS provider used to be in the unsafe list, so a server with no
    gateway refused to start — taking caller lookup, spam reputation and business
    claims down with it, none of which touch SMS.

    An unconfigured provider is a feature that does not work, not a reason to
    refuse to run. The endpoints for it answer 503, which is what they already do
    for the AI providers.
    """
    settings = Settings(
        environment="production",
        api_token="a" * 40,
        phone_pepper="b" * 32,
        jwt_secret="c" * 40,
        database_url="postgresql://user:pass@db:5432/kavach",
        auto_create_tables=False,
        allow_shared_token=False,
        otp_debug_return=False,
    )

    assert settings.unsafe_configuration() == [], "SMS must not be an unsafe-config item"
    gaps = settings.unconfigured_features()
    assert any("SMS" in g for g in gaps), "the missing provider should still be reported"
    # Reported, not fatal.
    assert all("SMS" not in p for p in settings.unsafe_configuration())


def test_unsafe_configuration_still_stops_the_boot():
    """The safety guarantee is unchanged — only the false dependency is gone."""
    settings = Settings(
        environment="production",
        api_token="dev-token",
        phone_pepper="dev-pepper-change-me",
        jwt_secret="dev-jwt-secret-change-me",
        database_url="postgresql://user:pass@db:5432/kavach",
        auto_create_tables=False,
    )
    unsafe = settings.unsafe_configuration()
    assert any("API_TOKEN" in p for p in unsafe)
    assert any("PHONE_PEPPER" in p for p in unsafe)
    assert any("JWT_SECRET" in p for p in unsafe)


def test_the_two_lists_are_disjoint():
    """Nothing may appear in both, or a feature gap could become a hard stop."""
    settings = Settings(environment="production")
    unsafe = set(settings.unsafe_configuration())
    gaps = set(settings.unconfigured_features())
    assert not (unsafe & gaps), f"overlap: {unsafe & gaps}"
    # And the combined view is what a caller asking "is this deployable" gets.
    assert settings.validate_for_production() == settings.unsafe_configuration() + settings.unconfigured_features()


# ------------------------------------------------- production, without a gateway --

def test_the_server_boots_in_production_without_an_sms_provider():
    """The fix, exercised end to end rather than asserted about.

    This is the configuration a real deployment has on day one: proper secrets, a
    Postgres URL, no SMS gateway bought yet. It used to refuse to start, because
    the missing provider was in the same list as the published-default secrets.

    The database is not reachable here, and that is fine — `auto_create_tables` is
    off in production, so the boot does not touch it. Liveness answers, readiness
    does not, and the missing provider is reported as a gap rather than a refusal.
    """
    with RunningServer(
        {
            "ENVIRONMENT": "production",
            "API_TOKEN": "a" * 40,
            "PHONE_PEPPER": "b" * 32,
            "JWT_SECRET": "c" * 40,
            "DATABASE_URL": "postgresql+psycopg://user:pass@127.0.0.1:59999/kavach",
            "AUTO_CREATE_TABLES": "false",
            "ALLOW_SHARED_TOKEN": "false",
            "OTP_DEBUG_RETURN": "false",
            # Deliberately absent: SMS_PROVIDER, and every provider credential.
        },
    ) as running:
        status, _ = running.get("/health")
        assert status == 200, "a missing SMS provider must not stop the process"

        # Readiness is honest about the unreachable database.
        try:
            ready_status, _ = running.get("/health/ready")
        except urllib.error.HTTPError as error:
            ready_status = error.code
        assert ready_status == 503, "readiness must not claim a database it cannot reach"


def test_production_still_refuses_sqlite():
    """The safety guarantee, unchanged by the split.

    uvicorn logs a lifespan failure and exits rather than propagating it, so the
    assertion is on the refusal being *reported* — the process must not come up
    serving with a configuration that was rejected.
    """
    with pytest.raises(RuntimeError, match="refused to start"):
        with RunningServer(
            {
                "ENVIRONMENT": "production",
                "API_TOKEN": "a" * 40,
                "PHONE_PEPPER": "b" * 32,
                "JWT_SECRET": "c" * 40,
                "DATABASE_URL": "sqlite:///./nope.db",
                "AUTO_CREATE_TABLES": "true",
            },
        ):
            pytest.fail("a server with SQLite in production must not serve requests")
