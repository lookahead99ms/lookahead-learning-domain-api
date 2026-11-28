"""Exercise the real Unix wrapper offline with disposable downloads and caches."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import tomllib
import unittest
import xml.etree.ElementTree as ET
import zipfile

repoRoot = Path(__file__).resolve().parents[3]


class MavenWrapperTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.binDir = self.root / "bin"
        self.binDir.mkdir()
        self.project = self.root / "project"
        (self.project / ".mvn/wrapper").mkdir(parents=True)
        shutil.copy2(repoRoot / "mvnw", self.project / "mvnw")
        self.archive = self.root / "maven.zip"
        entry = zipfile.ZipInfo("apache-maven-3.9.16/bin/mvn")
        entry.external_attr = 0o100755 << 16
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr(entry, '#!/bin/sh\nprintf "MAVEN_EXECUTED:%s\\n" "$*"\n')
        self.properties = self.project / ".mvn/wrapper/maven-wrapper.properties"
        self.properties.write_text(
            "distributionUrl=https://example.invalid/apache-maven-3.9.16-bin.zip\n"
            "distributionSha256Sum="
            f"{hashlib.sha256(self.archive.read_bytes()).hexdigest()}\n"
        )
        # Isolate tool discovery, including systems where wget is not installed.
        for name in (
            "uname",
            "dirname",
            "basename",
            "tr",
            "mktemp",
            "mkdir",
            "rm",
            "unzip",
            "mv",
            "chmod",
            "shasum",
            "sha256sum",
        ):
            executable = shutil.which(name)
            if executable:
                (self.binDir / name).symlink_to(executable)
        for name in ("wget", "curl"):
            self.writeExecutable(
                name,
                """#!/bin/sh
tool="${0##*/}"
printf '%s\\n' "$tool:$*" >> "$WRAPPER_FIXTURE_ROOT/calls"
countFile="$WRAPPER_FIXTURE_ROOT/$tool.count"
count=0
[ ! -f "$countFile" ] || read -r count < "$countFile"
count=$((count + 1))
printf '%s\\n' "$count" > "$countFile"
if [ "$tool" = wget ]; then failures="$WGET_FAILURES"; \
else failures="$CURL_FAILURES"; fi
while [ "$#" -gt 0 ]; do
  case "$1" in -O|-o) shift; target="$1" ;; esac
  shift
done
if [ "$count" -le "$failures" ]; then
  printf 'partial download' > "$target"
  exit 1
