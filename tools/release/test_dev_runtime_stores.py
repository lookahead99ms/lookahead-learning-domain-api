"""DLV-971: the runtime stores in the assets bucket and the live publication tar reach the Domain container as settings."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DevRuntimeStoreSettingsTests(unittest.TestCase):
    def setUp(self):
        self.template = json.loads((ROOT / 'deployment/dev/service.template.json').read_text())
        self.config = json.loads((ROOT / 'deployment/dev/deploy-config.json').read_text())['parameters']
        definition = next(r for r in self.template['Resources'].values() if r['Type'] == 'AWS::ECS::TaskDefinition')
        self.environment = {e['Name']: e['Value'] for e in definition['Properties']['ContainerDefinitions'][0]['Environment']}

    def test_every_store_prefix_is_a_template_parameter_passed_to_the_container(self):
        for parameter, variable in [('ReviewsPrefix', 'LOOKAHEAD_REVIEWS_PREFIX'), ('SupportRequestsPrefix', 'LOOKAHEAD_SUPPORT_REQUESTS_PREFIX'),
                                    ('DeliveryPrefix', 'LOOKAHEAD_DELIVERY_PREFIX'), ('PlatformReviewsLegacyPrefix', 'LOOKAHEAD_PLATFORM_REVIEWS_LEGACY_PREFIX'),
                                    ('PublicationLivePrefix', 'LOOKAHEAD_PUBLICATION_LIVE_PREFIX')]:
            self.assertIn(parameter, self.template['Parameters'], parameter)
            self.assertEqual(self.environment[variable], {'Ref': parameter}, variable)

    def test_dev_values_follow_the_approved_layout_and_serve_the_live_tar(self):
        self.assertEqual(self.config['ReviewsPrefix'], 'reviews/')
        self.assertEqual(self.config['SupportRequestsPrefix'], 'support-requests/')
        self.assertEqual(self.config['DeliveryPrefix'], 'delivery/')
        self.assertEqual(self.config['PlatformReviewsLegacyPrefix'], 'platform-reviews/')
        # 2026-11-06 late evening: the owner copied the live tar to release/live/ and IAM A is installed, so DEV serves the single tar.
        self.assertEqual(self.config['PublicationLivePrefix'], 'release/live/')
        self.assertEqual(self.template['Parameters']['PublicationLivePrefix']['Default'], '', 'the template default stays empty; the DEV value is the config')
        self.assertLessEqual(len((ROOT / 'deployment/dev/service.template.json').read_text()), 51200)


if __name__ == '__main__':
    unittest.main()
