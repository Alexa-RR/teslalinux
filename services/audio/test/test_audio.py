#!/usr/bin/env python3
"""services/audio/audio.py: the Pi speaker's card kept at full while it settles after appearing."""
import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
# the WebSocket server is not under test (the Pi has python3-websockets; a test machine may not)
try:
    import websockets  # noqa: F401
except ImportError:
    import types
    sys.modules["websockets"] = types.ModuleType("websockets")
import audio  # noqa: E402

JABRA = 48


def graph(card_volume):
    g = audio.Graph()
    g.apply([{"id": JABRA, "type": "PipeWire:Interface:Node",
              "info": {"props": {"node.name": "alsa_output.usb-0b0e_Jabra.analog-stereo", "media.class": "Audio/Sink"},
                       "params": {"Props": [{"volume": 1.0, "channelVolumes": [card_volume, card_volume]}]}}}])
    return g


class CardVolume(unittest.TestCase):
    def setUp(self):
        self.calls = []

        async def run_quiet(*cmd):
            self.calls.append(cmd)
            return 0
        self._saved = audio.run_quiet
        audio.run_quiet = run_quiet
        self.sp = audio.Speaker()

    def tearDown(self):
        audio.run_quiet = self._saved

    def apply(self, vol, now):
        asyncio.run(self.sp.apply_volume(graph(vol), 80, JABRA, now=now))
        return [c for c in self.calls if c[:3] == ("wpctl", "set-volume", str(JABRA))]

    def test_set_full_when_it_appears(self):
        self.assertEqual(self.apply(0.064, 100.0), [("wpctl", "set-volume", str(JABRA), "1.0")])

    def test_the_power_up_report_is_put_back_while_it_settles(self):
        self.apply(0.064, 100.0)
        self.apply(1.0, 102.0)                     # full: nothing to do
        self.assertEqual(len(self.apply(1.0, 103.0)), 1)
        self.assertEqual(len(self.apply(0.064, 105.0)), 2)   # the speaker reported its own level
        self.assertEqual(self.sp.full[JABRA], 100.0)         # the window counts from the first time

    def test_later_changes_are_the_drivers(self):
        self.apply(0.064, 100.0)
        self.assertEqual(len(self.apply(0.3, 100.0 + audio.CARD_SETTLE_S + 1)), 1)

    def test_node_volume(self):
        self.assertAlmostEqual(graph(0.25).node_volume(JABRA), 0.25)
        self.assertIsNone(audio.Graph().node_volume(JABRA))


if __name__ == "__main__":
    unittest.main()
