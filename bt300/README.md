# BT-300 head pose mouse bridge

This folder contains a small Android sender for Epson Moverio BT-300 and a
Debian receiver.

The sender uses Android's standard `SensorManager` motion APIs. It reads
`TYPE_GYROSCOPE` and sends raw angular velocity (`gx`, `gy`, `gz`) over UDP to
Debian.

## Build the APK

```bash
cd bt300
./build.sh
```

The output APK is:

```text
bt300/build/bt300-headmouse-debug.apk
```

Install it on the BT-300:

```bash
adb install -r build/bt300-headmouse-debug.apk
```

## Run the Debian receiver

On Debian, listening on all interfaces:

```bash
cd bt300
DISPLAY=:0 ./receiver.py --host 0.0.0.0 --port 39500 --heartbeat --verbose
```

If no packets arrive, test the receiver locally from another shell:

```bash
printf '{"gx":0.2,"gy":0.2,"gz":0.0}\n' | nc -u -w1 127.0.0.1 39500
```

The Android app must send to one of the local IPs printed by the receiver, not
to `127.0.0.1`.

The default mode maps gyroscope angular velocity to relative mouse movement.
For the tested BT-300 axis mapping:

- head left: `gy > +0.1`
- head right: `gy < -0.1`
- head up: `gx > +0.05`
- head down: `gx < -0.05`

The receiver therefore defaults to `--x-axis gy --y-axis gx --invert-x --invert-y`,
with `--x-deadzone 0.10` and `--y-deadzone 0.05`.

Useful receiver options:

```bash
DISPLAY=:0 ./receiver.py --yaw-gain 120 --pitch-gain 120
DISPLAY=:0 ./receiver.py --x-deadzone 0.12 --y-deadzone 0.06
DISPLAY=:0 ./receiver.py --no-invert-x
DISPLAY=:0 ./receiver.py --no-invert-y
DISPLAY=:0 ./receiver.py --deadzone 0.05
DISPLAY=:0 ./receiver.py --mode scroll --scroll-threshold 8
```

## Android app settings

Default target:

```text
192.168.0.12:39500
```

Change it in the app UI if Debian has a different IP.

## Notes

Android documents gyroscope motion sensors through the standard sensor API.
BT-300 should expose its gyroscope through the same Android API path.
