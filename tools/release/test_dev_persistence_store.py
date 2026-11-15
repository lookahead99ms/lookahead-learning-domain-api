"""DLV-970: the DEV task carries the persistence store switch and the four DynamoDB table names; DEV stays on PostgreSQL until the
owner switches it with a deploy that follows a recorded migration."""
import json
from pathlib import Path
import unittest

import deploy

ROOT = Path(__file__).resolve().parents[2]
TABLES = {'UserAccountDetailsTable': 'LOOKAHEAD_DYNAMODB_USER_ACCOUNT_DETAILS_TABLE', 'AdministratorAuditEventsTable': 'LOOKAHEAD_DYNAMODB_ADMINISTRATOR_AUDIT_EVENTS_TABLE',
          'UserStudyPlanDetailsTable': 'LOOKAHEAD_DYNAMODB_USER_STUDY_PLAN_DETAILS_TABLE', 'ProtectedOwnerIdentityTable': 'LOOKAHEAD_DYNAMODB_PROTECTED_OWNER_IDENTITY_TABLE'}


class DevPersistenceStoreTests(unittest.TestCase):
    def setUp(self):
        self.template = json.loads((ROOT / 'deployment/dev/service.template.json').read_text())
        self.config = json.loads((ROOT / 'deployment/dev/deploy-config.json').read_text())['parameters']
        container = self.template['Resources']['Task']['Properties']['ContainerDefinitions'][0]
        self.environment = {e['Name']: e['Value'] for e in container['Environment']}

    def test_the_template_switches_the_store_and_pins_the_four_table_names(self):
        store = self.template['Parameters']['PersistenceStore']
        self.assertEqual((['postgresql', 'dynamodb'], 'postgresql'), (store['AllowedValues'], store['Default']))
        self.assertEqual({'Ref': 'PersistenceStore'}, self.environment['LOOKAHEAD_PERSISTENCE_STORE'])
        for parameter, variable in TABLES.items():
            spec = self.template['Parameters'][parameter]
            name = deploy.TABLE_PARAMETERS[parameter]
            self.assertEqual((name, '^' + name + '$'), (spec['Default'], spec['AllowedPattern']), parameter)
            self.assertEqual({'Ref': parameter}, self.environment[variable], variable)
        # The service keeps its JDBC settings in every mode (ignored in dynamodb mode); the region is the task's own.
        self.assertIn('LOOKAHEAD_DOMAIN_JDBC_URL', self.environment)
        self.assertEqual({'Ref': 'AWS::Region'}, self.environment['AWS_REGION'])
        self.assertEqual('dev', self.environment['LOOKAHEAD_ENVIRONMENT'])
        self.assertNotIn('LOOKAHEAD_DYNAMODB_ENDPOINT', self.environment)  # DEV talks to the Region's service, never an endpoint
        self.assertEqual({'Task', 'Service'}, set(self.template['Resources']))  # the migration task is never part of the service stack

    def test_dev_stays_on_postgresql_until_the_owner_switches(self):
        self.assertEqual('postgresql', self.config['PersistenceStore'])
        self.assertEqual({k: self.config[k] for k in TABLES}, deploy.TABLE_PARAMETERS)
        self.assertLessEqual(set(self.config), set(self.template['Parameters']))
        self.assertLessEqual(len((ROOT / 'deployment/dev/service.template.json').read_text()), 51200)
        deploy.loadConfig(ROOT)  # the real configuration passes the store checks


if __name__ == '__main__':
    unittest.main()