fi
exec /bin/cp "$WRAPPER_FIXTURE_ROOT/maven.zip" "$target"
""",
            )
        self.writeExecutable("sleep", "#!/bin/sh\nexit 0\n")
        self.env = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("MVNW_")
        }
        self.env.update(
            PATH=str(self.binDir),
            MAVEN_USER_HOME=str(self.root / "cache"),
            WRAPPER_FIXTURE_ROOT=str(self.root),
            WGET_FAILURES="0",
            CURL_FAILURES="0",
        )

    def writeExecutable(self, name, source):
        path = self.binDir / name
        path.write_text(source)
        path.chmod(0o755)

    def runWrapper(self):
        return subprocess.run(
            ["/bin/sh", "./mvnw", "--batch-mode", "--version"],
            cwd=self.project,
            env=self.env,
            text=True,
            capture_output=True,
            timeout=15,
        )

    def calls(self):
        return (self.root / "calls").read_text().splitlines()

    def assertSuccess(self, result):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("MAVEN_EXECUTED:--batch-mode --version", result.stdout)

    def test_success_downloads_once_and_reuses_cache(self):
        self.assertSuccess(self.runWrapper())
        self.assertSuccess(self.runWrapper())
        self.assertEqual(1, len(self.calls()))

    def test_wget_recovers_from_partial_download_on_third_attempt(self):
        self.env["WGET_FAILURES"] = "2"
        self.assertSuccess(self.runWrapper())
        self.assertEqual(3, len(self.calls()))
        self.assertTrue(all(call.startswith("wget:") for call in self.calls()))
        self.assertTrue(all("--tries=1 --timeout=30" in call for call in self.calls()))

    def test_failed_wget_falls_back_to_curl_with_retries(self):
        self.env.update(WGET_FAILURES="3", CURL_FAILURES="1")
        self.assertSuccess(self.runWrapper())
        self.assertEqual(
            ["wget"] * 3 + ["curl"] * 2,
            [call.split(":", 1)[0] for call in self.calls()],
        )
        self.assertIn("--connect-timeout 15 --max-time 120 -f -L", self.calls()[-1])

    def test_curl_only_installation(self):
        (self.binDir / "wget").unlink()
        self.env["CURL_FAILURES"] = "2"
        self.assertSuccess(self.runWrapper())
        self.assertEqual(["curl"] * 3, [call.split(":", 1)[0] for call in self.calls()])

    def test_wget_only_failure_is_bounded(self):
        (self.binDir / "curl").unlink()
        self.env["WGET_FAILURES"] = "99"
        result = self.runWrapper()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(3, len(self.calls()))
        self.assertIn("after bounded download retries", result.stderr)
        self.assertNotIn("MAVEN_EXECUTED", result.stdout)

    def test_both_downloaders_fail_without_installing_partial_archive(self):
        self.env.update(WGET_FAILURES="99", CURL_FAILURES="99")
        result = self.runWrapper()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(6, len(self.calls()))
        self.assertIn("after bounded download retries", result.stderr)
        self.assertEqual([], list((self.root / "cache").rglob("mvn")))

    def test_checksum_failure_never_retries_falls_back_or_executes(self):
        self.archive.write_bytes(b"corrupted archive")
        result = self.runWrapper()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Failed to validate Maven distribution SHA-256", result.stderr)
        self.assertEqual(1, len(self.calls()))
        self.assertNotIn("MAVEN_EXECUTED", result.stdout)
        self.assertEqual([], list((self.root / "cache").rglob("mvn")))

    def test_maven_failure_is_returned_without_retrying(self):
        entry = zipfile.ZipInfo("apache-maven-3.9.16/bin/mvn")
        entry.external_attr = 0o100755 << 16
        with zipfile.ZipFile(self.archive, "w") as archive:
            archive.writestr(entry, "#!/bin/sh\necho MAVEN_FAILED\nexit 7\n")
        url = self.properties.read_text().splitlines()[0]
        self.properties.write_text(
            f"{url}\ndistributionSha256Sum="
            f"{hashlib.sha256(self.archive.read_bytes()).hexdigest()}\n"
        )
        result = self.runWrapper()
        self.assertEqual(7, result.returncode)
        self.assertEqual(1, result.stdout.count("MAVEN_FAILED"))
        self.assertEqual(1, len(self.calls()))

    def test_ci_runs_regressions_before_maven_coverage(self):
        steps = json.loads((repoRoot / ".github/workflows/ci.yml").read_text())["jobs"][
            "verify"
        ]["steps"]
        commands = [step.get("run", "") for step in steps]
        regression = (
            "python3 -m unittest discover -s .github/pipeline/build"
            " -p 'test_maven_wrapper.py'"
        )
        coverage = "./mvnw --batch-mode --no-transfer-progress -Pcoverage clean verify"
        self.assertLess(commands.index(regression), commands.index(coverage))


class CodeStyleGateTest(unittest.TestCase):
    """DLV-1026: Java follows Google Java Style (Spotless with google-java-format in
    verify) and the pipeline Python follows PEP 8 (Ruff in CI), each tool pinned."""

    def test_ci_checks_python_style_with_hash_pinned_ruff_before_maven(self):
        steps = json.loads((repoRoot / ".github/workflows/ci.yml").read_text())["jobs"][
            "verify"
        ]["steps"]
        commands = [step.get("run", "") for step in steps]
        style = next(c for c in commands if "requirements-style.txt" in c)
        self.assertIn(
            "--no-deps --require-hashes"
            " -r .github/pipeline/shared/requirements-style.txt",
            style,
        )
        self.assertIn('"$RUNNER_TEMP/style/bin/ruff" format --check .', style)
        self.assertIn('"$RUNNER_TEMP/style/bin/ruff" check .', style)
        coverage = "./mvnw --batch-mode --no-transfer-progress -Pcoverage clean verify"
        self.assertLess(commands.index(style), commands.index(coverage))

    def test_ruff_is_pinned_by_version_and_hash(self):
        text = (repoRoot / ".github/pipeline/shared/requirements-style.txt").read_text()
        requirements = [line for line in text.splitlines() if not line.startswith("#")]
        self.assertRegex(requirements[0], r"^ruff==\d+\.\d+\.\d+ \\$")
        hashes = re.findall(r"--hash=sha256:([0-9a-f]{64})", text)
        self.assertEqual(len(requirements) - 1, len(hashes))
        self.assertGreater(len(hashes), 1)

    def test_ruff_settings_are_pep8_with_the_agreed_rule_sets(self):
        settings = tomllib.loads((repoRoot / "ruff.toml").read_text())
        self.assertEqual(88, settings["line-length"])
        self.assertEqual(["*.py"], settings["include"])
        self.assertEqual(["E", "W", "F", "I"], settings["lint"]["select"])

    def test_maven_verify_checks_google_java_format_with_pinned_versions(self):
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        pom = ET.parse(repoRoot / "pom.xml").getroot()
        plugin = next(
            p
            for p in pom.findall("m:build/m:plugins/m:plugin", ns)
            if p.findtext("m:artifactId", namespaces=ns) == "spotless-maven-plugin"
        )
        self.assertRegex(
            plugin.findtext("m:version", namespaces=ns), r"^\d+\.\d+\.\d+$"
        )
        gjf = plugin.find("m:configuration/m:java/m:googleJavaFormat", ns)
        self.assertRegex(gjf.findtext("m:version", namespaces=ns), r"^\d+\.\d+\.\d+$")
        self.assertEqual("true", gjf.findtext("m:reflowLongStrings", namespaces=ns))
        self.assertEqual("true", gjf.findtext("m:reorderImports", namespaces=ns))
        goals = [
            g.text
            for g in plugin.findall("m:executions/m:execution/m:goals/m:goal", ns)
        ]
        self.assertEqual(["check"], goals)


if __name__ == "__main__":
    unittest.main()
