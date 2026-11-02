from __future__ import annotations

import json
import os
from pathlib import Path
import secrets
import tempfile
from typing import Literal
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from ..auth import require_admin
from ..build_info import BUILD_VERSION
from ..config import database_path


router = APIRouter(
    prefix="/admin-api/server-update", tags=["server-update"],
    dependencies=[Depends(require_admin)],
)


class UpdateStart(BaseModel):
    model_config = ConfigDict(extra="forbid")

    channel: Literal["stable", "dev"]
    request_id: str = Field(min_length=8, max_length=120)


class UpdateJob(BaseModel):
    id: str
    channel: Literal["stable", "dev"]
    status: Literal["queued", "running", "succeeded", "failed"]
    stage: str
    message: str
    created_at: str
    updated_at: str
    target_version: str | None = None


class UpdateStatus(BaseModel):
    enabled: bool
    current_version: str
    current_channel: Literal["stable", "dev", "unknown"]
    active_job: UpdateJob | None = None
    latest_job: UpdateJob | None = None


def _updater_url() -> str:
    return os.getenv("UPDATER_URL", "").strip().rstrip("/")


def _updater_token() -> str:
    configured = os.getenv("UPDATE_TOKEN_FILE", "").strip()
    path = Path(configured) if configured else database_path().parent / ".updater-token"
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix=".updater-token-", dir=path.parent)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                stream.write(secrets.token_urlsafe(32))
                stream.flush()
                os.fsync(stream.fileno())
            try:
                # Publish a complete file once; another API worker may have won the race.
                os.link(temporary, path)
            except FileExistsError:
                pass
        finally:
            Path(temporary).unlink(missing_ok=True)
    token = path.read_text(encoding="utf-8").strip()
    if not token:
        raise OSError("Updater token file is empty.")
    return token


def _proxy(path: str, body: dict | None = None) -> object:
    base = _updater_url()
    if not base:
        raise HTTPException(status_code=503, detail="Server updater is not configured.")
    try:
        token = _updater_token()
        request = Request(
            f"{base}{path}",
            data=json.dumps(body).encode("utf-8") if body is not None else None,
            headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
            method="POST" if body is not None else "GET",
        )
        with urlopen(request, timeout=5 if body is not None else 3) as response:
            return json.load(response)
    except HTTPError as error:
        messages = {
            404: "Update job not found.",
            409: "An update is already running or this request conflicts with an existing job.",
            422: "The update service rejected this request.",
        }
        raise HTTPException(
            status_code=error.code if error.code in messages else 503,
            detail=messages.get(error.code, "The update service is unavailable. Try again shortly."),
        ) from error
    except (OSError, URLError, ValueError) as error:
        raise HTTPException(
            status_code=503, detail="The update service is unavailable. Try again shortly.",
        ) from error


def _job(path: str, body: dict | None = None) -> UpdateJob:
    try:
        return UpdateJob.model_validate(_proxy(path, body))
    except ValidationError as error:
        raise HTTPException(status_code=502, detail="The update service returned an invalid job.") from error


@router.get("/status", response_model=UpdateStatus)
def update_status() -> UpdateStatus:
    current_channel = (
        "unknown" if BUILD_VERSION == "source" else "dev" if "-dev." in BUILD_VERSION else "stable"
    )
    enabled = bool(_updater_url())
    result = UpdateStatus(enabled=enabled, current_version=BUILD_VERSION, current_channel=current_channel)
    if enabled:
        try:
            payload = _proxy("/status")
            if not isinstance(payload, dict):
                raise ValueError("Invalid updater status.")
            result.active_job = UpdateJob.model_validate(payload["active_job"]) if payload.get("active_job") else None
            result.latest_job = UpdateJob.model_validate(payload["latest_job"]) if payload.get("latest_job") else None
        except (ValidationError, ValueError) as error:
            raise HTTPException(status_code=502, detail="The update service returned an invalid status.") from error
    return result


@router.post("/jobs", response_model=UpdateJob, status_code=202)
def start_update(draft: UpdateStart) -> UpdateJob:
    return _job("/jobs", draft.model_dump())


@router.get("/jobs/{job_id}", response_model=UpdateJob)
def get_update_job(job_id: str) -> UpdateJob:
    return _job(f"/jobs/{quote(job_id, safe='')}")


@router.post("/jobs/{job_id}/retry", response_model=UpdateJob, status_code=202)
def retry_update(job_id: str) -> UpdateJob:
    return _job(f"/jobs/{quote(job_id, safe='')}/retry", {})
