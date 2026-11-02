"""Observe the frozen upload test without changing its waits or assertions."""
from __future__ import annotations

import asyncio
from collections import deque
import functools
import hashlib
import json
import os
from pathlib import Path
import threading
import time
import types
from urllib.parse import urlsplit

import pytest

TARGET = "test_slow_frame_upload_releases_database_lock_and_revoke_prevents_store"
SOURCE_SHA = "6064ac3c1b0452e5eaa2fb0fc36e621d50deb8e4e3ddb0db2d8da5a01932aad3"
CALLER_LINES = {1404: "body_entry_setup", 1427: "revoke_while_body_blocked", 1438: "upload_after_release"}


def await_chain(coroutine: object) -> list[dict]:
    """Inspect only native awaitables, never custom properties or frame locals."""
    result = []
    seen: set[int] = set()
    for _ in range(32):
        if coroutine is None or id(coroutine) in seen:
            break
        seen.add(id(coroutine))
        if isinstance(coroutine, types.CoroutineType):
            frame, following = coroutine.cr_frame, coroutine.cr_await
        elif isinstance(coroutine, types.GeneratorType):
            frame, following = coroutine.gi_frame, coroutine.gi_yieldfrom
        elif isinstance(coroutine, types.AsyncGeneratorType):
            frame, following = coroutine.ag_frame, coroutine.ag_await
        else:
            result.append({"opaque_await_type": type(coroutine).__name__})
            break
        if frame is not None:
            result.append({"file": Path(frame.f_code.co_filename).name, "function": frame.f_code.co_name, "line": frame.f_lineno})
        coroutine = following
    return result


def make_wait_observer(original, select_stage, database, records, errors):
    """A failed observer must never replace the delegated wait's outcome."""
    def note(operation, exc):
        try:
            if len(errors) < 32:
                errors.append({"operation": operation, "error_type": type(exc).__name__})
        except BaseException:
            pass

    @functools.wraps(original)
    async def observed(fut, timeout):
        awaitable = fut
        import sys
        try:
            stage = select_stage(sys._getframe(1), timeout)
        except BaseException as exc:
            note("select_stage", exc)
            stage = None
        if stage is None:
            return await original(awaitable, timeout)
        record = {"stage": stage, "budget_seconds": timeout, "started_monotonic": time.monotonic()}
        try:
            value = await original(awaitable, timeout)
            record["outcome"] = "completed"
            return value
        except TimeoutError:
            record["outcome"] = "timeout_same_exception_reraised"
            try:
                pending = list(asyncio.all_tasks())
                record["task_count"] = len(pending)
                record["task_capture_truncated"] = len(pending) > 128
                record["tasks"] = [{"task": f"snapshot-task-{index}", "done": task.done(), "cancelled": task.cancelled(), "await_chain": await_chain(task.get_coro())} for index, task in enumerate(pending[:128], 1)]
                record["readonly_pg_ring_at_timeout"] = database.snapshot()
            except BaseException as exc:
                note("timeout_snapshot", exc)
            raise
        except asyncio.CancelledError:
            record["outcome"] = "cancelled_same_exception_reraised"
            raise
        except BaseException:
            record["outcome"] = "original_exception_reraised"
            raise
        finally:
            try:
                record["ended_monotonic"] = time.monotonic()
                if len(records) < 8:
                    records.append(record)
            except BaseException as exc:
                # No filesystem operation occurs between the original waits.
                note("buffer_record", exc)
    return observed


