#!/usr/bin/env python3
"""teslalinux-network's backup Wi-Fi without root, a Wi-Fi card or NetworkManager.

    python3 host/test/test_network_wifi.py

* a fake nmcli on PATH (profiles, the card's state, `connection up` outcomes, the scan list), driven by
  a JSON file the tests write; every call logged
* covered: a saved network keeps its password (root-only file) and the NetworkManager profile gets the
  password, not the WPA2 key (WPA3/SAE needs it: the cause of a phone hotspot never joining); an older
  key-only entry keeps its key; adding again without a password keeps both; 64 hex digits are a key;
  the wpa_supplicant config (no NetworkManager) offers WPA2 and SAE with the password; saving settings
  does not reconnect a connected card; Connect's results (connected with the signal, wrong password,
  not in range, needs the password for WPA3, timed out, no address, other reasons in NetworkManager's
  words); wifi-connect's refusals and the state file; wifi-state's keyOnly and cardState
"""
import importlib.machinery
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
TMP = tempfile.mkdtemp(prefix="tl-net-")
BIN = os.path.join(TMP, "bin")
FAKE = os.path.join(TMP, "fake.json")
LOG = os.path.join(TMP, "nmcli.log")
os.makedirs(BIN)
with open(os.path.join(BIN, "nmcli"), "w") as f:
    f.write(r'''#!/usr/bin/env python3
import json, sys
a = sys.argv[1:]
st = json.load(open(%r))
with open(%r, "a") as log:
    log.write(json.dumps(a) + "\n")
if not st.get("running", True):
    sys.exit(8)
if a == ["-t", "-f", "RUNNING", "general"]:
    print("running")
elif a[:5] == ["-t", "-f", "NAME", "connection", "show"]:
    print("\n".join(st.get("profiles", [])))
elif a[:2] == ["-g", "GENERAL.STATE,GENERAL.CONNECTION"]:
    print(st.get("device", "30 (disconnected)") + "\n" + st.get("connection", ""))
elif "up" in a and "connection" in a:
    out = st.get("up", {"rc": 0})
    sys.stderr.write(out.get("stderr", ""))
    sys.exit(out["rc"])
elif a[:2] == ["-t", "-f"] and "wifi" in a and "list" in a:
    print("\n".join(st.get("scan", [])))
''' % (FAKE, LOG))
os.chmod(os.path.join(BIN, "nmcli"), 0o755)
os.environ["PATH"] = BIN + os.pathsep + os.environ.get("PATH", "")
ENV = os.path.join(TMP, "env")
with open(ENV, "w") as f:
    f.write("AP_IFACE=wlan0\nAP_COUNTRY='IL'\n")
os.environ.update({
    "TESLALINUX_ENV": ENV,
    "TESLALINUX_WIFI_CLIENT": os.path.join(TMP, "wifi-client.json"),
    "TESLALINUX_WPA_CONF": os.path.join(TMP, "wpa.conf"),
    "TESLALINUX_WIFI_CONNECT_STATE": os.path.join(TMP, "run", "wifi-connect.json"),
    "TESLALINUX_WIFI_CONNECT_WAIT": "45",
})
_loader = importlib.machinery.SourceFileLoader("tlnet", os.path.join(REPO, "host", "teslalinux-network"))
_spec = importlib.util.spec_from_loader("tlnet", _loader)
net = importlib.util.module_from_spec(_spec)
_loader.exec_module(net)
net.wifi_iface = lambda cfg: "wlan1"

PHONE = "Alexa’s iPhone"  # a curly apostrophe, as iOS names its hotspot
NM_NAME = net.nm_wifi_name(PHONE)


def fake(**kw):
    st = {"running": True, "profiles": [], "device": "30 (disconnected)", "scan": ["%s:69:WPA2 WPA3:5220 MHz" % PHONE]}
    st.update(kw)
    with open(FAKE, "w") as f:
        json.dump(st, f)
    open(LOG, "w").close()


def calls():
    with open(LOG) as f:
        return [json.loads(line) for line in f if line.strip()]


def saved():
    with open(os.environ["TESLALINUX_WIFI_CLIENT"]) as f:
        return json.load(f)


