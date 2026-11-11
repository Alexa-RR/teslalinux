# tools/atl-vm: aarch64 test VM for Android Translation Layer

Goal: run the Waze APK under [ATL](https://gitlab.com/android_translation_layer/android_translation_layer)
on an arm64 Linux, find what breaks, and implement it. The VM stands in for the Pi 4 (same arch, same
Mesa/llvmpipe path minus the GPU) and runs on any machine with Docker.

```
vm.sh up            # builds the atlvm-host container (x86_64 Ubuntu + qemu-system-aarch64), downloads
                    # Alpine 3.22 aarch64 cloud image, seeds cloud-init, boots, waits for ssh
vm.sh provision     # inside the guest: switch to Alpine edge, install ATL + deps from testing,
                    # headless compositor (cage/weston), Mesa, build toolchain for patching ATL
vm.sh push waze-merged.apk /home/atl/waze/waze-merged.apk
vm.sh ssh './run-waze.sh 90'     # launches Waze under ATL headless, logs + screenshots every 10 s
vm.sh pull /home/atl/waze/run-*/shot-final.png shot.png
vm.sh stop | start | status | console | destroy
```

On an Apple Silicon Mac the same commands run the guest natively (QEMU + HVF) through `vm-mac.sh`,
which `vm.sh` dispatches to on macOS. The host-side Java build is `hostbuild.sh`; `hostbuild-setup.sh`
pulls its inputs (api-impl tree, libcore class jars, R8) from the guest into `out/atl-work`.

Layout: `docker/` (host container + `vm-inner` manager), `seed/` (cloud-init), `guest-provision.sh`,
`run-waze.sh` (copied into the guest).

Why a container around QEMU: on this workstation WSL has no root and conda-forge only ships
user-mode QEMU; Docker gives root in an x86_64 Ubuntu where `qemu-system-arm` is one apt-get away.
The VM is pure TCG (no KVM for arm64 on an x86 host): roughly 1/8 of native speed per core, enough for
ATL + Waze bring-up, far too slow for benchmarking.

The Waze APK is the APKMirror split bundle merged into one APK with APKEditor
(`java -jar APKEditor.jar m -i waze.apkm -o waze-merged.apk -extractNativeLibs true`), because ATL
takes a single APK.

## Native-side patches
`patches/atl-waze/native-lifecycle.sh` (and any sibling `*.sh` placed in the guest's
`~/atl-native-patches/`) is applied by `build-atl.sh` before every ninja run. Push it with
`vm.sh push patches/atl-waze/native-lifecycle.sh /home/atl/atl-native-patches/native-lifecycle.sh`
and run `vm.sh ssh ./build-atl.sh rebuild` after changing it.