class ReadOnlyDatabaseObserver:
    def __init__(self) -> None:
        self.stop = threading.Event()
        self.samples: deque[dict] = deque(maxlen=128)
        self.lock = threading.Lock()
        self.aliases: dict[int, str] = {}
        self.thread = threading.Thread(target=self.observe, daemon=True)

    def snapshot(self) -> list[dict]:
        with self.lock:
            return list(self.samples)

    def append(self, value: dict) -> None:
        with self.lock:
            self.samples.append(value)

    def observe(self) -> None:
        try:
            import psycopg
            raw = os.environ["DATABASE_URL"].replace("postgresql+psycopg://", "postgresql://", 1)
            location = urlsplit(raw)
            assert location.hostname in {"localhost", "127.0.0.1"} and location.path == "/erp_test"
            with psycopg.connect(raw, autocommit=True, connect_timeout=3, options="-c default_transaction_read_only=on -c statement_timeout=750 -c application_name=dcompany_readonly_diagnostic") as connection:
                assert connection.execute("SHOW default_transaction_read_only").fetchone() == ("on",)
                while not self.stop.is_set():
                    began = time.monotonic()
                    rows = connection.execute("""
                        SELECT pid, state, wait_event_type, wait_event,
                               EXTRACT(EPOCH FROM (clock_timestamp()-query_start))::float8,
                               cardinality(pg_blocking_pids(pid))
                        FROM pg_stat_activity
                        WHERE datname=current_database() AND pid<>pg_backend_pid()
                        ORDER BY pid LIMIT 33
                    """).fetchall()
                    anonymous = []
                    for pid, state, wait_type, wait_event, age, blockers in rows[:32]:
                        alias = self.aliases.setdefault(pid, f"connection-{len(self.aliases)+1}")
                        anonymous.append({"connection": alias, "state": state, "wait_type": wait_type, "wait_event": wait_event, "query_age_seconds": age, "blocker_count": blockers})
                    self.append({"monotonic": began, "sample_duration_seconds": time.monotonic()-began, "connections": anonymous, "connections_truncated": len(rows) > 32})
                    self.stop.wait(0.1)
        except Exception as exc:
            # Exception contents/DSNs can contain credentials; retain only the type.
            self.append({"observer_error_type": type(exc).__name__, "monotonic": time.monotonic()})


@pytest.hookimpl(wrapper=True)
def pytest_runtest_call(item):
    if item.name != TARGET:
        return (yield)
    assert os.environ.get("GITHUB_ACTIONS") == "true"
    source = Path(str(item.path))
    assert hashlib.sha256(source.read_bytes()).hexdigest() == SOURCE_SHA
    output = Path(os.environ["D_COMPANY_DIAGNOSTIC_OUTPUT"]).resolve()
    temporary = Path(os.environ["RUNNER_TEMP"]).resolve()
    assert output.is_relative_to(temporary)
    database = ReadOnlyDatabaseObserver()
    original = asyncio.wait_for
    records: list[dict] = []
    observer_errors: list[dict] = []
    try:
        output.mkdir(mode=0o700, exist_ok=True)
    except OSError as exc:
        observer_errors.append({"operation": "create_output", "error_type": type(exc).__name__})

    def select_stage(caller, timeout):
        if caller.f_code.co_name == TARGET and Path(caller.f_code.co_filename).resolve() == source.resolve() and caller.f_lineno in CALLER_LINES and timeout == 2:
            return CALLER_LINES[caller.f_lineno]
        return None

    observe_wait = make_wait_observer(original, select_stage, database, records, observer_errors)
    observer_started = False
    try:
        database.thread.start()
        observer_started = True
    except Exception as exc:
        observer_errors.append({"operation": "start_observer", "error_type": type(exc).__name__})
    asyncio.wait_for = observe_wait
    try:
        return (yield)
    finally:
        asyncio.wait_for = original
        try:
            database.stop.set()
            if observer_started:
                database.thread.join(timeout=5)
            payload = {"waits": records, "readonly_pg_ring": database.snapshot(), "observer_started": observer_started, "observer_thread_stopped": not database.thread.is_alive(), "no_query_text_arguments_or_locals": True, "post_wait_for_snapshot_can_follow_request_cancellation": True, "observer_errors": observer_errors}
            encoded = json.dumps(payload, indent=2) + "\n"
            if len(encoded.encode()) > 2 * 1024 * 1024:
                encoded = json.dumps({"observer_output_truncated": True, "observer_errors": observer_errors}) + "\n"
            (output / "upload-observations.json").write_text(encoded)
        except BaseException as exc:
            # Emit a type-only fallback; never raise over the original test outcome.
            try:
                print("D58 diagnostic teardown unavailable: " + type(exc).__name__)
            except BaseException:
                pass
