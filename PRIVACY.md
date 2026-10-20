# WifiDoctor privacy policy

_Last updated: 2026-10-20_

WifiDoctor diagnoses problems with the Wi-Fi network your device is connected to. This policy explains what the app accesses and what happens to it.

## Summary

- WifiDoctor has **no account, no analytics, no advertising and no crash reporting**.
- All measurement results are **stored only on your device**. Nothing is sent to the developer or to third parties.
- You can delete all stored results at any time in **Settings → Clear history**, or by uninstalling the app.

## What the app accesses and why

| Data | Why it is needed | Where it goes |
|------|------------------|---------------|
| Wi-Fi connection details: network name (SSID), router ID (BSSID), signal strength, link speed, frequency, channel | To diagnose the connection and group results per network | Stored on the device only |
| Nearby Wi-Fi networks from a Wi-Fi scan (names, router IDs, channels, signal strength, advertised country) | To measure channel congestion, find a faster band of your own network, and suggest a Wi-Fi channel permitted in your country | Used during the check. Only summary counts and the suggested channels are stored, on the device |
| Mobile network / SIM country code and device region setting | To choose which Wi-Fi channels are legal where you are | Used during the check. The country code is stored with the result, on the device |
| Optional room names you type in for walk tests | To label and rank your rooms | Stored on the device only |

Android classifies Wi-Fi network names and scan results as location information. That is why the app asks for the **location** (and, on Android 13+, **nearby Wi-Fi devices**) permission. WifiDoctor **does not use GPS**, does not access your precise or approximate coordinates, and does not request background location.

## Network connections the app makes

To measure your connection, the app connects only to:

- your router (latency probes and DNS queries),
- the internet probe targets shown in Settings (by default `1.1.1.1` and `8.8.8.8`),
- the DNS servers being compared (your network's DNS server and `1.1.1.1`),
- the speed test URL shown in Settings (by default Cloudflare's speed test endpoint). Like any web request, the operator of that server receives your IP address.

No measurement results are included in these connections.

## Data export

If you use **Export history as CSV**, the app writes a file to a location you choose. What you do with that file is up to you.

## Notifications

If you turn on scheduled checks, the app can show a notification when a problem is found. Notifications are generated on the device.

## Children

WifiDoctor is a general-purpose utility and is not directed at children.

## Changes

If this policy changes, the new version will be published at the same address with a new date.

## Contact

Questions about this policy: open an issue at https://github.com/jesperlund/WifiHealthAssistant/issues.
