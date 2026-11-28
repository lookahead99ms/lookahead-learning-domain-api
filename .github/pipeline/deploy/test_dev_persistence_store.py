"""DLV-970: the DEV task reads the four DynamoDB table names and nothing of
PostgreSQL. DEV serves DynamoDB since the owner switched it on 2026-11-17 (the
recorded migration, data version 4, run 44014416875; the account import, data version
5, run 44162520740); the PostgreSQL settings, the store switch and the database
secret were retired from the task and the configuration the same day."""

import json
from pathlib import Path
import unittest

import deploy

ROOT = Path(__file__).resolve().parents[3]
TABLES = {
    "UserAccountDetailsTable": "LOOKAHEAD_DYNAMODB_USER_ACCOUNT_DETAILS_TABLE",
    "AdministratorAuditEventsTable": (
        "LOOKAHEAD_DYNAMODB_ADMINISTRATOR_AUDIT_EVENTS_TABLE"
    ),
    "UserStudyPlanDetailsTable": "LOOKAHEAD_DYNAMODB_USER_STUDY_PLAN_DETAILS_TABLE",
    "ProtectedOwnerIdentityTable": "LOOKAHEAD_DYNAMODB_PROTECTED_OWNER_IDENTITY_TABLE",
}
RETIRED_PARAMETERS = (
    "PersistenceStore",
    "DatabaseAddress",
    "DomainAppSecretArn",
    "DomainAppVersion",
)
RETIRED_VARIABLES = (
    "LOOKAHEAD_PERSISTENCE_STORE",
    "LOOKAHEAD_DOMAIN_JDBC_URL",
    "SPRING_DATASOURCE_USERNAME",
    "LOOKAHEAD_DOMAIN_DB_PASSWORD",
)


class DevPersistenceStoreTests(unittest.TestCase):
    def setUp(self):
        self.template = json.loads(
            (ROOT / ".github/pipeline/deploy/dev/service.template.json").read_text()
        )
        self.config = json.loads(
            (ROOT / ".github/pipeline/deploy/dev/deploy-config.json").read_text()
        )["parameters"]
        container = self.template["Resources"]["Task"]["Properties"][
            "ContainerDefinitions"
        ][0]
        self.environment = {e["Name"]: e["Value"] for e in container["Environment"]}
        self.secrets = {e["Name"] for e in container["Secrets"]}

    def test_the_template_pins_the_four_table_names_and_carries_no_database_setting(
        self,
    ):
        for parameter, variable in TABLES.items():
            spec = self.template["Parameters"][parameter]
            name = deploy.TABLE_PARAMETERS[parameter]
            self.assertEqual(
                (name, "^" + name + "$"),
                (spec["Default"], spec["AllowedPattern"]),
                parameter,
            )
            self.assertEqual({"Ref": parameter}, self.environment[variable], variable)
        for parameter in RETIRED_PARAMETERS:
            self.assertNotIn(parameter, self.template["Parameters"], parameter)
        for variable in RETIRED_VARIABLES:
            self.assertNotIn(variable, self.environment, variable)
            self.assertNotIn(variable, self.secrets, variable)
        self.assertEqual(
            {
                "LOOKAHEAD_TLS_SCHEMA",
                "LOOKAHEAD_TLS_SERVER_CERTIFICATE",
                "LOOKAHEAD_TLS_SERVER_KEY",
                "LOOKAHEAD_TLS_TRUST",
                "LOOKAHEAD_DOMAIN_GATEWAY_SECRET",
            },
            self.secrets,
        )
        self.assertNotIn(
            "postgresql",
            json.dumps(self.template).lower().replace("postgresql served dev", ""),
        )  # history only, in the metadata note
        self.assertEqual({"Ref": "AWS::Region"}, self.environment["AWS_REGION"])
        self.assertEqual("dev", self.environment["LOOKAHEAD_ENVIRONMENT"])
        self.assertNotIn(
            "LOOKAHEAD_DYNAMODB_ENDPOINT", self.environment
        )  # DEV talks to the Region's service, never an endpoint
        self.assertEqual(
            {"Task", "Service"}, set(self.template["Resources"])
        )  # the migration task is never part of the service stack

    def test_the_configuration_carries_the_table_names_and_no_retired_parameter(self):
        self.assertEqual({k: self.config[k] for k in TABLES}, deploy.TABLE_PARAMETERS)
        for parameter in RETIRED_PARAMETERS:
            self.assertNotIn(parameter, self.config, parameter)
        self.assertLessEqual(set(self.config), set(self.template["Parameters"]))
        self.assertLessEqual(
            len(
                (ROOT / ".github/pipeline/deploy/dev/service.template.json").read_text()
            ),
            51200,
        )
        deploy.loadConfig(ROOT)  # the real configuration passes the table-name check


if __name__ == "__main__":
    unittest.main()
