# tools/debvm: the Debian trixie arm64 build VM

Builds the native Raspberry Pi OS runtime (ATL stack + tacomp) for Debian 13 trixie arm64 (glibc),
the base of stock Raspberry Pi OS Lite 64-bit. The plan behind it is `out/pios-plan/PLAN.md`
(git-ignored), sections 1-3.

The guest is the official `debian-13-genericcloud-arm64.qcow2` under QEMU/HVF, run by the same
`tools/atl-vm/vm-mac.sh` as the Alpine ATL VM, with its state in `~/teslalinux-debvm` and ssh on
127.0.0.1:2223. **Only one VM runs at a time** (8 GB Mac, 4 GB per guest): `vm-mac.sh start` and
`vm-deb.sh up` refuse while the other VM's `qemu.pid` is alive (`ATL_VM_ALLOW_TWO=1` overrides).
Nothing here stops the Alpine VM by itself; `vm-deb.sh switch-from-alpine` asks first.

```sh
D=tools/debvm/vm-deb.sh
$D init                  # download + verify the image (SHA512SUMS), 48G overlay, UEFI vars, key, seed
$D up                    # start + wait for ssh (cloud-init: user atl, 8 GB swap file, growpart)
$D provision-async       # build deps; poll: $D ssh tail -3 ~/provision.out
$D build                 # push patches + tacomp, run build-dist.sh detached; poll: $D ssh tail ~/build/build.out
$D ssh ~/build-dist.sh status
eval "$(tools/debvm/hostenv-deb.sh)"   # then the Java side, from the Mac, against this VM:
tools/atl-vm/hostbuild-setup.sh all && tools/atl-vm/hostbuild.sh && tools/atl-vm/patch-core-oj.sh
```

| File | What |
|---|---|
| `vm-deb.sh` | init / up / provision / push-patches / push-tacomp / build, the rest delegated to `vm-mac.sh` |
| `seed/` | cloud-init NoCloud seed (`user-data.tmpl`, `meta-data`) |
| `guest-provision.sh` | apt build deps (ATL Build.md's Debian list, art_standalone's README, Alpine makedepends), test runtime, `/opt/teslalinux` + `~/atl-prefix` link |
| `versions.env` | every pinned source: ATL `f4622e36` (build-atl.sh), art_standalone / bionic_translation / OpenSLES at Alpine's pins (confirm against the Alpine VM), wolfSSL 5.9.4, vixl 8.0.0, tacomp's private wayland 1.24 / libdrm 2.4.129 / xkbcommon 1.8.1 / wlroots 0.20.2 |
| `build-dist.sh` | in the guest: vixl, wolfSSL (JNI), bionic_translation + `bionic_translation-waze.sh`, art_standalone, OpenSLES, ATL + all `native-*.sh`, then tacomp's stack into `/opt/teslalinux/tacomp`; stamps per step and pin set, logs in `~/build/logs` |
| `hostenv-deb.sh` | exports that point `hostbuild.sh`, `hostbuild-setup.sh` and `patch-core-oj.sh` at this VM |

Why tacomp has its own prefix: wlroots 0.20 needs wayland >= 1.24, libdrm >= 2.4.129, xkbcommon >= 1.8
and wayland-protocols >= 1.47 (Debian's wlroots 0.20.2-1 meson.build/control); trixie has 1.23.1,
2.4.124, 1.7.0 and 1.45. Keeping them in `/opt/teslalinux/tacomp` (RUNPATH) leaves ATL on trixie's
system libraries.
