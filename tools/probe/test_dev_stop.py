"""Runner shutdown checks without launching Minecraft: python3 tools/probe/test_dev_stop.py."""
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
RUNNER = (ROOT / "tools/probe/dev.sh").read_text()
STOP = RUNNER[RUNNER.index("SERVER_PATTERN="):RUNNER.index("cleanup()")]


class ShutdownTest(unittest.TestCase):
    def stop(self, log, running=False):
        with tempfile.TemporaryDirectory(prefix="stop-test-", dir=ROOT / "build") as folder:
            root = pathlib.Path(folder)
            (root / "build").mkdir()
            (root / "build/dev-server.log").write_text(log)
            # No real PIDs: exercise marker handling and the grace/kill branch with a fake clock.
            mocks = '''
PATTERN=game
pgrep() { [[ "$*" != *server* ]] && ((alive)); }
pkill() { echo "signal $*"; if [[ "$*" == *KILL* ]]; then alive=0; fi; return 0; }
sleep() { SECONDS=$((SECONDS+1)); }
'''
            script = mocks + STOP + f'\nSERVER_PATTERN=server; server_started=1; alive={int(running)}\nstop\n'
            result = subprocess.run(["timeout", "10", "bash", "-c", script], cwd=root,
                                    capture_output=True, text=True)
            tail = root / "build/dev-server-stop-tail.log"
            return result, tail.read_text() if tail.exists() else None

    def test_saved_server_succeeds(self):
        result, tail = self.stop("All dimensions are saved\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIsNone(tail)

    def test_missing_save_marker_fails_even_after_process_exit(self):
        result, tail = self.stop("Saving worlds\n")
        self.assertEqual(1, result.returncode)
        self.assertIn("FAIL: server shutdown did not complete", result.stderr)
        self.assertEqual("Saving worlds\n", tail)

    def test_hang_is_killed_and_keeps_last_200_lines(self):
        log = "Saving worlds\n" + "".join(f"line {i}\n" for i in range(250))
        result, tail = self.stop(log, running=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("60 s shutdown grace", result.stderr)
        self.assertIn("signal -KILL", result.stdout)
        self.assertEqual(log.splitlines()[-200:], tail.splitlines())

    def test_saved_but_nonexiting_process_still_fails(self):
        result, _ = self.stop("All dimensions are saved\n", running=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("60 s shutdown grace", result.stderr)


if __name__ == "__main__":
    unittest.main()
