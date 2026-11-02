import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from updater.app import Engine, NewJob, Store, create_app


class UpdateAppTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.token = self.root / "token"
        self.token.write_text("fixture-token", encoding="utf-8")
        self.app = create_app(state_dir=self.root / "state", token_file=self.token,
                              project="original-project", start_worker=False,
                              runner=lambda job: {"version": "v0.7.9-dev.6", "revision": "abc", "web": True})
        self.client = self.enterContext(TestClient(self.app))
        self.headers = {"Authorization": "Bearer fixture-token"}

    def post(self, job_id="request-0001", channel="dev"):
        return self.client.post("/jobs", json={"request_id": job_id, "channel": channel}, headers=self.headers)

    def test_authentication_rotation_and_missing_token(self):
        self.assertEqual(self.client.get("/status").status_code, 401)
        self.assertEqual(self.client.get("/status", headers=self.headers).json(),
                         {"active_job": None, "latest_job": None})
        self.token.write_text("rotated", encoding="utf-8")
        self.assertEqual(self.client.get("/status", headers=self.headers).status_code, 401)
        self.assertEqual(self.client.get("/status", headers={"Authorization": "Bearer rotated"}).status_code, 200)
        self.token.unlink()
        self.assertEqual(self.client.get("/status", headers=self.headers).status_code, 503)

    def test_idempotency_conflict_and_validation(self):
        self.assertEqual(self.post().status_code, 202)
        self.assertEqual(self.post().json()["id"], "request-0001")
        self.assertEqual(self.post(channel="stable").status_code, 409)
        self.assertEqual(self.post("request-0002").status_code, 409)
        self.assertEqual(self.post(channel="arbitrary").status_code, 422)
        self.assertEqual(self.post("short").status_code, 422)

    def test_success_waits_for_health_and_cannot_retry(self):
        self.post()
        engine = self.app.state.engine
        with patch.object(engine, "wait_for_services") as verify:
            engine.process_next()
        verify.assert_called_once_with({"version": "v0.7.9-dev.6", "revision": "abc", "web": True})
        job = self.client.get("/jobs/request-0001", headers=self.headers).json()
        self.assertEqual((job["status"], job["target_version"]), ("succeeded", "v0.7.9-dev.6"))
        self.assertNotIn("target_revision", job)
        self.assertEqual(self.client.post("/jobs/request-0001/retry", headers=self.headers).status_code, 409)

    def test_failure_message_and_retry(self):
        self.post()
        engine = self.app.state.engine
        engine.runner = lambda job: (_ for _ in ()).throw(RuntimeError("Docker pull failed; retry."))
        engine.process_next()
        failed = engine.store.get("request-0001")
        self.assertEqual(failed["status"], "failed")
        self.assertIn("Docker pull failed", failed["message"])
        retried = self.client.post("/jobs/request-0001/retry", json={}, headers=self.headers)
        self.assertEqual((retried.status_code, retried.json()["status"]), (202, "queued"))
        self.assertEqual(self.client.get("/jobs/missing-id", headers=self.headers).status_code, 404)

    def test_interrupted_job_is_queued_on_restart_and_history_is_bounded(self):
        path = self.root / "recovery.sqlite3"
        store = Store(path)
        store.enqueue(NewJob(request_id="interrupted-job", channel="dev"))
        store.claim()
        recovered = Store(path)
        self.assertEqual(recovered.get("interrupted-job")["status"], "queued")
        recovered.update("interrupted-job", status="failed")
        for index in range(102):
            job_id = f"history-{index:04d}"
            recovered.enqueue(NewJob(request_id=job_id, channel="dev"))
            recovered.claim()
            recovered.update(job_id, status="succeeded")
        with recovered.connect() as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 100)

    def test_health_checks_target_identity_and_web(self):
        engine = self.app.state.engine
        engine.health_timeout = 0
        metadata = {"version": "v0.7.9-dev.6", "revision": "abc", "web": True}
        def response(value, status=200):
            value = io.BytesIO(json.dumps(value).encode())
            value.status = status
            return value
        with patch("updater.app.urlopen", side_effect=[response({"version": "old", "revision": "abc"})]):
            with self.assertRaisesRegex(RuntimeError, "selected version"):
                engine.wait_for_services(metadata)
        with patch("updater.app.urlopen", side_effect=[response(metadata | {"version": "0.7.9-dev.6"}), response({})]) as http:
            engine.wait_for_services(metadata)
        self.assertEqual(http.call_args_list[1].args[0], "http://admin-web/")


if __name__ == "__main__":
    unittest.main()
