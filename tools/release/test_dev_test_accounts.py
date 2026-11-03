"""DLV-969 S35: DEV keeps database-only test accounts (identity dev); no sign-in pool is involved."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DevTestAccountSettingsTests(unittest.TestCase):
    def setUp(self):
        self.template = json.loads((ROOT / 'deployment/dev/service.template.json').read_text())
        self.config = json.loads((ROOT / 'deployment/dev/deploy-config.json').read_text())['parameters']

    def test_the_template_passes_the_identity_to_the_domain_container(self):
        parameter = self.template['Parameters']['TestAccountIdentity']
        self.assertEqual(parameter['AllowedValues'], ['local', 'dev'])
        self.assertEqual(parameter['Default'], 'local')
        self.assertNotIn('TestAccountPoolId', self.template['Parameters'])
        definition = next(r for r in self.template['Resources'].values() if r['Type'] == 'AWS::ECS::TaskDefinition')
        environment = {e['Name']: e['Value'] for e in definition['Properties']['ContainerDefinitions'][0]['Environment']}
        self.assertEqual(environment['LOOKAHEAD_TEST_ACCOUNT_IDENTITY'], {'Ref': 'TestAccountIdentity'})
        self.assertNotIn('LOOKAHEAD_TEST_ACCOUNT_POOL_ID', environment)

    def test_dev_uses_database_only_test_accounts(self):
        self.assertEqual(self.config['TestAccountIdentity'], 'dev')
        self.assertNotIn('TestAccountPoolId', self.config)


if __name__ == '__main__':
    unittest.main()
