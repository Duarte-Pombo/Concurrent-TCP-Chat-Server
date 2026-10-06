#!/bin/bash
echo "yes" | keytool -genkeypair -alias chat -keyalg RSA -keysize 2048 -validity 365 \
  -keystore keystore.jks -storepass changeit -keypass changeit \
  -dname "CN=localhost"
echo "Keystore generated: keystore.jks"