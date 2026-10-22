# Endpoint TLS probe for Android

`HomeTlsProbe.java` checks an HTTPS endpoint through the phone's current default
route. It uses Android's default TLS trust and hostname verification. It records
full exception causes/stacks, certificate validity/fingerprints, DNS answers,
connected socket addresses, timing and whether the local socket belongs to a TUN.
It does not select profiles, change VPN configuration or disable certificate checks.

Compile against Android SDK's android.jar with javac, then convert the generated
classes to a DEX JAR with Android build-tools d8. Run the probe using the shell UID:

```sh
adb -s DEVICE push home-tls-probe.jar /data/local/tmp/home-tls-probe.jar
adb -s DEVICE shell 'CLASSPATH=/data/local/tmp/home-tls-probe.jar app_process /system/bin HomeTlsProbe https://service.example.invalid/ 5 500'
```

Arguments: HTTPS URL, request count, pause in milliseconds. Use a bounded count
such as 5 and save JSON lines to protected local storage. Output contains private
endpoint, certificate and network metadata; do not publish raw output. Remove the
temporary JAR after use. Device selection and network changes belong to the caller.

A Java SSLHandshakeException alone does not identify a certificate problem. Compare
its cause with destination availability and origin service logs before attributing
an outage to the VPN. An unavailable origin can close the proxied TCP connection
before a TLS session exists.