def save(cfg):
    with open(os.environ["TESLALINUX_WIFI_CLIENT"], "w") as f:
        json.dump(cfg, f)


class Popen:
    """records the detached commands (nmcli device connect, wifi-connect-run) instead of running them"""
    started = []

    def __init__(self, cmd, **kw):
        Popen.started.append(cmd)


class _Subprocess:
    """the helper's subprocess module with Popen recorded (subprocess.run itself keeps the real Popen)"""
    Popen = Popen

    def __getattr__(self, name):
        return getattr(net_subprocess, name)


net_subprocess = net.subprocess
net.subprocess = _Subprocess()


class Profiles(unittest.TestCase):
    def setUp(self):
        for p in (os.environ["TESLALINUX_WIFI_CLIENT"], os.environ["TESLALINUX_WPA_CONF"]):
            if os.path.exists(p):
                os.remove(p)
        Popen.started.clear()

    def profile_settings(self):
        c = [a for a in calls() if a[:2] in (["connection", "add"], ["connection", "modify"]) and "802-11-wireless.ssid" in a]
        self.assertTrue(c, calls())
        a = c[-1]
        return {a[i]: a[i + 1] for i in range(a.index("802-11-wireless.ssid"), len(a) - 1, 2)}

    def test_password_kept_and_given_to_networkmanager(self):
        fake()
        net.apply_wifi({"add": {"ssid": PHONE, "psk": "hunter22"}})
        n = saved()["networks"][0]
        self.assertEqual(n["passphrase"], "hunter22")
        self.assertEqual(n["psk"], net.wpa_psk(PHONE, "hunter22"))
        self.assertEqual(oct(os.stat(os.environ["TESLALINUX_WIFI_CLIENT"]).st_mode & 0o777), "0o600")
        s = self.profile_settings()
        self.assertEqual((s["wifi-sec.key-mgmt"], s["wifi-sec.psk"], s["wifi-sec.pmf"]), ("wpa-psk", "hunter22", "default"))
        self.assertEqual(s["802-11-wireless.ssid"], PHONE)
        self.assertIn(["nmcli", "--wait", "30", "device", "connect", "wlan1"], Popen.started)

    def test_key_only_entry_and_readd_without_password(self):
        key = net.wpa_psk(PHONE, "hunter22")
        save({"enabled": True, "prefer": "wifi", "iface": "", "networks": [{"ssid": PHONE, "psk": key, "hidden": False}]})
        fake(profiles=[NM_NAME], device="100 (connected)", connection=NM_NAME)
        net.apply_wifi({"prefer": "wifi"})
        self.assertEqual(self.profile_settings()["wifi-sec.psk"], key)
        self.assertEqual(Popen.started, [])  # a connected card is not reconnected by saving settings
        net.apply_wifi({"add": {"ssid": PHONE, "psk": "hunter22"}})
        net.apply_wifi({"add": {"ssid": PHONE, "psk": ""}})  # again without a password: both kept
        self.assertEqual((saved()["networks"][0]["passphrase"], saved()["networks"][0]["psk"]), ("hunter22", key))
        net.apply_wifi({"add": {"ssid": PHONE, "psk": "a" * 64}})  # 64 hex digits are a key
        self.assertNotIn("passphrase", saved()["networks"][0])
        out = io.StringIO()
        sys.stdout, real = out, sys.stdout
        try:
            net.cmd_wifi_state()
        finally:
            sys.stdout = real
        st = json.loads(out.getvalue())
        self.assertEqual((st["networks"][0]["keyOnly"], st["cardState"]), (True, "connected"))
        self.assertNotIn("passphrase", out.getvalue())

    def test_wpa_supplicant_config(self):
        fake(running=False)
        net.apply_wifi({"add": {"ssid": "Garage", "psk": "old-key-net"}})
        cfg = saved()
        cfg["networks"][0].pop("passphrase")
        save(cfg)
        net.apply_wifi({"add": {"ssid": PHONE, "psk": 'pa"ss word'}})
        with open(os.environ["TESLALINUX_WPA_CONF"]) as f:
            conf = f.read()
        phone, garage = conf.split("network={")[1:]
        self.assertIn('\tkey_mgmt=WPA-PSK SAE\n\tpsk="pa"ss word"\n\tieee80211w=1', phone)
        self.assertIn("\tssid=%s" % PHONE.encode().hex(), phone)
        self.assertIn("\tkey_mgmt=WPA-PSK\n\tpsk=%s" % net.wpa_psk("Garage", "old-key-net"), garage)
        self.assertIn("country=IL", conf)


