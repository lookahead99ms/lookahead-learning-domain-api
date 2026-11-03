"""DLV-969 S28: DEV turns test accounts on through the Look Ahead pool, and the pool is the sign-in issuer's own."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DevTestAccountSettingsTests(unittest.TestCase):
    def setUp(self):
        self.template = json.loads((ROOT / 'deployment/dev/service.template.json').read_text())
        self.config = json.loads((ROOT / 'deployment/dev/deploy-config.json').read_text())['parameters']

    def test_the_template_passes_both_settings_to_the_domain_container(self):
        parameters = self.template['Parameters']
        self.assertEqual(parameters['TestAccountIdentity']['AllowedValues'], ['local', 'cognito'])
        self.assertEqual(parameters['TestAccountIdentity']['Default'], 'local')
        self.assertEqual(parameters['TestAccountPoolId']['Default'], '')
        definition = next(r for r in self.template['Resources'].values() if r['Type'] == 'AWS::ECS::TaskDefinition')
        environment = {e['Name']: e['Value'] for e in definition['Properties']['ContainerDefinitions'][0]['Environment']}
        self.assertEqual(environment['LOOKAHEAD_TEST_ACCOUNT_IDENTITY'], {'Ref': 'TestAccountIdentity'})
        self.assertEqual(environment['LOOKAHEAD_TEST_ACCOUNT_POOL_ID'], {'Ref': 'TestAccountPoolId'})

    def test_dev_uses_cognito_and_the_issuers_pool(self):
        self.assertEqual(self.config['TestAccountIdentity'], 'cognito')
        # CloudTestAccountSettings refuses any pool other than the one app.cognito.issuer names.
        self.assertTrue(self.config['ApplicationIssuer'].endswith('/' + self.config['TestAccountPoolId']))
        self.assertEqual(self.config['TestAccountPoolId'], 'us-east-2_edT1KP4r9')


if __name__ == '__main__':
    unittest.main()
