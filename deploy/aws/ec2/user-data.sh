#!/bin/bash
# EC2 user data: runs once, as root, on the instance's first boot.
# Output goes to /var/log/cloud-init-output.log on the instance.
set -euo pipefail

# Docker from Amazon Linux 2023's own repository, started now and on every boot.
dnf install -y docker
systemctl enable --now docker

# Amazon Linux 2023 doesn't package the Compose plugin, so take it from
# Docker's GitHub releases and check it against the published checksum.
mkdir -p /usr/local/lib/docker/cli-plugins
cd /usr/local/lib/docker/cli-plugins
curl -fsSLO https://github.com/docker/compose/releases/latest/download/docker-compose-linux-aarch64
curl -fsSLO https://github.com/docker/compose/releases/latest/download/docker-compose-linux-aarch64.sha256
sha256sum -c docker-compose-linux-aarch64.sha256
mv docker-compose-linux-aarch64 docker-compose
chmod +x docker-compose
rm docker-compose-linux-aarch64.sha256

# 1 GiB of swap, so a memory spike in Java or Postgres slows down instead of
# getting a process killed on the 2 GiB machine.
dd if=/dev/zero of=/swapfile bs=1M count=1024
chmod 600 /swapfile
mkswap /swapfile
swapon /swapfile
echo '/swapfile swap swap defaults 0 0' >> /etc/fstab
