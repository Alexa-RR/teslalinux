#!/usr/bin/env python3
"""services/carsync/carsync.py: what the app is told from the car's drive reads, and the car's fixes."""
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import carsync  # noqa: E402

TEL = {"destination": "Herzl St 10", "destLat": 31.768319, "destLon": 35.213710, "minutesToArrival": 21.75}


def drive(at, route=None):
    d = {"at": at, "gear": "P", "speedKph": 0, "destination": "", "destLat": None, "destLon": None}
    if route:
        d.update(route)
    return d


class Clock:
    t = 1000.0

    def __call__(self):
        return self.t


class FollowerTest(unittest.TestCase):
    def setUp(self):
        self.calls = []
        self.fail = False
        self.clock = Clock()

        def navigate(r):
            self.calls.append(("navigate", r["name"], r["lat"], r["lon"]))
            if self.fail:
                raise RuntimeError("Waze is not reachable")
            return "Waze is driving"

        def stop():
            self.calls.append(("stop",))
            return "stopped"
        self.f = carsync.Follower(navigate, stop, now=self.clock)

    def test_a_destination_counts_after_two_reads_and_goes_once(self):
        self.assertFalse(self.f.observe(drive(1, TEL)))
        self.assertTrue(self.f.observe(drive(3, TEL)))
        self.assertEqual(self.calls, [("navigate", "Herzl St 10", 31.768319, 35.213710)])
        # the same read again, and further reads of the same route (re-planned a few metres off)
        self.assertFalse(self.f.observe(drive(3, TEL)))
        self.assertFalse(self.f.observe(drive(5, dict(TEL, destLat=32.14790))))
        self.assertEqual(len(self.calls), 1)
        self.assertTrue(self.f.last_action["ok"])

    def test_a_quick_change_goes_only_as_the_last_one(self):
        other = {"destination": "Dizengoff Center", "destLat": 32.0753, "destLon": 34.7748, "minutesToArrival": 9}
        self.f.observe(drive(1, TEL))
        self.f.observe(drive(3, other))
        self.f.observe(drive(5, other))
        self.assertEqual([c[1] for c in self.calls], ["Dizengoff Center"])

    def test_the_route_ending_ends_the_drive_once(self):
        self.f.observe(drive(1, TEL))
        self.f.observe(drive(3, TEL))
        self.f.observe(drive(5))
        self.assertTrue(self.f.observe(drive(7)))
        self.assertFalse(self.f.observe(drive(9)))
        self.assertEqual(self.calls[1:], [("stop",)])
        self.assertIsNone(self.f.sent)

    def test_no_route_and_nothing_sent_does_nothing(self):
        for at in range(1, 6):
            self.assertFalse(self.f.observe(drive(at)))
        self.assertEqual(self.calls, [])

    def test_follow_off_sends_nothing_and_on_sends_the_current_route(self):
        self.f.observe(drive(1, TEL), follow=False)
        self.f.observe(drive(3, TEL), follow=False)
        self.assertEqual(self.calls, [])
        self.f.observe(drive(5, TEL), follow=True)
        self.assertEqual(len(self.calls), 1)

    def test_a_refused_destination_is_offered_again_later(self):
        self.fail = True
        self.f.observe(drive(1, TEL))
        self.f.observe(drive(3, TEL))
        self.assertFalse(self.f.last_action["ok"])
        self.clock.t += 5
        self.f.observe(drive(5, TEL))
        self.assertEqual(len(self.calls), 1)  # not before RETRY_S
        self.clock.t += carsync.RETRY_S
        self.fail = False
        self.f.observe(drive(7, TEL))
        self.assertEqual(len(self.calls), 2)
        self.assertTrue(self.f.last_action["ok"])
        self.clock.t += carsync.RETRY_S * 2
        self.f.observe(drive(9, TEL))
        self.assertEqual(len(self.calls), 2)  # taken: not again

    def test_retries_end(self):
        self.fail = True
        at = 1
        for _ in range(carsync.RETRIES + 4):
            self.f.observe(drive(at, TEL))
            at += 2
            self.clock.t += carsync.RETRY_S + 1
        self.assertEqual(len(self.calls), carsync.RETRIES + 1)

    def test_kept_across_a_restart(self):
        f = carsync.Follower(lambda r: self.calls.append(r), lambda: "", sent=carsync.route_of(dict(TEL, at=1)))
        f.observe(drive(1, TEL))
        f.observe(drive(3, TEL))
        self.assertEqual(self.calls, [])


class FakeWaze:
    """navigate_geo answers per destination: a search's result, or the coordinates' drive"""

    def __init__(self, search, mode="car"):
        self.search, self.mode, self.calls = search, mode, []

    def interface(self):
        return self.mode

    def navigate_geo(self, dest, lat, lon, label, mode):
        self.calls.append(("geo", dest, lat, lon, label))
        if dest is not None:
            if isinstance(self.search, Exception):
                raise self.search
            return self.search
        return "Navigating to Received location", {"navigating": True, "distanceLeft": {"value": 18, "unit": "km"}}

    def stop_navigation(self, args):
        self.calls.append(("stop",))
        return "stopped", {}


