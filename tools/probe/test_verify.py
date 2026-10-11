import unittest
from verify import failures


class CompletionTest(unittest.TestCase):
    complete = "[CHAT] [Dev] CP_PROBE_SERVER_ACK\nPROBE COMPLETE | line 42 of 42\n"

    def test_complete(self):
        self.assertEqual([], failures(self.complete))

    def test_partial_log_is_not_success(self):
        self.assertTrue(failures("PROBE ride-0 | pos 0 141 0"))

    def test_client_completion_with_stalled_server_is_failure(self):
        self.assertIn("server did not acknowledge script completion", failures("PROBE COMPLETE | line 42 of 42\n"))

    def test_ack_must_be_received_in_chat_not_echoed_script(self):
        self.assertTrue(failures("cmd say CP_PROBE_SERVER_ACK\nPROBE COMPLETE | line 42 of 42\n"))

    def test_condition_timeout_is_failure(self):
        self.assertTrue(failures("PROBE until ground 0.0 -> TIMEOUT | state\n" + self.complete))

    def test_rejected_commands_are_failure(self):
        for message in ("Unknown or incomplete command", "Incorrect argument for command",
                        "You do not have permission", "Unknown block type 'missing:block'", "That position is not loaded"):
            with self.subTest(message=message):
                self.assertTrue(failures("[CHAT] " + message + "\n" + self.complete))

    def test_expected_no_change_is_not_command_failure(self):
        self.assertEqual([], failures("[CHAT] No blocks were filled\n" + self.complete))


if __name__ == "__main__":
    unittest.main()
