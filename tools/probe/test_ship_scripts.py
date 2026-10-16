"""Ship probes obey the driver's strict completion guard without removing any checks."""
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parent


class ShipScriptTest(unittest.TestCase):
    def test_ship_scripts_finish_with_screenshot_and_acknowledged_quit(self):
        for name in ("ship-hold.txt", "ship-sleep.txt", "ship-restart-before.txt",
                     "ship-restart-after.txt"):
            with self.subTest(script=name):
                lines = (ROOT / name).read_text().splitlines()
                self.assertIn("strict", lines)
                self.assertTrue(lines[-2].startswith("shot "))
                self.assertEqual("quit", lines[-1])

    def test_space_hold_and_wake_require_server_tick_window(self):
        for name in ("ship-hold.txt", "ship-space-sleep.txt"):
            with self.subTest(script=name):
                script = (ROOT / name).read_text()
                self.assertIn('assert hold-window', script)
                self.assertIn('matches 1', script)
                self.assertTrue(script.splitlines()[-2].startswith('shot '))
                self.assertEqual('quit', script.splitlines()[-1])
        wake = (ROOT / 'ship-space-sleep.txt').read_text()
        self.assertLess(wake.index('assert space-place'), wake.index('assert hold-window'))
        hold = (ROOT / 'ship-hold.txt').read_text()
        self.assertLess(hold.index('assert hold-window'), hold.rindex('requirehelm'))

    def test_empty_cargo_fixture_is_explicit_and_precedes_ticket_release(self):
        for name in ("ship-sleep.txt", "ship-restart-before.txt"):
            with self.subTest(script=name):
                script = (ROOT / name).read_text()
                inspect = script.index('cmd starfold shipprobe "0:0" inspect')
                remove = script.index('cmd starfold shipprobe "0:0" remove world-items')
                release = script.index('cmd starfold shipprobe "0:0" leases off')
                self.assertLess(inspect, remove)
                self.assertLess(remove, release)
                self.assertIn('assert dormant', script)
                self.assertIn('assert checkpoint', script)


if __name__ == "__main__":
    unittest.main()
