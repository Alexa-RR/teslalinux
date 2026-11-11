# teslalinux ATL test VM host: an x86_64 container that runs the aarch64 QEMU system emulator.
# We need root to apt-install qemu-system-aarch64; Docker gives us that without touching the host.
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update -q && apt-get install -y -q --no-install-recommends \
      qemu-system-arm qemu-efi-aarch64 qemu-utils \
      openssh-client python3 curl ca-certificates sshpass genisoimage \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /vm
COPY vm-inner.sh /usr/local/bin/vm-inner
RUN chmod +x /usr/local/bin/vm-inner
CMD ["sleep", "infinity"]