class Connect(unittest.TestCase):
    def setUp(self):
        save({"enabled": True, "prefer": "lte", "iface": "",
              "networks": [{"ssid": PHONE, "psk": net.wpa_psk(PHONE, "hunter22"), "passphrase": "hunter22", "hidden": False}]})
        Popen.started.clear()

    def run_attempt(self, **kw):
        fake(**kw)
        net.cmd_wifi_connect_run(PHONE)
        with open(os.environ["TESLALINUX_WIFI_CONNECT_STATE"]) as f:
            return json.load(f)

    def test_connected(self):
        st = self.run_attempt(up={"rc": 0})
        self.assertEqual((st["state"], st["result"], st["signalPercent"]), ("connected", "connected", 69))
        up = [a for a in calls() if "up" in a][0]
        self.assertEqual(up, ["--wait", "45", "connection", "up", "id", NM_NAME, "ifname", "wlan1"])
        self.assertNotIn("pid", st)

    def test_failures_in_plain_words(self):
        err = "Error: Connection activation failed: %s\nHint: use 'journalctl -xe NM_CONNECTION=x' to get more details.\n"
        st = self.run_attempt(up={"rc": 4, "stderr": err % "Secrets were required, but not provided"})
        self.assertEqual((st["state"], st["result"]), ("failed", "wrong-password"))
        self.assertIn("did not accept the password (Secrets were required, but not provided)", st["message"])
        st = self.run_attempt(up={"rc": 4, "stderr": err % "The Wi-Fi network could not be found"}, scan=[])
        self.assertEqual(st["result"], "not-found")
        self.assertIn("not in range", st["message"])
        st = self.run_attempt(up={"rc": 3, "stderr": "Error: Timeout expired (45 seconds)\n"})
        self.assertEqual(st["result"], "timeout")
        st = self.run_attempt(up={"rc": 4, "stderr": err % "IP configuration could not be reserved (no available address, timeout, etc.)"})
        self.assertEqual(st["result"], "no-address")
        st = self.run_attempt(up={"rc": 4, "stderr": err % "The Wi-Fi network could not be found"})
        self.assertEqual(st["result"], "failed")
        self.assertIn("(The Wi-Fi network could not be found)", st["message"])
        cfg = saved()
        cfg["networks"][0].pop("passphrase")
        save(cfg)
        st = self.run_attempt(up={"rc": 4, "stderr": err % "The Wi-Fi network could not be found"})
        self.assertEqual(st["result"], "needs-password")
        self.assertIn("WPA3", st["message"])

    def start(self, ssid):
        sys.stdin = io.StringIO(json.dumps({"ssid": ssid}))
        out = io.StringIO()
        sys.stdout, real = out, sys.stdout
        try:
            net.cmd_wifi_connect()
        finally:
            sys.stdout, sys.stdin = real, sys.__stdin__
        return json.loads(out.getvalue())

    def test_start_and_refusals(self):
        fake()
        self.assertEqual(self.start("Unknown"), {"ok": False, "message": "That network is not saved"})
        self.assertEqual(Popen.started, [])
        r = self.start(PHONE)
        self.assertEqual(r, {"ok": True, "message": "Connecting"})
        self.assertEqual(Popen.started[0][-2:], ["wifi-connect-run", PHONE])
        with open(os.environ["TESLALINUX_WIFI_CONNECT_STATE"]) as f:
            self.assertEqual(json.load(f)["state"], "connecting")
        cfg = saved()
        cfg["enabled"] = False
        save(cfg)
        self.assertEqual(self.start(PHONE)["message"], "Backup Wi-Fi is switched off")


if __name__ == "__main__":
    unittest.main(verbosity=1)
