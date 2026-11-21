import unittest

from acceptance import PROBES, SLICES, verify_session


COMPLETE = '[CHAT] [Dev] CP_PROBE_SERVER_ACK\nPROBE COMPLETE | line 5 of 5\n'
LOGIN = '[12:00:00] [Server thread/INFO] [minecraft/MinecraftServer]: Dev joined the game\n'
ACK = '[12:00:01] [Server thread/INFO] [minecraft/MinecraftServer]: [Dev] CP_PROBE_SERVER_ACK\n'


class AcceptanceTest(unittest.TestCase):
    def test_recipe_and_persistence_scripts_are_separate_server_lifetimes(self):
        self.assertEqual(SLICES['distillation'], (
            ('stage2-industry-boundary.txt',), ('stage2-industry-rejoin.txt',)))
        for sessions in SLICES.values():
            for scripts in sessions:
                for script in scripts:
                    self.assertEqual((PROBES / script).read_text().splitlines()[-1], 'quit')

    def test_login_requires_both_server_receipts_and_the_client_check(self):
        client = COMPLETE + 'PROBE CHECK PASS in-world\n'
        self.assertEqual(verify_session('acceptance-join.txt', client, LOGIN + ACK), [])
        self.assertIn('no server ACK in this session', verify_session('acceptance-join.txt', client, LOGIN))
        self.assertIn('missing or out-of-order probe check: in-world',
                      verify_session('acceptance-join.txt', COMPLETE, LOGIN + ACK))

    def test_client_marker_cannot_replace_server_domain_receipt(self):
        script = 'stage2-industry-rejoin.txt'
        checks = [line.split()[1] for line in (PROBES / script).read_text().splitlines() if line.startswith('check ')]
        client = COMPLETE + ''.join(f'PROBE CHECK PASS {label}\n' for label in checks)
        receipt = 'liquidPersistence=EQUAL sixPhysicalTanks=true'
        self.assertEqual(verify_session(script, client + receipt, LOGIN + ACK),
                         [f'missing domain receipt: {receipt}'])
        self.assertEqual(verify_session(script, client, LOGIN + ACK +
                                       f'[Server thread/INFO] [starfold-probe/]: INDUSTRY_VERIFY {receipt}\n'), [])
