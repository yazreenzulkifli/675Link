# 675Link 1.3

Android Auto on the CFMoto 675 TFT — and other Carbit / MotoPlay dashes — built on
[OpenCfMoto](https://github.com/zanderp/open-cfmoto).

**Install:** download `675Link-v1.3.apk` below, open it, and allow the install. It updates an earlier
675Link in place (your bikes and settings stay).

## New

- **Car mode over USB (experimental)** — Android Auto on Perodua Smart Link / Carbit "Oversea" car head
  units through the USB cable. Switch the home screen to **Car**, tap **1. Get Android Auto ready**,
  then plug in. Connects hands-free and stays up through the car's stream restarts. Tested on a
  Perodua Myvi at 1280×720.
- **Ride finished reminder** — 5 minutes after a ride with no bike connected, a notification reminds you
  to turn off 675Link and Android Auto's head unit server to save battery. **Turn off** closes
  675Link; tapping the reminder shows where to tap **Stop** in Android Auto. On / Off and a test button
  in **Setup → Notifications**.
- **Google Maps or Waze** — pick your default navigation app in **Setup → Setup → Navigation app**.
  The Map button, the Destination tile, Navigate to… and the handlebar "Navigate to saved place"
  buttons all open it. If Waze is picked but not installed, Google Maps opens instead.
- **Plain Wi‑Fi QR codes** — pairs with dashes that show a standard `WIFI:` QR (ZXMOTO and others).
- **One popup design** — every popup now uses the frosted glass card with a cyan main button.
- **New home screen** — Bike / Car switch, four status tiles (Permission, Dev mode, Head unit,
  Destination), and a dock for Dash view, Garage, Map, Mirror and USB.
- **Head unit health** — the Head unit tile shows Online, Offline or **Needs restart** and opens a guide
  to start, stop or restart Android Auto's server.

## Fixed

- QR scanner: the zoom buttons no longer overlap the hint, and **Import QR from photo** is no longer
  hidden behind the navigation bar.

## Known issues

- Car mode: touch accuracy and response time on the car screen are still being tuned.

## Reminder for Android Auto 17.4+

Start the head unit server before riding (Android Auto → tap Version ~10× once → ⋮ → **Start head unit
server**) and stop it after. The home screen's **3 Head unit** tile guides you.
