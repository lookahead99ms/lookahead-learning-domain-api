"""DLV-969 S36: the DEV task passes the merge-link email settings; sending stays off until the owner turns it on."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DevEmailSettingsTests(unittest.TestCase):
    def setUp(self):
        self.template = json.loads((ROOT / 'deployment/dev/service.template.json').read_text())
        self.config = json.loads((ROOT / 'deployment/dev/deploy-config.json').read_text())['parameters']
        definition = self.template['Resources']['Task']
        self.environment = {e['Name']: e['Value'] for e in definition['Properties']['ContainerDefinitions'][0]['Environment']}

    def test_the_template_passes_the_switch_the_sender_and_the_link_origin(self):
        parameter = self.template['Parameters']['EmailEnabled']
        self.assertEqual((parameter['AllowedValues'], parameter['Default']), (['false', 'true'], 'false'))
        self.assertEqual(self.environment['LOOKAHEAD_EMAIL_ENABLED'], {'Ref': 'EmailEnabled'})
        self.assertEqual(self.environment['LOOKAHEAD_EMAIL_FROM'], 'Look Ahead <no-reply@dev.devlookahead.com>')
        self.assertEqual(self.environment['LOOKAHEAD_PUBLIC_URL'], 'https://dev.devlookahead.com')
        # SES uses the task's existing region; no second region setting and no email credential in the task.
        self.assertEqual(self.environment['AWS_REGION'], {'Ref': 'AWS::Region'})
        self.assertFalse([name for name in self.environment if name.startswith('LOOKAHEAD_EMAIL_') and name not in ('LOOKAHEAD_EMAIL_ENABLED', 'LOOKAHEAD_EMAIL_FROM')])
        secrets = self.template['Resources']['Task']['Properties']['ContainerDefinitions'][0].get('Secrets', [])
        self.assertFalse([s for s in secrets if 'EMAIL' in s['Name'] or 'SES' in s['Name']])

    def test_dev_keeps_sending_off(self):
        self.assertEqual(self.config['EmailEnabled'], 'false')
        # Every DEV value names a template parameter, so CloudFormation accepts the deploy.
        self.assertLessEqual(set(self.config), set(self.template['Parameters']))


if __name__ == '__main__':
    unittest.main()
