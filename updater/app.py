"""Optional, Compose-scoped update worker. The API proxies authenticated requests."""
from __future__ import annotations

from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
import hmac
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
from threading import Event, Thread
import time
from typing import Callable, Literal
from urllib.error import URLError
from urllib.request import urlopen

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


class NewJob(BaseModel):
    channel: Literal["stable", "dev"]
    request_id: str = Field(min_length=8, max_length=120, pattern=r"^[A-Za-z0-9_-]+$")


class Store:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, channel TEXT NOT NULL, status TEXT NOT NULL,
                stage TEXT NOT NULL, message TEXT NOT NULL,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                target_version TEXT, target_revision TEXT
            )""")
            db.execute("UPDATE jobs SET status='queued', stage='queued', message=?, updated_at=? "
                       "WHERE status IN ('queued','running')",
                       ("Recovering an interrupted update.", now()))

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def row(value: sqlite3.Row | None) -> dict | None:
        if value is None:
            return None
        result = dict(value)
        result.pop("target_revision", None)
        return result

    def get(self, job_id: str) -> dict:
        with self.connect() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        if row is None:
            raise HTTPException(404, "Update job not found.")
        return self.row(row)

    def status(self) -> dict:
        with self.connect() as db:
            active = db.execute("SELECT * FROM jobs WHERE status IN ('queued','running') "
                                "ORDER BY created_at LIMIT 1").fetchone()
            latest = db.execute("SELECT * FROM jobs ORDER BY updated_at DESC, rowid DESC LIMIT 1").fetchone()
        return {"active_job": self.row(active), "latest_job": self.row(latest)}

    def enqueue(self, request: NewJob) -> dict:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            existing = db.execute("SELECT * FROM jobs WHERE id=?", (request.request_id,)).fetchone()
            if existing:
                if existing["channel"] != request.channel:
                    raise HTTPException(409, "This request ID belongs to a different channel.")
                return self.row(existing)
            if db.execute("SELECT 1 FROM jobs WHERE status IN ('queued','running') LIMIT 1").fetchone():
                raise HTTPException(409, "Another update job is already active.")
            timestamp = now()
            db.execute("INSERT INTO jobs VALUES (?,?,?,?,?,?,?,?,?)", (
                request.request_id, request.channel, "queued", "queued", "Waiting to update.",
                timestamp, timestamp, None, None,
            ))
            self.prune(db)
        return self.get(request.request_id)

    @staticmethod
    def prune(db: sqlite3.Connection) -> None:
        db.execute("DELETE FROM jobs WHERE status IN ('succeeded','failed') AND id NOT IN "
                   "(SELECT id FROM jobs WHERE status IN ('succeeded','failed') "
                   "ORDER BY updated_at DESC, rowid DESC LIMIT 100)")

    def retry(self, job_id: str) -> dict:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT status FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None:
                raise HTTPException(404, "Update job not found.")
            if row["status"] != "failed":
                raise HTTPException(409, "Only failed jobs can be retried.")
            if db.execute("SELECT 1 FROM jobs WHERE status IN ('queued','running') LIMIT 1").fetchone():
                raise HTTPException(409, "Another update job is already active.")
            db.execute("UPDATE jobs SET status='queued', stage='queued', message=?, "
                       "updated_at=? WHERE id=?", ("Retry queued.", now(), job_id))
        return self.get(job_id)

    def update(self, job_id: str, **values) -> None:
        values["updated_at"] = now()
        with self.connect() as db:
            assignments = ", ".join(f"{name}=?" for name in values)
            db.execute(f"UPDATE jobs SET {assignments} WHERE id=?", (*values.values(), job_id))
            self.prune(db)

    def claim(self) -> dict | None:
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created_at LIMIT 1").fetchone()
            if row is None:
                return None
            db.execute("UPDATE jobs SET status='running', stage='preparing', message=?, updated_at=? WHERE id=?",
                       ("Checking the existing deployment.", now(), row["id"]))
        return self.get(row["id"])


class Engine:
    def __init__(self, store: Store, project: str, helper: str, state: Path,
                 *, health_timeout: float = 120, runner: Callable | None = None):
        self.store, self.project, self.helper, self.state = store, project, helper, state
        self.health_timeout = health_timeout
        self.runner = runner or self.run_helper
        self.stop = Event()
        self.wake = Event()
        self.thread: Thread | None = None

    def start(self) -> None:
        self.thread = Thread(target=self.loop, name="compose-updater", daemon=True)
        self.thread.start()

    def loop(self) -> None:
        while not self.stop.is_set():
            if not self.process_next():
                self.wake.wait(1)
                self.wake.clear()

    def process_next(self) -> bool:
        job = self.store.claim()
        if job is None:
            return False
        try:
            metadata = self.runner(job)
            self.store.update(job["id"], stage="verifying", message="Waiting for the updated services.",
                              target_version=metadata["version"], target_revision=metadata["revision"])
            self.wait_for_services(metadata)
            self.store.update(job["id"], status="succeeded", stage="complete", message="Services are running the selected version.")
        except Exception as error:
            self.store.update(job["id"], status="failed", stage="failed", message=str(error)[:1500] or type(error).__name__)
        return True

    def run_helper(self, job: dict) -> dict:
        with tempfile.TemporaryDirectory(prefix="update-", dir=self.state) as directory:
            result = Path(directory) / "result"
            command = ["sh", self.helper, "--project-name", self.project,
                       "--channel", job["channel"], "--require-web-update", "--result-file", str(result)]
            last_error = ""
            with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding="utf-8", errors="replace") as process:
                for line in process.stdout:
                    line = line.strip()
                    print(line, flush=True)
                    if line.startswith("Stage: "):
                        stage = line.removeprefix("Stage: ")
                        self.store.update(job["id"], stage=stage, message={
                            "pulling": "Pulling the selected images.",
                            "validating": "Checking API/Web release identities.",
                            "recreating": "Recreating the existing services.",
                        }.get(stage, stage))
                    elif line.startswith("Error: "):
                        last_error = line.removeprefix("Error: ")[:1500]
                exit_code = process.wait()
            if exit_code:
                raise RuntimeError(last_error or f"Deployment helper exited with code {exit_code}; check the updater container logs and retry.")
            try:
                version, revision, has_web = result.read_text(encoding="utf-8").splitlines()
                if not version or not revision or has_web not in ("true", "false"):
                    raise ValueError
            except (OSError, ValueError) as error:
                raise RuntimeError("Deployment helper did not return release metadata; retry with the current updater image.") from error
            return {"version": version, "revision": revision, "web": has_web == "true"}

    def wait_for_services(self, metadata: dict) -> None:
        deadline = time.monotonic() + self.health_timeout
        last_problem = "API is not ready."
        while True:
            try:
                with urlopen("http://api:8787/health", timeout=3) as response:
                    health = json.load(response)
                if (str(health.get("version", "")).removeprefix("v") != metadata["version"].removeprefix("v")
                        or health.get("revision") != metadata["revision"]):
                    raise ValueError("API has not reported the selected version and revision yet.")
                if metadata["web"]:
                    with urlopen("http://admin-web/", timeout=3) as response:
                        if response.status != 200:
                            raise ValueError("Web console is not ready.")
                return
            except (OSError, URLError, ValueError) as error:
                last_problem = str(error)
            if time.monotonic() >= deadline:
                raise RuntimeError(f"Services did not become healthy on the selected version. {last_problem} Retry the job after checking container logs.")
            time.sleep(2)


def create_app(*, state_dir: Path | None = None, token_file: Path | None = None,
               project: str | None = None, start_worker: bool = True,
               runner: Callable | None = None) -> FastAPI:
    state = state_dir or Path(os.getenv("UPDATE_STATE_DIR", "/state"))
    token_path = token_file or Path(os.getenv("UPDATE_TOKEN_FILE", "/data/.updater-token"))
    project_name = project if project is not None else os.getenv("UPDATE_PROJECT_NAME", "")

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        engine = Engine(Store(state / "jobs.sqlite3"), project_name,
                        os.getenv("UPDATE_HELPER_PATH", "/app/switch-channel.sh"), state, runner=runner)
        app.state.engine = engine
        if start_worker:
            engine.start()
        yield
        engine.stop.set()
        engine.wake.set()
        if engine.thread:
            engine.thread.join(timeout=5)

    app = FastAPI(title="Component Vault Compose updater", lifespan=lifespan)

    def authenticate(authorization: str | None = Header(default=None)) -> None:
        try:
            token = token_path.read_text(encoding="utf-8").strip()
        except OSError as error:
            raise HTTPException(503, "Updater token is unavailable; enable the API updater connection first.") from error
        if not token:
            raise HTTPException(503, "Updater token is empty; restart the API to regenerate it.")
        scheme, _, value = (authorization or "").partition(" ")
        if scheme.lower() != "bearer" or not hmac.compare_digest(value.encode(), token.encode()):
            raise HTTPException(401, "Invalid updater authorization.")

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.get("/status", dependencies=[Depends(authenticate)])
    def status():
        return app.state.engine.store.status()

    @app.post("/jobs", status_code=202, dependencies=[Depends(authenticate)])
    def create_job(request: NewJob):
        if not project_name:
            raise HTTPException(503, "UPDATE_PROJECT_NAME must identify the existing Compose project.")
        job = app.state.engine.store.enqueue(request)
        app.state.engine.wake.set()
        return job

    @app.get("/jobs/{job_id}", dependencies=[Depends(authenticate)])
    def get_job(job_id: str):
        return app.state.engine.store.get(job_id)

    @app.post("/jobs/{job_id}/retry", status_code=202, dependencies=[Depends(authenticate)])
    def retry_job(job_id: str):
        if not project_name:
            raise HTTPException(503, "UPDATE_PROJECT_NAME must identify the existing Compose project.")
        job = app.state.engine.store.retry(job_id)
        app.state.engine.wake.set()
        return job

    return app


app = create_app()
