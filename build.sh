#!/bin/bash
export ANDROID_HOME=$HOME/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools
wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O tools.zip
unzip -q tools.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
rm tools.zip
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
yes | sdkmanager --licenses > /dev/null 2>&1
yes | sdkmanager "platforms;android-34" "build-tools;34.0.0" > /dev/null 2>&1
echo "sdk.dir=$ANDROID_HOME" > local.properties

if [ -f debug.keystore.base64 ]; then
  base64 -d debug.keystore.base64 > debug.keystore 2>/dev/null || true
fi
if ! keytool -list -keystore debug.keystore -storepass android -alias androiddebugkey > /dev/null 2>&1; then
  rm -f debug.keystore
  keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
if [ ! -f .env ] && [ -f .env.example ]; then
  cp .env.example .env
fi

gradle assembleDebug
