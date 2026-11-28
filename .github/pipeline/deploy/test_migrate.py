"""Tests for `07 · Migrate DEV data` (DLV-970): the migration task tool and its
workflow. AWS and GitHub are injected fakes; nothing is called for real. Domain only."""

import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import deploy
import issue_template as issueTemplate
import migrate as m
import owner_approval as owner
from test_deploy import CONFIG as WEB_CONFIG
from test_deploy import DIGEST, SHA
from test_owner_approval import Github

ROOT = Path(__file__).resolve().parents[3]
REPOSITORY = "lookahead99ms/lookahead-learning-domain-api"
TASK = (
    "arn:aws:ecs:us-east-2:509614632283:task/lookahead-dev-cluster/"
    "0123456789abcdef0123456789abcdef"
)
REVISION = (
    "arn:aws:ecs:us-east-2:509614632283:task-definition/lookahead-dev-domain-migrate:4"
)
CONFIG = {
    **WEB_CONFIG,
    "app": "domain",
    "service": "domain",
    "sessionPrefix": "domain-deploy",
    "stack": "lookahead-dev-domain",
    "logGroup": "/lookahead/dev/domain",
    "discovery": {
        "serviceName": "domain",
        "serviceId": "srv-domain0000000001",
        "serviceArn": (
            "arn:aws:servicediscovery:us-east-2:509614632283:service/srv-"
            "domain0000000001"
        ),
    },
    "parameters": {
        **WEB_CONFIG["parameters"],
        "LogGroupName": "/lookahead/dev/domain",
        **deploy.TABLE_PARAMETERS,
    },
}


def report(command="migrate", run="12", **extra):
    return {
        "command": command,
        "environment": "dev",
        "runId": "github-" + run,
        "before": 0,
        "after": 2,
        "required": 2,
        "applied": ["1", "2"] if command == "migrate" else [],
        "verified": ["1", "2"],
        "ahead": False,
        **extra,
    }


class FakeAws:
    """Dispatches on `<service> <operation>`; the migration task stops after
    `stopPolls` reads with `exitCode`/`stopCode`, and its log stream holds `messages`
    (absent for `logAbsentPolls` reads first)."""

    def __init__(self, config):
        self.config, self.calls = config, []
        self.image = DIGEST
        self.stack = None
        self.session = config["sessionPrefix"] + "-migrate-12-1"
        self.registered = None
        self.runFailures = []
        self.stopPolls, self.exitCode, self.stopCode, self.stoppedReason = (
            2,
            0,
            "EssentialContainerExited",
            "",
        )
        self.messages = ["Migration starting", json.dumps(report())]
        self.logAbsentPolls = 1
        self.containerDigest = None

    def __call__(self, *args):
        self.calls.append(args)
        key = args[0] + " " + args[1]
        opts, i = {}, 2
        while i < len(args):
            if (
                args[i].startswith("--")
                and i + 1 < len(args)
                and not str(args[i + 1]).startswith("--")
            ):
                opts[args[i]] = args[i + 1]
                i += 2
            else:
                opts[args[i]] = True
                i += 1
        if key == "sts get-caller-identity":
            return {
                "Account": self.config["account"],
                "Arn": (
                    "arn:aws:sts::"
                    + self.config["account"]
                    + ":assumed-role/lookahead-dev-deploy/"
                    + self.session
                ),
            }
        if key == "ecr batch-get-image":
            return (
                {
                    "images": [
                        {
                            "imageId": {
                                "imageDigest": self.image,
                                "imageTag": opts["--image-ids"].split("=")[1],
                            }
                        }
                    ],
                    "failures": [],
                }
                if self.image
                else {"images": [], "failures": [{"failureCode": "ImageNotFound"}]}
            )
        if key == "cloudformation describe-stacks":
            if self.stack is None:
                raise RuntimeError(
                    "aws cloudformation describe-stacks failed: Stack with id "
                    + opts["--stack-name"]
                    + " does not exist"
                )
            return {
                "Stacks": [
                    {
                        "StackStatus": self.stack["status"],
                        "StackId": "id",
                        "Parameters": [
                            {"ParameterKey": k, "ParameterValue": v}
                            for k, v in self.stack["parameters"].items()
                        ],
                        "Tags": [],
                    }
                ]
            }
        if key == "ecs register-task-definition":
            self.registered = json.loads(opts["--cli-input-json"])
            return {
                "taskDefinition": {
                    "taskDefinitionArn": REVISION,
                    "revision": 4,
                    "status": "ACTIVE",
                    "family": self.registered["family"],
                }
            }
        if key == "ecs run-task":
            assert (
                opts["--task-definition"] == REVISION
                and opts["--launch-type"] == "FARGATE"
                and opts["--count"] == "1"
            ), opts
            self.network = json.loads(opts["--network-configuration"])
            return {
                "tasks": (
                    []
                    if self.runFailures
                    else [{"taskArn": TASK, "lastStatus": "PROVISIONING"}]
                ),
                "failures": self.runFailures,
            }
        if key == "ecs describe-tasks":
            assert opts["--tasks"] == TASK, opts
            self.stopPolls -= 1
            image = self.registered["containerDefinitions"][0]["image"]
            pulled = self.containerDigest or image.rsplit("@", 1)[-1]
            container = {
                "name": "migrate",
                "image": image.rsplit("@", 1)[0] + "@" + pulled,
                "imageDigest": pulled,
                "lastStatus": "RUNNING" if self.stopPolls >= 0 else "STOPPED",
            }
            if self.stopPolls >= 0:
                return {
                    "tasks": [
                        {
                            "taskArn": TASK,
                            "lastStatus": "RUNNING",
                            "containers": [container],
                        }
                    ]
                }
            if self.exitCode is not None:
                container["exitCode"] = self.exitCode
            return {
                "tasks": [
                    {
                        "taskArn": TASK,
                        "lastStatus": "STOPPED",
                        "stopCode": self.stopCode,
                        "stoppedReason": self.stoppedReason,
                        "containers": [container],
                    }
                ]
            }
        if key == "logs get-log-events":
            assert (
                opts["--log-stream-name"]
                == "migrate/migrate/0123456789abcdef0123456789abcdef"
                and opts["--log-group-name"] == self.config["logGroup"]
            ), opts
            self.logAbsentPolls -= 1
            if self.logAbsentPolls >= 0:
                raise RuntimeError(
                    "aws logs get-log-events failed: An error occurred"
                    " (ResourceNotFoundException): The specified log stream does not"
                    " exist."
                )
            return {
                "events": [
                    {"message": message, "timestamp": 1} for message in self.messages
                ]
            }
        raise AssertionError("unexpected aws call " + key)

    def serving(self):
        self.stack = {
            "status": "UPDATE_COMPLETE",
            "parameters": {
                **CONFIG["parameters"],
                "ApplicationImage": (
                    self.config["imageRepository"] + "@sha256:" + "o" * 64
                ),
                "DesiredCount": "1",
                "RefreshToken": "none",
            },
        }

    def ops(self, service, operation):
        return [c for c in self.calls if c[0] == service and c[1] == operation]


