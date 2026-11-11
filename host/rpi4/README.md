# host/rpi4

Firmware-level boot configuration for the Raspberry Pi 4.

* `config.txt`: direct kernel boot (no U-Boot), zero firmware delays, VPU + v3d enabled for the
  V4L2 M2M encoder, tacomp and the app's GL rendering, every unused peripheral probe disabled.
* `cmdline.txt`: `quiet loglevel=3` and `systemd.show_status=0` keep the console out of the boot
  path; `coherent_pool=1M pcie_aspm=off` are upstream Tesla Android's settings (USB/PCIe, see
  `docs/UPSTREAM-NOTES.md`). Replace `PARTUUID=CHANGEME-02` with the real root partition
  (`host/phase1-pios/provision.sh` does it).

`host/phase1-pios/provision.sh` copies both files over Pi OS's defaults. The one-command installer
(`tools/pios/install.sh`) does not replace them: it appends a marked block with the fast-boot and
codec settings to the existing `config.txt` and only adds `quiet loglevel=3` to the command line.
The Alpine image (`tools/image/build-alpine-pi.sh`) derives its `config.txt` from this one and
writes its own command line.
