#!/usr/bin/env bash
set -euo pipefail

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  openjdk-11-jdk wget unzip git curl ca-certificates

# Install Android commandline tools
ANDROID_SDK_ROOT=/opt/android-sdk
mkdir -p ${ANDROID_SDK_ROOT}/cmdline-tools
cd /tmp
if [ ! -f cmdline.zip ]; then
  wget -O cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip
fi
unzip -q cmdline.zip -d ${ANDROID_SDK_ROOT}/cmdline-tools
if [ -d "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" ]; then
  mv ${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools ${ANDROID_SDK_ROOT}/cmdline-tools/latest || true
fi
export PATH=${PATH}:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools

yes | ${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager --sdk_root=${ANDROID_SDK_ROOT} --licenses || true
${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager --sdk_root=${ANDROID_SDK_ROOT} "platform-tools" "platforms;android-33" "build-tools;33.0.0"

# Make SDK available to vscode user
chown -R vscode:vscode ${ANDROID_SDK_ROOT} || true
echo "export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT}" >> /home/vscode/.bashrc
echo "export PATH=\$PATH:${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:${ANDROID_SDK_ROOT}/platform-tools" >> /home/vscode/.bashrc

echo "Dev container setup complete. You may need to re-open the folder in container for environment to apply."