class FakeRoutes:
    def __init__(self, waze):
        self.waze = waze

    def start_drive(self, args):
        self.waze.calls.append(("go",))
        return "Pressed Go now", {"navigating": True, "distanceLeft": {"value": 18.4, "unit": "km"}}


class NavigateTest(unittest.TestCase):
    R = {"name": "Herzl St 10", "lat": 31.768319, "lon": 35.213710, "km": 18.4}

    def run_with(self, search, r=None, mode="car"):
        w = FakeWaze(search, mode)
        text = carsync.app_navigate(r or self.R, waze=w, routes=FakeRoutes(w))
        return text, w.calls

    def test_the_name_goes_to_waze_search_first(self):
        text, calls = self.run_with(("Navigating to Herzl 10", {"navigating": True, "distanceLeft": {"value": 17.2, "unit": "km"}}))
        self.assertEqual(calls, [("geo", "Herzl St 10", None, None, "Herzl St 10")])
        self.assertEqual(text, "Navigating to Herzl 10")

    def test_the_route_preview_is_confirmed(self):
        text, calls = self.run_with(("Waits", {"navigating": False, "waitsFor": ["Go now"]}))
        self.assertEqual([c[0] for c in calls], ["geo", "go"])
        self.assertEqual(text, "Pressed Go now")

    def test_another_place_of_that_name_is_replaced_by_the_point(self):
        text, calls = self.run_with(("Navigating", {"navigating": True, "distanceLeft": {"value": 96, "unit": "km"}}))
        self.assertEqual(calls, [("geo", "Herzl St 10", None, None, "Herzl St 10"), ("stop",),
                                 ("geo", None, 31.768319, 35.213710, "Herzl St 10")])
        self.assertIn("96 km away", text)

    def test_a_search_that_starts_no_drive_or_fails_is_replaced_by_the_point(self):
        for search in (("Sent, it may be showing search results", {"navigating": False}), RuntimeError("no results")):
            text, calls = self.run_with(search)
            self.assertEqual(calls[-1], ("geo", None, 31.768319, 35.213710, "Herzl St 10"))
            self.assertNotIn(("stop",), calls)
            self.assertIn("sent the car's point instead", text)

    def test_no_name_and_phone_mode_send_the_point(self):
        _, calls = self.run_with(None, r={"name": "", "lat": 32.1, "lon": 34.8})
        self.assertEqual(calls, [("geo", None, 32.1, 34.8, "32.10000, 34.80000")])
        _, calls = self.run_with(None, mode="phone")
        self.assertEqual(calls, [("geo", None, 31.768319, 35.213710, "Herzl St 10")])

    def test_distances(self):
        self.assertAlmostEqual(carsync.distance_km({"value": 800, "unit": "m"}), 0.8)
        self.assertAlmostEqual(carsync.distance_km({"value": 2, "unit": "mi"}), 3.218688)
        self.assertIsNone(carsync.distance_km({"value": 2, "unit": "parsec"}))
        self.assertTrue(carsync.same_place(17.2, 18.4))
        self.assertTrue(carsync.same_place(2.5, 0.4))     # within 3 km
        self.assertFalse(carsync.same_place(96, 18.4))
        self.assertTrue(carsync.same_place(None, 18.4))
        self.assertTrue(carsync.same_place(50, None))


class FixTest(unittest.TestCase):
    LOC = {"at": 100, "lat": 31.771959, "lon": 35.217018, "heading": 144, "valid": True, "carAt": 99930,
           "accuracy": 9999.99}

    def test_a_fresh_fix(self):
        m = json.loads(carsync.fix_message(self.LOC, {"at": 99, "speedKph": 36.0}, now=101))
        self.assertEqual(m, {"latitude": "31.7719590", "longitude": "35.2170180", "speed": "10.00", "bearing": "144.0",
                             "vertical_accuracy": "null", "timestamp": "99930", "source": "car"})

    def test_stale_or_invalid_fixes_and_stale_speed(self):
        self.assertIsNone(carsync.fix_message(self.LOC, None, now=100 + carsync.FIX_FRESH_S + 1))
        self.assertIsNone(carsync.fix_message(dict(self.LOC, valid=False), None, now=100))
        self.assertIsNone(carsync.fix_message(None, None, now=100))
        m = json.loads(carsync.fix_message(self.LOC, {"at": 50, "speedKph": 36.0}, now=100))
        self.assertEqual(m["speed"], "null")


class SettingsTest(unittest.TestCase):
    def test_defaults_and_bad_values(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "carsync.json")
            self.assertEqual(carsync.read_settings(p), {"follow": True, "carGps": False})
            with open(p, "w") as f:
                json.dump({"follow": False, "carGps": "yes"}, f)
            self.assertEqual(carsync.read_settings(p), {"follow": False, "carGps": False})
            st = os.path.join(d, "status.json")
            carsync.write_status({"sent": None}, st)
            self.assertEqual(carsync.read_status(st), {"sent": None})


if __name__ == "__main__":
    unittest.main()