class MigrateFixture(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        (self.root / ".github/pipeline/deploy/dev").mkdir(parents=True)
        shutil.copy(
            ROOT / ".github/pipeline/deploy/dev/service.template.json",
            self.root / ".github/pipeline/deploy/dev/service.template.json",
        )
        self.config = json.loads(json.dumps(CONFIG))
        self.writeConfig()
        self.github = Github(REPOSITORY, SHA)
        self.github.env["GITHUB_WORKFLOW_REF"] = (
            REPOSITORY + "/" + m.WORKFLOW + "@refs/heads/main"
        )
        self.github.data["actions/runs/12"]["path"] = m.WORKFLOW
        self.env = dict(self.github.env)
        self.aws = FakeAws(self.config)
        self.now = self.github.now

    def writeConfig(self):
        (self.root / ".github/pipeline/deploy/dev/deploy-config.json").write_text(
            json.dumps(self.config)
        )

    def get(self, repository, path, payload=None):
        return self.github.get(repository, path, payload)

    def planned(self, command=None):
        env = dict(self.env)
        if command:
            env["MIGRATE_COMMAND"] = command
        result = m.plan(env, self.aws, self.root, self.get, now=self.now)
        stamp = self.now.replace(microsecond=0).isoformat()
        self.github.comment = lambda: {
            "id": 7,
            "user": {"login": owner.OWNER},
            "body": m.approvalCommand(result),
            "created_at": stamp,
            "updated_at": stamp,
        }
        return result

    def approved(self, planned):
        env = {**self.env, "MIGRATE_COMMAND": planned["command"]}
        return m.approve(
            planned,
            env,
            self.root,
            self.get,
            now=lambda: self.now,
            sleep=lambda s: None,
        )

    def migrator(self, planned):
        self.logged = []
        return m.Migrator(
            planned,
            self.aws,
            sleep=lambda s: None,
            now=lambda: self.now,
            log=lambda line, **_: self.logged.append(line),
        )


class PlanTests(MigrateFixture):
    def test_plan_binds_the_release_image_roles_network_tables_and_ledger(self):
        self.aws.serving()
        planned = self.planned()
        self.assertEqual(
            (
                "migrate",
                "domain",
                "domain-v2026.10.26.20.00",
                self.config["imageRepository"] + "@" + DIGEST,
            ),
            (planned["command"], planned["app"], planned["imageTag"], planned["image"]),
        )
        self.assertEqual(
            (
                "lookahead-dev-domain-migrate",
                "migrate",
                "domain-deploy-migrate-12-1",
                "lookahead-dev-domain",
            ),
            (
                planned["family"],
                planned["containerName"],
                planned["sessionName"],
                planned["stack"],
            ),
        )
        self.assertEqual(
            (
                "arn:aws:iam::509614632283:role/lookahead-dev-migration-task",
                "arn:aws:iam::509614632283:role/lookahead-dev-ecs-execution",
            ),
            (planned["taskRoleArn"], planned["executionRoleArn"]),
        )
        self.assertEqual(
            (["subnet-a", "subnet-b"], "sg-1", "/lookahead/dev/domain", "migrate"),
            (
                planned["subnets"],
                planned["securityGroup"],
                planned["logGroup"],
                planned["logStreamPrefix"],
            ),
        )
        self.assertEqual(
            [
                "java",
                "-cp",
                "/opt/lookahead/app.jar",
                "-Dloader.main=com.lookahead.domain.persistence.dynamodb.migration."
                "DomainDynamoMigration",
                "org.springframework.boot.loader.launch.PropertiesLauncher",
            ],
            planned["entryPoint"],
        )
        self.assertEqual(
            {
                "LOOKAHEAD_ENVIRONMENT": "dev",
                "AWS_REGION": "us-east-2",
                "GITHUB_RUN_ID": "12",
                "JAVA_TOOL_OPTIONS": m.JAVA_TOOL_OPTIONS,
                "LOOKAHEAD_DYNAMODB_USER_ACCOUNT_DETAILS_TABLE": (
                    "lookahead-dev-user-account-details"
                ),
                "LOOKAHEAD_DYNAMODB_ADMINISTRATOR_AUDIT_EVENTS_TABLE": (
                    "lookahead-dev-administrator-audit-events"
                ),
                "LOOKAHEAD_DYNAMODB_USER_STUDY_PLAN_DETAILS_TABLE": (
                    "lookahead-dev-user-study-plan-details"
                ),
                "LOOKAHEAD_DYNAMODB_PROTECTED_OWNER_IDENTITY_TABLE": (
                    "lookahead-dev-protected-owner-identity"
                ),
            },
            planned["environment"],
        )
        # DLV-970 (2026-11-17): no command carries a secret or a PostgreSQL variable
        # since the import source was retired.
        self.assertEqual({}, planned["secrets"])
        self.assertNotIn("LOOKAHEAD_DYNAMODB_ENDPOINT", planned["environment"])
        for command in ("migrate", "verify", "status"):
            readOnly = self.planned(command)
            self.assertEqual({}, readOnly["secrets"])
            self.assertEqual(
                [], [k for k in readOnly["environment"] if "IMPORT" in k or "JDBC" in k]
            )
            self.assertEqual(
                [],
                [
                    r
                    for r in readOnly["resources"]
                    if r["type"]
                    in ("AWS::RDS::DBInstance", "AWS::SecretsManager::Secret")
                ],
            )
            self.assertNotIn("currentPersistenceStore", readOnly)
        self.assertEqual(
            {
                "table": "lookahead-dev-protected-owner-identity",
                "partition": "DATA_VERSION#DOMAIN",
                "items": ["STEP#<ten-digit version>", "CURRENT", "LOCK"],
            },
            planned["ledger"],
        )
        self.assertEqual("1", planned["currentDesiredCount"])
        self.assertEqual(
            planned["planDigest"],
            owner.digest({k: v for k, v in planned.items() if k != "planDigest"}),
        )
        rows = [
            (r["action"], r["type"], r["consoleName"]) for r in planned["resources"]
        ]
        self.assertEqual(
            [
                ("Create", "AWS::ECS::TaskDefinition", "lookahead-dev-domain-migrate"),
                (
                    "Run once",
                    "AWS::ECS::Task",
                    "lookahead-dev-domain-migrate on lookahead-dev-cluster",
                ),
                ("No change", "AWS::ECS::Service", "lookahead-dev-domain"),
            ],
            rows[:3],
        )
        self.assertEqual(
            [
                ("Modify", "AWS::DynamoDB::Table", name)
                for name in deploy.TABLE_PARAMETERS.values()
            ],
            rows[3:7],
        )
        self.assertEqual(
            [
                (
                    "Modify",
                    "AWS::DynamoDB::Table item",
                    "lookahead-dev-protected-owner-identity · DATA_VERSION#DOMAIN / "
                    + key,
                )
                for key in ("STEP#<ten-digit version>", "CURRENT", "LOCK")
            ],
            rows[7:],
        )
        self.assertEqual(
            ("DATA_VERSION#DOMAIN", "LOCK"),
            (planned["resources"][-1]["pk"], planned["resources"][-1]["sk"]),
        )
        self.assertIn(
            "the running release keeps serving; deploy this release afterwards",
            planned["resources"][2]["note"],
        )
        self.assertEqual(
            [], self.aws.ops("ecs", "register-task-definition")
        )  # planning changes nothing

    def test_verify_and_status_are_read_only_plans_and_the_command_is_bounded(self):
        for command in ("verify", "status"):
            planned = self.planned(command)
            self.assertEqual(command, planned["command"])
            self.assertTrue(
                all(r["action"] == "Read only" for r in planned["resources"][3:]),
                planned["resources"],
            )
            self.assertIsNone(planned["currentDesiredCount"])  # no stack yet
            self.assertEqual(
                "APPROVE migrate dev domain-api v2026.10.26.20.00 " + command,
                m.approvalCommand(planned),
            )
            self.assertEqual(
                ("Migrate", command),
                (m.headline(planned)["action"], m.headline(planned)["note"]),
            )
            self.assertIn("nothing is written", m.headline(planned)["change"])
        with self.assertRaisesRegex(
            ValueError, "MIGRATE_COMMAND must be migrate, verify or status"
        ):
            self.planned("drop")
        self.assertEqual("migrate", m.command({"MIGRATE_COMMAND": " "}))

    def test_plan_refuses_missing_image_other_app_no_tables_and_placeholders(
        self,
    ):
        self.aws.image = None
        with self.assertRaisesRegex(ValueError, "not found in ECR"):
            self.planned()
        self.aws.image = DIGEST
        for key in ("UserAccountDetailsTable", "ProtectedOwnerIdentityTable"):
            config = json.loads(json.dumps(CONFIG))
            del config["parameters"][key]
            (self.root / ".github/pipeline/deploy/dev/deploy-config.json").write_text(
                json.dumps(config)
            )
            with self.assertRaisesRegex(
                ValueError,
                "four DEV DynamoDB table names|four DynamoDB table names required",
            ):
                self.planned()
        web = json.loads(json.dumps(WEB_CONFIG))
        (self.root / ".github/pipeline/deploy/dev/deploy-config.json").write_text(
            json.dumps(web)
        )
        with self.assertRaisesRegex(
            ValueError,
            "Domain configuration with the four DynamoDB table names required",
        ):
            m.loadConfig(self.root)
        self.writeConfig()
        with patch.object(
            m,
            "environment",
            return_value={
                **m.environment(self.config, "12"),
                "AWS_REGION": "PENDING-S9",
            },
        ):
            with self.assertRaisesRegex(ValueError, "placeholder for AWS_REGION"):
                self.planned()

    def test_provenance_accepts_only_a_fresh_owner_dispatch_in_the_domain_repo(
        self,
    ):
        self.assertEqual("domain", m.provenance(self.env, self.get)["app"])
        for key, value in (
            ("GITHUB_EVENT_NAME", "push"),
            (
                "GITHUB_WORKFLOW_REF",
                REPOSITORY + "/" + deploy.WORKFLOW + "@refs/heads/main",
            ),
            ("GITHUB_ACTOR", "someone"),
            ("GITHUB_RUN_ATTEMPT", "2"),
        ):
            env = dict(self.env)
            env[key] = value
            with self.assertRaisesRegex(
                ValueError, "Fresh owner dispatch of 07 · Migrate DEV data"
            ):
                m.provenance(env, self.get)
        env = dict(self.env)
        env["GITHUB_REPOSITORY"] = "lookahead99ms/lookahead-learning-web"
        with self.assertRaisesRegex(ValueError, "Only the Domain migrates data"):
            m.provenance(env, self.get)
        env = dict(self.env)
        env["RELEASE_VERSION"] = "v2026.01.01.00.00"
        with self.assertRaisesRegex(ValueError, "Dispatched release tag differs"):
            m.provenance(env, self.get)
        self.github.data["commits/main"] = {"sha": "f" * 40}
        with self.assertRaisesRegex(ValueError, "Main moved"):
            m.provenance(self.env, self.get)
        self.github.data["commits/main"] = {"sha": SHA}
        self.github.data["actions/runs/12"]["status"] = "completed"
        with self.assertRaisesRegex(ValueError, "Run provenance changed"):
            m.provenance(self.env, self.get)


class ApprovalTests(MigrateFixture):
    def issue(self):
        return self.github.data["issues/3"]

    def test_approve_opens_v2_issue_naming_task_tables_ledger_returns_comment(
        self,
    ):
        self.aws.serving()
        planned = self.planned()
        receipt = self.approved(planned)
        self.assertEqual(
            (7, planned["planDigest"], deploy.SCHEMA_RECEIPT, "run"),
            (
                receipt["commentId"],
                receipt["planDigest"],
                receipt["schema"],
                receipt["mode"],
            ),
        )
        self.assertEqual(
            "DEV: Migrate domain-api v2026.10.26.20.00 · migrate", self.issue()["title"]
        )
        body = self.issue()["body"]
        self.assertTrue(
            body.startswith(
                "Applies the data steps of domain-api v2026.10.26.20.00 to the four DEV"
                " DynamoDB tables. The service is not touched.\n\n```text\n"
                "APPROVE migrate dev domain-api v2026.10.26.20.00 migrate\n```\n"
            )
        )
        for phrase in (
            "APPROVE migrate dev domain-api v2026.10.26.20.00 migrate",
            "### Affected AWS resources",
            "`AWS::ECS::TaskDefinition`",
            "`AWS::DynamoDB::Table`",
            "DATA_VERSION#DOMAIN / CURRENT",
            "DATA_VERSION#DOMAIN / LOCK",
            "lookahead-dev-migration-task",
            "data first, then app",
            "ecs register-task-definition",
            "ecs run-task",
            "logs get-log-events",
            "**NO CHANGE** `AWS::ECS::Service` · `lookahead-dev-domain`",
            "DesiredCount `1`",
            "Approve within 10 minutes",
            "a new step, not a flag",
            "retired on 2026-11-17",
        ):
            self.assertIn(phrase, body, phrase)
        for phrase in (
            "currentPersistenceStore",
            "postgresql store",
            "LOOKAHEAD_IMPORT",
            "AWS::RDS",
            "AWS::SecretsManager",
        ):
            self.assertNotIn(phrase, body, phrase)
        for forbidden in ("change set", "DesiredCount 0", "rolling update"):
            self.assertNotIn(forbidden, body, forbidden)
        self.assertEqual(
            ["## " + s for s in issueTemplate.SECTIONS],
            [line for line in body.splitlines() if line.startswith("## ")],
        )
        env = {**self.env, "MIGRATE_COMMAND": "migrate"}
        m.verify(planned, receipt, env, self.root, self.get, self.now)

    def test_verify_rejects_changed_plan_config_command_or_reject_bounded_wait(
        self,
    ):
        planned = self.planned()
        receipt = self.approved(planned)
        env = {**self.env, "MIGRATE_COMMAND": "migrate"}
        tampered = dict(planned)
        tampered["taskRoleArn"] = (
            "arn:aws:iam::509614632283:role/lookahead-dev-app-task"
        )
        with self.assertRaisesRegex(ValueError, "plan digest changed"):
            m.verify(tampered, receipt, env, self.root, self.get, self.now)
        self.config["observationMinutes"] = 2
        self.writeConfig()
        with self.assertRaisesRegex(ValueError, "Configuration changed"):
            m.verify(planned, receipt, env, self.root, self.get, self.now)
        self.config["observationMinutes"] = 15
        self.writeConfig()
        with self.assertRaisesRegex(ValueError, "Dispatched command differs"):
            m.verify(
                planned,
                receipt,
                {**env, "MIGRATE_COMMAND": "status"},
                self.root,
                self.get,
                self.now,
            )
        stamp = self.now.replace(microsecond=0).isoformat()
        self.github.comments = [
            {
                "id": 8,
                "user": {"login": owner.OWNER},
                "body": "REJECT",
                "created_at": stamp,
                "updated_at": stamp,
            }
        ]
        with self.assertRaisesRegex(ValueError, "Owner rejected"):
            m.verify(planned, receipt, env, self.root, self.get, self.now)
        self.github.comment = lambda: {
            "id": 7,
            "user": {"login": "someone"},
            "body": m.approvalCommand(planned),
            "created_at": stamp,
            "updated_at": stamp,
        }
        sleeps = []
        with self.assertRaises(TimeoutError):
            m.approve(
                planned,
                env,
                self.root,
                self.get,
                now=lambda: self.now,
                sleep=sleeps.append,
            )
        self.assertEqual([20] * 30, sleeps)
        self.assertIn(
            "Approve within 3 minutes of this issue being created; the approval comment"
            " is then valid for 45 minutes.",
            m.gist(
                planned, {"APPROVAL_WAIT_MINUTES": "3", "APPROVAL_VALID_MINUTES": "45"}
            ),
        )

    def test_only_the_readable_phrase_with_the_planned_command_approves(self):
        """DLV-1027: `APPROVE migrate dev domain-api <version> <command>`, exact after
        trimming surrounding whitespace, case-sensitive."""
        planned = self.planned("verify")
        receipt = self.approved(planned)
        env = {**self.env, "MIGRATE_COMMAND": "verify"}
        good = "APPROVE migrate dev domain-api v2026.10.26.20.00 verify"
        for wrong in (
            "APPROVE VERIFY domain-v2026.10.26.20.00",
            "APPROVE MIGRATE domain-v2026.10.26.20.00",
            "APPROVE migrate dev domain-api v2026.10.26.20.00 migrate",
            "APPROVE migrate dev domain-api v2026.10.26.20.00",
            "APPROVE migrate prod domain-api v2026.10.26.20.00 verify",
            "APPROVE migrate dev domain v2026.10.26.20.00 verify",
            "APPROVE migrate dev domain-api v2026.10.26.20.01 verify",
            "APPROVE Migrate dev domain-api v2026.10.26.20.00 verify",
        ):
            self.github.comments[0]["body"] = wrong
            with self.assertRaisesRegex(
                ValueError, "Exact live owner approval", msg=wrong
            ):
                m.verify(planned, receipt, env, self.root, self.get, self.now)
        self.github.comments[0]["body"] = "\n" + good + "  "
        m.verify(planned, receipt, env, self.root, self.get, self.now)

    def test_the_issue_body_of_verify_and_status_says_nothing_is_written(self):
        for command in ("verify", "status"):
            self.github.data.pop("issues/3", None)
            planned = self.planned(command)
            self.approved(planned)
            body = self.issue()["body"]
            self.assertIn(
                "```text\nAPPROVE migrate dev domain-api v2026.10.26.20.00 "
                + command
                + "\n```",
                body,
            )
            self.assertEqual(
                "DEV: Migrate domain-api v2026.10.26.20.00 · " + command,
                self.issue()["title"],
            )
            self.assertIn("Nothing is written.", body.splitlines()[0])
            self.assertIn("**READ ONLY** `AWS::DynamoDB::Table`", body)
            self.assertIn("an empty `applied` list", body)
            self.assertIn("**Business change:** none", body)


class ExecuteTests(MigrateFixture):
    def registered(self):
        return self.aws.registered

    def test_execute_registers_runs_waits_reads_the_report_and_succeeds(self):
        planned = self.planned()
        outcome = self.migrator(planned).run()
        self.assertEqual(
            ("success", "migrate", 0, "EssentialContainerExited", REVISION, TASK),
            (
                outcome["outcome"],
                outcome["command"],
                outcome["exitCode"],
                outcome["stopCode"],
                outcome["taskDefinition"],
                outcome["task"],
            ),
        )
        self.assertEqual(
            ["identity", "registered", "started", "stopped", "report"],
            [s["step"] for s in outcome["steps"]],
        )
        self.assertEqual(report(), outcome["report"])
        definition = self.registered()
        container = definition["containerDefinitions"][0]
        self.assertEqual(
            (
                "lookahead-dev-domain-migrate",
                ["FARGATE"],
                "awsvpc",
                planned["taskRoleArn"],
                planned["executionRoleArn"],
            ),
            (
                definition["family"],
                definition["requiresCompatibilities"],
                definition["networkMode"],
                definition["taskRoleArn"],
                definition["executionRoleArn"],
            ),
        )
        self.assertEqual(
            (
                "migrate",
                planned["image"],
                planned["entryPoint"],
                ["migrate"],
                "10001:10001",
                True,
            ),
            (
                container["name"],
                container["image"],
                container["entryPoint"],
                container["command"],
                container["user"],
                container["readonlyRootFilesystem"],
            ),
        )
        self.assertEqual(
            {
                "awslogs-group": "/lookahead/dev/domain",
                "awslogs-region": "us-east-2",
                "awslogs-stream-prefix": "migrate",
            },
            container["logConfiguration"]["options"],
        )
        self.assertNotIn(
            "mode", container["logConfiguration"]["options"]
        )  # blocking: the report line is never dropped
        self.assertEqual(
            planned["environment"],
            {e["name"]: e["value"] for e in container["environment"]},
        )
        self.assertNotIn("secrets", container)
        self.assertNotIn("portMappings", container)
        self.assertNotIn("tags", definition)
        self.assertEqual(
            {
                "awsvpcConfiguration": {
                    "subnets": ["subnet-a", "subnet-b"],
                    "securityGroups": ["sg-1"],
                    "assignPublicIp": "ENABLED",
                }
            },
            self.aws.network,
        )
        run = self.aws.ops("ecs", "run-task")[0]
        self.assertEqual(
            ("migrate-12", "1.4.0"),
            (
                run[run.index("--started-by") + 1],
                run[run.index("--platform-version") + 1],
            ),
        )
        self.assertEqual(
            3, len(self.aws.ops("ecs", "describe-tasks"))
        )  # two RUNNING reads, then STOPPED
        self.assertEqual(
            2, len(self.aws.ops("logs", "get-log-events"))
        )  # the stream appeared on the second read
        # Only the task definition family of the migration is touched: no service, stack
        # or change-set call.
        self.assertEqual(
            [],
            [
                c
                for c in self.aws.calls
                if c[0] == "cloudformation" and c[1] != "describe-stacks"
            ]
            + self.aws.ops("ecs", "update-service"),
        )
        self.assertTrue(any(line.startswith("report: ") for line in self.logged))

    def test_the_migrate_task_definition_keeps_the_container_shape_of_the_service(self):
        """S6: migrate.taskDefinition() is built apart from
        .github/pipeline/deploy/dev/service.template.json; its container must keep
        the service's hardening and runtime shape (user, read-only root, dropped
        capabilities, the /tmp volume, JAVA_TOOL_OPTIONS, the log group and region,
        cpu/memory/platform) and differ only where the migration must: family, task
        role, entry point, blocking logs, no ports, one secret for a migrate."""

        def camel(value):
            if isinstance(value, dict):
                return {k[0].lower() + k[1:]: camel(v) for k, v in value.items()}
            return [camel(v) for v in value] if isinstance(value, list) else value

        planned = self.planned()
        definition = m.taskDefinition(planned)
        task = camel(
            json.loads(
                (ROOT / ".github/pipeline/deploy/dev/service.template.json").read_text()
            )["Resources"]["Task"]["Properties"]
        )
        service, container = (
            task["containerDefinitions"][0],
            definition["containerDefinitions"][0],
        )
        for key in (
            "cpu",
            "memory",
            "networkMode",
            "requiresCompatibilities",
            "runtimePlatform",
            "volumes",
        ):
            self.assertEqual(task[key], definition[key], key)
        for key in (
            "user",
            "readonlyRootFilesystem",
            "linuxParameters",
            "mountPoints",
            "essential",
        ):
            self.assertEqual(service[key], container[key], key)
        environment = {e["name"]: e["value"] for e in service["environment"]}
        self.assertEqual(
            environment["JAVA_TOOL_OPTIONS"],
            planned["environment"]["JAVA_TOOL_OPTIONS"],
        )
        self.assertEqual(m.JAVA_TOOL_OPTIONS, environment["JAVA_TOOL_OPTIONS"])
        self.assertEqual(
            (environment["LOOKAHEAD_ENVIRONMENT"], {"ref": "AWS::Region"}),
            (
                planned["environment"]["LOOKAHEAD_ENVIRONMENT"],
                environment["AWS_REGION"],
            ),
        )
        options = container["logConfiguration"]["options"]
        self.assertEqual(
            ({"ref": "LogGroupName"}, {"ref": "AWS::Region"}, "awslogs"),
            (
                service["logConfiguration"]["options"]["awslogs-group"],
                service["logConfiguration"]["options"]["awslogs-region"],
                container["logConfiguration"]["logDriver"],
            ),
        )
        self.assertEqual(
            (
                self.config["parameters"]["LogGroupName"],
                self.config["logGroup"],
                self.config["region"],
            ),
            (
                options["awslogs-group"],
                options["awslogs-group"],
                options["awslogs-region"],
            ),
        )
        self.assertEqual(
            ("migrate", "domain"),
            (
                options["awslogs-stream-prefix"],
                service["logConfiguration"]["options"]["awslogs-stream-prefix"],
            ),
        )
        # What differs, on purpose.
        self.assertEqual(
            ("lookahead-dev-domain-migrate", "lookahead-dev-domain"),
            (definition["family"], task["family"]),
        )
        self.assertEqual(
            (
                {"ref": "TaskRoleArn"},
                "arn:aws:iam::509614632283:role/lookahead-dev-migration-task",
            ),
            (task["taskRoleArn"], definition["taskRoleArn"]),
        )
        self.assertEqual(
            (
                {"ref": "ExecutionRoleArn"},
                self.config["parameters"]["ExecutionRoleArn"],
            ),
            (task["executionRoleArn"], definition["executionRoleArn"]),
        )
        self.assertEqual(
            ("non-blocking", None),
            (service["logConfiguration"]["options"].get("mode"), options.get("mode")),
        )
        self.assertTrue(service.get("portMappings") and service.get("secrets"))
        for absent in ("portMappings", "healthCheck", "stopTimeout", "startTimeout"):
            self.assertNotIn(absent, container, absent)
        # No command carries a secret since the PostgreSQL import source was retired
        # (2026-11-17); the service keeps its TLS and Gateway secrets.
        for command in ("migrate", "verify", "status"):
            self.assertNotIn(
                "secrets",
                m.taskDefinition(self.planned(command))["containerDefinitions"][0],
            )
        self.assertEqual(["migrate"], container["command"])
        self.assertNotIn("entryPoint", service)

    def test_nonzero_exit_missing_or_failed_report_fails_and_records_reason(
        self,
    ):
        self.aws.exitCode, self.aws.messages = (
            1,
            [
                "x",
                json.dumps(
                    {
                        "command": "migrate",
                        "failed": True,
                        "error": "DynamoMigrationLock.Locked",
                    }
                ),
            ],
        )
        migrator = self.migrator(self.planned())
        with self.assertRaisesRegex(
            ValueError, "exit code 1: DynamoMigrationLock.Locked"
        ):
            migrator.run()
        self.assertEqual(
            ("failure", 1, True),
            (
                migrator.outcome["outcome"],
                migrator.outcome["exitCode"],
                migrator.outcome["report"]["failed"],
            ),
        )
        self.aws = FakeAws(self.config)
        (
            self.aws.exitCode,
            self.aws.stopCode,
            self.aws.stoppedReason,
            self.aws.messages,
        ) = (
            None,
            "TaskFailedToStart",
            "CannotPullContainerError: denied",
            [],
        )
        migrator = self.migrator(self.planned())
        with self.assertRaisesRegex(
            ValueError,
            "stop code TaskFailedToStart, exit code None: CannotPullContainerError:"
            " denied",
        ):
            migrator.run()
        self.assertEqual("no-report", migrator.steps[-1]["step"])
        self.aws = FakeAws(self.config)
        self.aws.messages = [
            "no json here",
            '{"not": "a report"}',
            '{"command": broken',
        ]
        with self.assertRaisesRegex(ValueError, "No JSON report line"):
            self.migrator(self.planned()).run()
        self.aws = FakeAws(self.config)
        self.aws.messages = [json.dumps(report(failed=True, error="LEDGER_CONFLICT"))]
        with self.assertRaisesRegex(
            ValueError, "Migration reported failure: LEDGER_CONFLICT"
        ):
            self.migrator(self.planned()).run()
        self.aws = FakeAws(self.config)
        self.aws.messages = [json.dumps(report(run="11"))]
        with self.assertRaisesRegex(ValueError, "belongs to another run"):
            self.migrator(self.planned()).run()
        self.aws = FakeAws(self.config)
        self.aws.messages = [json.dumps(report(after=1))]
        with self.assertRaisesRegex(
            ValueError, "Data version 1 is still behind the required 2"
        ):
            self.migrator(self.planned()).run()
        self.aws = FakeAws(self.config)
        self.aws.messages = [json.dumps(report(environment="prod"))]
        with self.assertRaisesRegex(ValueError, "another command or environment"):
            self.migrator(self.planned()).run()

    def test_verify_and_status_must_record_no_ledger_change(self):
        for command in ("verify", "status"):
            self.aws = FakeAws(self.config)
            self.aws.messages = [json.dumps(report(command))]
            outcome = self.migrator(self.planned(command)).run()
            self.assertEqual(
                ("success", [command], []),
                (
                    outcome["outcome"],
                    self.registered()["containerDefinitions"][0]["command"],
                    outcome["report"]["applied"],
                ),
            )
            self.aws = FakeAws(self.config)
            self.aws.messages = [json.dumps(report(command, applied=["2"]))]
            with self.assertRaisesRegex(
                ValueError, command + " must record no ledger change"
            ):
                self.migrator(self.planned(command)).run()

    def test_an_ahead_ledger_is_recorded_and_the_last_report_line_wins(self):
        self.aws.messages = [
            json.dumps(report(ahead=True)),
            "trailer",
            json.dumps(report(ahead=True, before=3, after=3)),
        ]
        outcome = self.migrator(self.planned()).run()
        self.assertEqual(
            ("success", 3, "ahead"),
            (
                outcome["outcome"],
                outcome["report"]["before"],
                outcome["steps"][-1]["step"],
            ),
        )

    def test_session_identity_task_start_wrong_image_and_timeouts_are_refused(self):
        self.aws.session = "someone-else"
        migrator = self.migrator(self.planned())
        with self.assertRaisesRegex(ValueError, "Migration session identity mismatch"):
            migrator.run()
        self.assertEqual(
            ([], "failure"),
            (
                self.aws.ops("ecs", "register-task-definition"),
                migrator.outcome["outcome"],
            ),
        )
        self.aws = FakeAws(self.config)
        self.aws.runFailures = [{"arn": "x", "reason": "RESOURCE:CPU"}]
        with self.assertRaisesRegex(ValueError, "Task did not start: .*RESOURCE:CPU"):
            self.migrator(self.planned()).run()
        self.aws = FakeAws(self.config)
        self.aws.containerDigest = "sha256:" + "f" * 64
        with self.assertRaisesRegex(ValueError, "did not run the approved image"):
            self.migrator(self.planned()).run()
        self.aws = FakeAws(self.config)
        self.aws.stopPolls = 10**6
        with self.assertRaisesRegex(
            ValueError,
            "migration task 0123456789abcdef0123456789abcdef did not complete within"
            " 1800 seconds",
        ):
            self.migrator(self.planned()).run()
        self.aws = FakeAws(self.config)
        self.aws.logAbsentPolls = 10**6
        migrator = self.migrator(self.planned())
        with self.assertRaisesRegex(ValueError, "No JSON report line"):
            migrator.run()
        self.assertEqual(
            12, len(self.aws.ops("logs", "get-log-events"))
        )  # 120 seconds of 10-second reads
        with patch.object(
            m.deploy,
            "tolerant",
            side_effect=RuntimeError(
                "aws logs get-log-events failed: AccessDeniedException"
            ),
        ):
            with self.assertRaisesRegex(RuntimeError, "AccessDeniedException"):
                self.migrator(self.planned()).run()
        self.aws = FakeAws(self.config)
        with patch.dict(self.aws.__dict__, {"registered": None}):
            original = self.aws.__call__

            def badRegister(*args):
                if args[1] == "register-task-definition":
                    self.aws.calls.append(args)
                    return {
                        "taskDefinition": {
                            "taskDefinitionArn": (
                                "arn:aws:ecs:us-east-2:509614632283:task-definition/"
                                "lookahead-dev-domain:9"
                            ),
                            "status": "ACTIVE",
                        }
                    }
                return original(*args)

            with self.assertRaisesRegex(
                ValueError, "not a revision of lookahead-dev-domain-migrate"
            ):
                m.Migrator(
                    self.planned(),
                    badRegister,
                    sleep=lambda s: None,
                    now=lambda: self.now,
                    log=lambda *a, **k: None,
                ).run()

    def test_report_line_parsing_is_tolerant(self):
        self.assertIsNone(m.reportLine([]))
        self.assertIsNone(m.reportLine(['{"a": 1}', '{"command": broken', "[1, 2]"]))
        self.assertEqual(
            {"command": "status", "after": 2},
            m.reportLine(
                ['{"command": "migrate"}', ' {"command": "status", "after": 2} ']
            ),
        )


class CliTests(MigrateFixture):
    def test_cli_plan_approve_verify_execute_write_once(self):
        out = self.root / "out"
        out.mkdir()
        env = dict(self.env)
        env["GITHUB_OUTPUT"] = str(out / "github-output")
        env["MIGRATE_COMMAND"] = "migrate"
        (out / "github-output").write_text("")
        planned = m.main(
            ["plan", "--root", str(self.root), "--output", str(out / "plan.json")],
            env,
            call=self.aws,
            get=self.get,
        )
        self.assertIn("command=migrate", (out / "github-output").read_text())
        stamp = self.now.replace(microsecond=0).isoformat()
        self.github.comment = lambda: {
            "id": 7,
            "user": {"login": owner.OWNER},
            "body": m.approvalCommand(planned),
            "created_at": stamp,
            "updated_at": stamp,
        }
        with self.assertRaises(FileExistsError):
            m.main(
                ["plan", "--root", str(self.root), "--output", str(out / "plan.json")],
                env,
                call=self.aws,
                get=self.get,
            )
        with patch.object(
            m.deploy,
            "utc",
            side_effect=lambda now=None: self.now if now is None else now,
        ):
            receipt = m.main(
                [
                    "approve",
                    "--root",
                    str(self.root),
                    "--plan",
                    str(out / "plan.json"),
                    "--receipt",
                    str(out / "approval.json"),
                ],
                env,
                get=self.get,
                sleep=lambda s: None,
            )
            self.assertEqual(receipt, json.loads((out / "approval.json").read_text()))
            m.main(
                [
                    "verify",
                    "--root",
                    str(self.root),
                    "--plan",
                    str(out / "plan.json"),
                    "--receipt",
                    str(out / "approval.json"),
                ],
                env,
                get=self.get,
            )
            with self.assertRaisesRegex(ValueError, "--output required"):
                m.main(
                    [
                        "execute",
                        "--root",
                        str(self.root),
                        "--plan",
                        str(out / "plan.json"),
                        "--receipt",
                        str(out / "approval.json"),
                    ],
                    env,
                    call=self.aws,
                    get=self.get,
                )
            outcome = m.main(
                [
                    "execute",
                    "--root",
                    str(self.root),
                    "--plan",
                    str(out / "plan.json"),
                    "--receipt",
                    str(out / "approval.json"),
                    "--output",
                    str(out / "result.json"),
                ],
                env,
                call=self.aws,
                get=self.get,
                sleep=lambda s: None,
            )
        self.assertEqual("success", outcome["outcome"])
        self.assertEqual(outcome, json.loads((out / "result.json").read_text()))
        with self.assertRaisesRegex(ValueError, "--plan and --receipt required"):
            m.main(["verify", "--root", str(self.root)], env, get=self.get)
        with self.assertRaisesRegex(ValueError, "--output required"):
            m.main(["plan", "--root", str(self.root)], env, call=self.aws, get=self.get)


STAGES = ["validate", "plan-migration", "approve-migration", "migrate-dev", "record"]
NAMES = {
    "validate": "Security gate",
    "plan-migration": "Plan DEV data migration",
    "approve-migration": "Approve DEV data migration",
    "migrate-dev": "Migrate DEV data",
    "record": "Record migration",
}
PINNED = {
    "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
    "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
    "actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333",
    "actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97",
    "aws-actions/configure-aws-credentials@e1253824e5c10ff9df46874f81ed3ec929e19cfd",
}


def runs(job):
    return [s["run"] for s in job.get("steps", []) if "run" in s]


def names(job):
    return [s.get("name") or s.get("uses") for s in job.get("steps", [])]


def uses(job, prefix):
    return [
        s for s in job.get("steps", []) if str(s.get("uses", "")).startswith(prefix)
    ]


class MigrateWorkflowTests(unittest.TestCase):
    """`07 · Migrate DEV data`: dispatch-only, tag-driven, one approval issue, one
    migration task, never beside a deploy."""

    def setUp(self):
        self.wf = json.loads((ROOT / ".github/workflows/migrate-dev.yml").read_text())
        self.jobs = self.wf["jobs"]

    def test_shape_inputs_and_shared_deploy_concurrency(self):
        self.assertEqual("07 · Migrate DEV data", self.wf["name"])
        self.assertEqual(["workflow_dispatch"], list(self.wf["on"]))
        inputs = self.wf["on"]["workflow_dispatch"]["inputs"]
        self.assertEqual({"release_version", "command"}, set(inputs))
        self.assertEqual(
            (True, "string"),
            (inputs["release_version"]["required"], inputs["release_version"]["type"]),
        )
        self.assertEqual(
            ("choice", ["migrate", "verify", "status"], "migrate", False),
            (
                inputs["command"]["type"],
                inputs["command"]["options"],
                inputs["command"]["default"],
                inputs["command"]["required"],
            ),
        )
        self.assertEqual({"contents": "read"}, self.wf["permissions"])
        self.assertEqual(
            {"group": "deploy-dev", "cancel-in-progress": False}, self.wf["concurrency"]
        )
        self.assertEqual(
            {
                "DEPLOY_ROLE": (
                    "${{ vars.DEV_DEPLOY_ROLE_ARN ||"
                    " 'arn:aws:iam::509614632283:role/lookahead-dev-deploy' }}"
                ),
                "RELEASE_VERSION": "${{ inputs.release_version }}",
                "MIGRATE_COMMAND": "${{ inputs.command || 'migrate' }}",
            },
            self.wf["env"],
        )
        self.assertEqual(STAGES, list(self.jobs))
        self.assertEqual(NAMES, {k: v["name"] for k, v in self.jobs.items()})
        validate = self.jobs["validate"]
        self.assertEqual('test "$GITHUB_REF" = refs/heads/main', runs(validate)[0])
        self.assertTrue(
            runs(validate)[-1].endswith("*/deploy.py,*/migrate.py' --fail-under=85")
        )

    def test_plan_resolves_the_tag_then_assumes_the_plan_session(self):
        plan = self.jobs["plan-migration"]
        self.assertEqual(
            (
                "validate",
                "dev",
                {"contents": "read", "actions": "read", "id-token": "write"},
            ),
            (plan["needs"], plan["environment"], plan["permissions"]),
        )
        self.assertEqual(
            {
                "command": "${{ steps.plan.outputs.command }}",
                "plan-digest": "${{ steps.plan.outputs.plan_digest }}",
            },
            plan["outputs"],
        )
        text = "\n".join(runs(plan))
        self.assertIn(
            "release_version.py resolve --app domain --record"
            ' "$RUNNER_TEMP/migrate-plan/release-source.json"',
            text,
        )
        self.assertIn(
            'migrate.py plan --output "$RUNNER_TEMP/migrate-plan/plan.json"', text
        )
        creds = [
            c["with"] for c in uses(plan, "aws-actions/configure-aws-credentials@")
        ]
        self.assertEqual(
            [
                (
                    "${{ env.DEPLOY_ROLE }}",
                    "domain-deploy-migrate-plan-${{ github.run_id }}-1",
                    900,
                    "509614632283",
                )
            ],
            [
                (
                    c["role-to-assume"],
                    c["role-session-name"],
                    c["role-duration-seconds"],
                    c["allowed-account-ids"],
                )
                for c in creds
            ],
        )
        order = names(plan)
        oidc = order.index(
            next(
                s["name"]
                for s in plan["steps"]
                if str(s.get("uses", "")).startswith("aws-actions/")
            )
        )
        self.assertLess(
            order.index("Point the release-source record at the runner temp directory"),
            oidc,
        )
        self.assertEqual(
            {"migrate-plan": "${{ runner.temp }}/migrate-plan/"},
            {
                s["with"]["name"]: s["with"]["path"]
                for s in uses(plan, "actions/upload-artifact@")
            },
        )

    def test_approval_is_credential_free_and_its_own_stage(self):
        approve = self.jobs["approve-migration"]
        self.assertEqual(
            (
                ["plan-migration"],
                "always() && needs.plan-migration.result == 'success'",
            ),
            (approve["needs"], approve["if"]),
        )
        self.assertEqual(
            {
                "contents": "read",
                "actions": "read",
                "issues": "write",
                "pull-requests": "read",
            },
            approve["permissions"],
        )
        self.assertNotIn("environment", approve)
        self.assertEqual([], uses(approve, "aws-actions/"))
        self.assertEqual(20, approve["timeout-minutes"])
        self.assertEqual(
            ("${{ vars.APPROVAL_WAIT_MINUTES }}", "${{ vars.APPROVAL_VALID_MINUTES }}"),
            (
                approve["env"]["APPROVAL_WAIT_MINUTES"],
                approve["env"]["APPROVAL_VALID_MINUTES"],
            ),
        )
        self.assertIn(
            'migrate.py approve --plan "$RUNNER_TEMP/migrate-plan/plan.json" --receipt'
            ' "$RUNNER_TEMP/migrate-approval/approval.json"',
            "\n".join(runs(approve)),
        )
        self.assertEqual(
            ("failure()", True, True),
            (
                approve["steps"][-1]["if"],
                approve["steps"][-1]["continue-on-error"],
                # DLV-1027: titles read `DEV: Migrate domain-api <version> · <command>`
                '--match "DEV: Migrate"' in approve["steps"][-1]["run"],
            ),
        )

    def test_migration_rechecks_approval_then_executes_once_and_records_outcome(
        self,
    ):
        job = self.jobs["migrate-dev"]
        self.assertEqual(
            (
                ["plan-migration", "approve-migration"],
                "always() && needs.approve-migration.result == 'success'",
                "dev",
                60,
            ),
            (job["needs"], job["if"], job["environment"], job["timeout-minutes"]),
        )
        self.assertEqual(
            {
                "contents": "read",
                "actions": "read",
                "issues": "write",
                "id-token": "write",
            },
            job["permissions"],
        )
        order = names(job)
        oidc = order.index(
            next(
                s["name"]
                for s in job["steps"]
                if str(s.get("uses", "")).startswith("aws-actions/")
            )
        )
        self.assertLess(
            order.index("Recheck the migration approval before AWS credentials"), oidc
        )
        creds = [c["with"] for c in uses(job, "aws-actions/configure-aws-credentials@")]
        self.assertEqual(
            [
                (
                    "${{ env.DEPLOY_ROLE }}",
                    "domain-deploy-migrate-${{ github.run_id }}-1",
                    3600,
                )
            ],
            [
                (
                    c["role-to-assume"],
                    c["role-session-name"],
                    c["role-duration-seconds"],
                )
                for c in creds
            ],
        )
        execute = next(s for s in job["steps"] if s.get("id") == "execute")
        self.assertEqual(oidc + 1, order.index(execute["name"]))
        self.assertEqual(
            "python3 .github/pipeline/deploy/migrate.py execute --plan"
            ' "$RUNNER_TEMP/migrate-plan/plan.json" --receipt'
            ' "$RUNNER_TEMP/migrate-approval/approval.json" --output'
            ' "$RUNNER_TEMP/migrate-result/result.json"',
            execute["run"],
        )
        result = next(
            s
            for s in uses(job, "actions/upload-artifact@")
            if s["with"]["name"] == "migrate-result"
        )
        self.assertEqual(
            ("always()", 90), (result["if"], result["with"]["retention-days"])
        )
        last = job["steps"][-1]
        self.assertEqual(
            ("always()", True, "${{ steps.execute.outcome }}"),
            (last["if"], last["continue-on-error"], last["env"]["OUTCOME"]),
        )
        self.assertIn(
            'issue_close.py --stage "Migrate DEV data" --outcome "$OUTCOME" --detail'
            ' "$detail" --receipt "$RUNNER_TEMP/migrate-approval/approval.json"',
            last["run"],
        )
        text = json.dumps(self.wf)
        for forbidden in (
            "update-service",
            "create-change-set",
            "delete-stack",
            "docker ",
            "deploy.py plan",
            "deploy.py execute",
            "lookahead-learning-infra",
            "CROSS_REPO_READ_TOKEN",
            "$GITHUB_SHA",
        ):
            # every AWS action goes through migrate.py; no deploy, no other
            # repository
            self.assertNotIn(forbidden, text, forbidden)
        self.assertEqual(
            text.count("github.sha"), text.count('"ref": "${{ github.sha }}"')
        )

    def test_every_job_is_pinned_and_the_record_bundles_the_three_receipts(self):
        for name, job in self.jobs.items():
            self.assertNotIn("runner.", json.dumps(job.get("env", {})), name)
            for step in job.get("steps", []):
                if "uses" in step:
                    self.assertIn(step["uses"], PINNED, name + ": " + step["uses"])
            self.assertNotEqual(
                "write", job.get("permissions", {}).get("contents"), name
            )
            if name in ("plan-migration", "migrate-dev"):
                self.assertEqual("dev", job["environment"], name)
            else:
                self.assertNotIn("id-token", job.get("permissions", {}), name)
        record = self.jobs["record"]
        self.assertEqual(
            (
                ["plan-migration", "approve-migration", "migrate-dev"],
                "always() && needs.plan-migration.result == 'success'",
            ),
            (record["needs"], record["if"]),
        )
        optional = next(s for s in uses(record, "actions/download-artifact@"))
        self.assertEqual(
            ("{migrate-plan,migrate-approval,migrate-result}", True),
            (optional["with"]["pattern"], optional["continue-on-error"]),
        )
        upload = next(s["with"] for s in uses(record, "actions/upload-artifact@"))
        self.assertEqual(
            ("migrate-record-${{ inputs.release_version }}-${{ github.run_id }}", 90),
            (upload["name"], upload["retention-days"]),
        )


if __name__ == "__main__":
    unittest.main()
