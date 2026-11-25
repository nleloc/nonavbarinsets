# NoNavbarInsets

Hide navigation bar insets from WindowManagerService so target apps stay untouched while can still toggle per-app

## Requirements
- Android 11~17 (i think)
- Modern Xposed framework supporting libxposed API 101+

## Building
- Clone this repo
- Run `./gradlew assembleRelease`

## Setup
1. Enable the module in your Xposed manager (only tick `system`, please)
2. Reboot
3. Add the `Spoof navbar` Quick Settings tile, or manage via config file

## Config
### Via the config file
Packages are stored one per line in `/data/system/nonavbarinsets/list.txt` and reloaded automatically

- To view current list:
  ```sh
  cat /data/system/nonavbarinsets/list.txt
  ```

- To add a package:
  ```sh
  echo com.example.app >> /data/system/nonavbarinsets/list.txt
  ```

### Via the Quick Settings Tile
- Open your apps and trigger the `Spoof navbar` tile, that's it!

## DISCLAIMER
> [!IMPORTANT]
> I'm not responsible for anything that can happen on your device by using this module. \
> Including bricked devices, data loss, or accidentally trigger a thermonuclear war.
