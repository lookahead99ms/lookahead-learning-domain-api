"""Image tests for the Fargate /tmp mount (DLV-968).

On AWS Fargate the task definition mounts an empty bind volume at /tmp. ECS copies the
owner and permissions from the image only for paths the image declares with VOLUME; any
other mount arrives root-owned 0755. The Domain API runs as UID 10001, so without
``VOLUME ["/tmp"]`` Tomcat exits with "Unable to create tempDir ... /tmp/tomcat.8443.*"
before the database is ever touched. These tests inspect the built image, prove an
anonymous /tmp volume inherits 10001:10001 and the conventional sticky 1777 mode (so an
unparameterised local tmpfs that inherits the directory mode stays writable), reproduce the root-owned mount
failure, and confirm an owned mount gets past tempDir creation.

Opt in with DOMAIN_IMAGE_TEST_IMAGE=<image reference>; without it the whole class skips.
"""
import json
import os
import subprocess
import time
import unittest

IMAGE = os.environ.get("DOMAIN_IMAGE_TEST_IMAGE", "")
HARDENING = ["--read-only", "--network", "none", "--cap-drop", "ALL", "--security-opt", "no-new-privileges"]
STARTUP_WAIT_SECONDS = 90
TEMPDIR_FAILURE = "Unable to create tempDir"


def docker(*args, timeout=60, check=True):
    result = subprocess.run(["docker", *args], text=True, capture_output=True, timeout=timeout)
    if check and result.returncode != 0:
        raise AssertionError(f"docker {' '.join(args)} failed ({result.returncode}):\n{result.stdout}{result.stderr}")
    return result


@unittest.skipUnless(IMAGE, "set DOMAIN_IMAGE_TEST_IMAGE=<image> to run the Domain image /tmp volume tests")
class ImageTmpVolumeTest(unittest.TestCase):
    def start(self, *run_args):
        """Start the real entrypoint detached, bound to loopback, and remove the container afterwards.

        LOOKAHEAD_ENVIRONMENT has no default and is read by @ConditionalOnProperty during component
        scanning, which runs before Tomcat creates its tempDir; without it startup fails too early.
        """
        container = docker("run", "--detach", *HARDENING, "--env", "LOOKAHEAD_BIND_ADDRESS=127.0.0.1",
                           "--env", "LOOKAHEAD_ENVIRONMENT=local", *run_args, IMAGE).stdout.strip()
        self.addCleanup(docker, "rm", "--force", "--volumes", container, check=False)
        return container

    def wait_for_exit(self, container):
        """Poll until the container stops; return (still_running, exit_code, combined_logs)."""
        deadline = time.monotonic() + STARTUP_WAIT_SECONDS
        running = True
        while running and time.monotonic() < deadline:
            time.sleep(2)
            running = docker("inspect", "--format", "{{.State.Running}}", container).stdout.strip() == "true"
        exit_code = int(docker("inspect", "--format", "{{.State.ExitCode}}", container).stdout.strip())
        logs = docker("logs", container)
        return running, exit_code, logs.stdout + logs.stderr

    def test_image_declares_tmp_volume_and_runs_as_lookahead(self):
        config = json.loads(docker("image", "inspect", "--format", "{{json .Config}}", IMAGE).stdout)
        self.assertEqual({"/tmp": {}}, config.get("Volumes"))
        self.assertEqual("10001:10001", config.get("User"))

    def test_anonymous_tmp_volume_inherits_owner_and_mode(self):
        result = docker("run", "--rm", *HARDENING, "--entrypoint", "sh", IMAGE,
                        "-c", "stat -c %u:%g:%a /tmp && touch /tmp/probe", timeout=120, check=False)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("10001:10001:1777", result.stdout.splitlines()[0])

    def test_root_owned_tmp_mount_reproduces_fargate_tempdir_failure(self):
        container = self.start("--mount", "type=tmpfs,destination=/tmp,tmpfs-mode=0755")
        running, exit_code, logs = self.wait_for_exit(container)
        self.assertFalse(running, f"container still running after {STARTUP_WAIT_SECONDS}s:\n{logs}")
        self.assertNotEqual(0, exit_code, logs)
        self.assertIn("tempdir", logs.lower(), logs)

    def test_owned_tmp_mount_gets_past_tempdir_creation(self):
        container = self.start("--tmpfs", "/tmp:rw,noexec,nosuid,size=64m,mode=0700,uid=10001,gid=10001")
        _running, _exit_code, logs = self.wait_for_exit(container)
        self.assertNotIn(TEMPDIR_FAILURE, logs, logs)


if __name__ == "__main__":
    unittest.main()
