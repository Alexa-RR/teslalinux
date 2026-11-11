# host/rpi4

Firmware-level boot configuration shared by every phase.

* `config.txt`: direct kernel boot (no U-Boot), zero firmware delays, VPU + v3d enabled for the
  encoder and Mesa, every unused peripheral probe disabled.
* `cmdline.txt`: `psi=1` is **required** by Waydroid (PSI is compiled in but default-disabled on the Pi
  kernel). cgroup v1 memory/cpuset flags are needed by LXC on older userspace; harmless on cgroup v2.
  Replace `PARTUUID=CHANGEME-02` with the real root partition (provision.sh does it).

Phase 1 copies these over Pi OS's defaults. Phase 2's Buildroot `post-image.sh` writes them into the
boot partition and additionally builds the cmdline into the kernel (`CONFIG_CMDLINE_FORCE`).
