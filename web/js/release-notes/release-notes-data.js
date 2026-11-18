// Generated from CHANGELOG.md by tools/changelog/changelog.py page. Do not edit by hand:
// change CHANGELOG.md and run the script again (tools/debvm/pack-dist.sh checks it).
(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;

  TAHtml.releaseNotesData = {
    "versions": [
      {
        "versionName": "Unreleased",
        "changelogItems": [
          {
            "title": "Voice search",
            "shortDescription": "",
            "descriptionMarkdown": "In Hebrew or English, with the microphone and speech recognition of the browser the car page is open in.\n\n• In the car interface, the search field has a microphone button: speak a place, the words appear as they are heard and Waze searches when you stop speaking. It works while the car moves, when typing is off.\n• Settings > Waze interface: \"Voice language\" (Hebrew or English, kept by each browser) and \"Test the microphone\", which listens once and shows what it heard. A browser without speech recognition says so there."
          },
          {
            "title": "GPU speed",
            "shortDescription": "Settings > Device > GPU speed.",
            "descriptionMarkdown": "Waze's map is drawn by the Pi's graphics chip, and that is what limits how smoothly it moves. The chip can now run faster: Standard (500 MHz, the Pi's own), Faster (600 MHz, needs a heatsink) or Fastest (700 MHz, needs a heatsink and a fan; it also raises the chip's voltage a little). The choice applies at the next restart; the tile shows the chip's clock and temperature now, and says when the Pi has slowed itself down because of heat."
          },
          {
            "title": "Waze on a real Raspberry Pi",
            "shortDescription": "",
            "descriptionMarkdown": "The first install on Raspberry Pi hardware (a Pi 4 with a fresh Raspberry Pi OS); everything before was tested in the build VM, which hid all of these.\n\n• Waze 5.27, the version a new install and the daily update now download, closed about 15 seconds after every start. It runs again.\n• The screen stayed black: the Pi's hardware picture encoder never answered, and the stream stopped for good. It now gets the picture in a form it can read, and if it ever fails, the Pi switches to its processor for the picture instead of stopping.\n• Waze could not reach any of its servers (\"certificate verify failed\"), and once it could, it crashed on its second connection to a server. Apps now make secure connections with Android's own component for them (Conscrypt), as on a phone: they work, a connection to a server already seen is set up in about half the time and a third of the processor time, and the faster encryption for the Pi's processor is used.\n• Waze waited on IPv6 connections that lead nowhere on some networks (a home router with only a local IPv6 prefix) and gave up. The Pi now uses IPv4 only.\n• The car's position is sent to Waze from the start (it was off until switched on in Settings).\n• In Phone mode, the map beside search results, a place card or a route stayed black for 3 to 7 seconds every time it opened. It now shows within a second.\n• In Phone mode, \"Connect calendar\" froze Waze: the screen stayed on its last picture and nothing answered. Screens that open over Waze's map without covering it (Waze's permission and calendar screens) now leave the map running underneath, as on a phone.\n• White was light grey in the picture from the Pi's hardware encoder. The colours are now exact, and making each picture takes the Pi half the processor time it did.\n• Waze's map is smoother: about 26 pictures a second instead of 21 at the full 1088x832 (Waze idle on its map). The map now goes to the screen as it is, instead of being drawn again under Waze's panels for every picture."
          },
          {
            "title": "The hotspot and the car page come up sooner",
            "shortDescription": "Measured on a Raspberry Pi 4 with a fresh Raspberry Pi OS install.",
            "descriptionMarkdown": "• The car hotspot could come up without its address (172.16.0.1): the address was set before the Wi-Fi chip was ready, so the car could join the network but not reach the page. It now waits for the Wi-Fi chip.\n• The Pi boots in 12 seconds instead of 22; the hotspot is up about 8 seconds after power-on and the car page right after it, instead of about 21.\n• Waze's first start after an install no longer builds the runtime's core library image again.\n• Waze's first start after an install or a Waze update no longer spends about three minutes preparing Waze's code: the installer and the updater do it beforehand (measured: 199 s during the install, none at the first start).\n• An LTE stick that works as a network card (a Huawei HiLink stick such as the E3372) got no address on a Pi set up with Raspberry Pi Imager, so the Pi had no mobile internet and kept resetting the stick. It now connects by itself (tested: E3372 on 4G)."
          },
          {
            "title": "Panel size",
            "shortDescription": "Settings > Waze interface > Panel size, in Car mode.",
            "descriptionMarkdown": "The panels and buttons the car page draws over the map (turn card, trip card, search, lists, the route preview, the map buttons) can now be smaller, or a little larger: Smallest, Smaller, Default or Larger. The screen redraws at once, and Waze keeps its car and route in the map area the panels leave free. Waze's map keeps its own size (see Map size). Each browser keeps its own choice."
          },
          {
            "title": "Map size",
            "shortDescription": "Settings > Waze interface > Map size, in Car mode.",
            "descriptionMarkdown": "Waze's street names, road signs, report icons, position arrow and speedometer can now be drawn larger (or smaller) on the map, to match the car page's large panels: Smaller, Default, Larger or Largest. The map changes at once, without restarting Waze; the speedometer, which Waze sizes only when it creates it, follows the next time Waze starts. The panels on the screen keep their size. The browser keeps the choice, and every car page that is open follows the latest one."
          }
        ]
      },
      {
        "versionName": "v0.2.0 · 2026-11-12",
        "changelogItems": [
          {
            "title": "Car mode",
            "shortDescription": "Settings > Waze interface > Car.",
            "descriptionMarkdown": "Choose Car and only Waze restarts, in a few seconds; your places and settings are the same in both modes, and every open car page follows the change by itself. Waze then draws only its map and sends every other screen the way it does to a car display; the car page draws them over the map. Choose Phone to go back.\n\n• Before driving: the Drive now panel with recent places, saved places behind the bookmark button, and search with live results as you type; the place card (opening hours, rating, address, distance); the route preview with alternate routes and their times, and Go now with its countdown.\n• Driving: a turn card with the maneuver, the distance, the road name and the lanes, and the step after it (\"Then\"); the trip estimate (minutes, arrival time, distance) with a button to end the drive; Waze's spoken prompts as before.\n• Settings and messages: Waze's car settings, lists with sections, switches and choices, paging on long lists, alerts and short messages.\n• The map: touches still reach Waze directly (drag, pinch and tap work as in Phone mode); the map buttons (zoom, recentre) fold into one ± button over the bare map; when a panel covers the turn card, the next step shows as a small pill.\n• While the car moves, as on any car display: long lists are shortened and typing in search is turned off (Waze's own driving restrictions, from the car's speed)."
          },
          {
            "title": "Day and night",
            "shortDescription": "Settings > Waze interface > Day and night.",
            "descriptionMarkdown": "Automatic (the default) follows the sun at the car's position: the map turns dark after sunset and light again in the morning. Day and Night fix it. Waze's map and the Graphite panels switch at the same moment."
          },
          {
            "title": "Graphite look (experimental)",
            "shortDescription": "Settings > Waze interface > Car interface style.",
            "descriptionMarkdown": "Rounded, the default, draws dark rounded panels. Graphite is flat and quieter: frosted panels with a soft edge, light by day and dark by night, in the Inter typeface; the page's own mute and settings buttons follow it. Graphite is experimental in this release: its colours and details may still change, and a few things Waze draws itself keep Waze's colours (see Known issues).\n\n• Driving: one turn card with the lanes and the step after it, and a trip card with the arrival time, the minutes and distance left, and End drive.\n• The map buttons stand in slim frosted columns at the right edge; search opens as a card over the live map, with the keyboard under it; lists have thin dividers between rows.\n• The choice is kept by the browser, like the display settings, and applies at once."
          },
          {
            "title": "Map colours",
            "shortDescription": "Settings > Waze interface > Map colours, with the Graphite look.",
            "descriptionMarkdown": "• Muted (the default) shows Waze's map in calm neutral greys with the route in Graphite's blue, by day and by night; traffic, speed limits, report pins and the speedometer keep their colours. Only the car page's picture changes.\n• Graphite: Waze draws its map in Graphite's own palette (neutral land and roads, the route in Graphite's blue), day and night. Waze's files are never changed: when Waze starts, it is handed a recoloured copy of its colour file in memory, and only for Waze versions verified with this palette (today 5.25.0.1). For any other version Settings says so and the map falls back to Muted. Switching Graphite on or off restarts Waze.\n• Waze's own shows the map exactly as Waze draws it.\n\nPhone mode always shows Waze's own colours."
          },
          {
            "title": "Release notes in the car",
            "shortDescription": "This page now shows Teslalinux's own releases.",
            "descriptionMarkdown": "The Release Notes page used to show the history of Tesla Android, the project Teslalinux is forked from. It now lists Teslalinux's releases, generated from the project's changelog (CHANGELOG.md)."
          },
          {
            "title": "Smaller fixes",
            "shortDescription": "Things that were in the way.",
            "descriptionMarkdown": "• In Car mode, the page's mute and settings buttons no longer cover the top of Waze's buttons at the top right: those now start just below them.\n• The notice about a temporary certificate no longer covers the first buttons of the bottom bar on the app pages, and in Car mode it sits at the bottom centre, clear of End drive."
          },
          {
            "title": "Known issues",
            "shortDescription": "What does not work yet.",
            "descriptionMarkdown": "• Car mode: Waze 5.25 sends no arrival screen; at the destination the drive simply ends and the map comes back, without the arrival summary some car displays show.\n• Rounded look: the panels keep their dark style in daytime; only the map and its buttons switch to day colours.\n• Graphite look (experimental): the route bubbles in route preview (\"Best\" and the current route) keep Waze's own colours, purple by day and cyan by night, because Waze sets them in its code, not in its map colours; the position arrow keeps Waze's light blue for the same reason.\n• Muted map colours need the h264 renderer (Settings > Display > Renderer); with MJPEG the map keeps Waze's own colours.\n• Waze keeps redrawing its map while nothing moves, so the display stream sends frames even when the picture is still (more CPU and Wi-Fi use than needed).\n• Phone mode: the Account and login screen does not scroll.\n• Some outlines drawn by the app may be missing: Paint.getFillPath (stroke to outline) is not implemented yet."
          }
        ]
      },
      {
        "versionName": "v0.1.2 · 2026-11-11",
        "changelogItems": [
          {
            "title": "Certificate updates in a few bytes",
            "shortDescription": "The Pi downloads the car's certificate only when it changes.",
            "descriptionMarkdown": "The issuer publishes a small DNS record with the fingerprints of the current certificates. The Pi checks it every hour (about 200 bytes instead of 11 KB every 6 hours) and downloads only a slot that changed, verified exactly as before. A full download still happens at most every 6 hours as a fallback, and whenever the served certificate is in the last quarter of its life."
          },
          {
            "title": "LTE that recovers by itself",
            "shortDescription": "A stuck LTE stick is brought back without unplugging it.",
            "descriptionMarkdown": "A Huawei E3372 (HiLink) that lost coverage often never reconnects until it is plugged in again. When LTE has coverage but no internet for 90 seconds, the Pi now steps up one action at a time: mobile data off and on, restart the stick, re-enumerate it on USB, and as a last resort power cycle the USB port (by default only when the stick is the only USB device). At least 2 minutes between steps, 15 minutes after a full round, and nothing at all without coverage, with a SIM problem, while a preferred Wi-Fi works, or with LTE_RECOVERY=off. ModemManager modems get the same steps.\n\nThe car page's Status shows the stick: operator, network type, signal bars and radio values, connection and roaming, data this session and this month, SIM problems and the last recovery action."
          },
          {
            "title": "Waydroid is gone",
            "shortDescription": "Waze runs under ATL on plain Raspberry Pi OS, nothing else.",
            "descriptionMarkdown": "The old Android-container design (Waydroid, LXC, its network bridge and boot flags) is removed from the installer, the services and the docs. There is no Android image and no container on the Pi."
          }
        ]
      },
      {
        "versionName": "v0.1.1 · 2026-11-11",
        "changelogItems": [
          {
            "title": "One address for every car",
            "shortDescription": "https://car.alexahub.dev/ works out of the box.",
            "descriptionMarkdown": "The Tesla browser opens only pages with a publicly trusted certificate, and the connection ends on the Pi. Every Pi now serves the same certificate for car.alexahub.dev (as Tesla Android does with its images), renewed automatically by this project and kept up to date on the Pi without a new release. A new installation works in the car right away: the release carries the current certificate. No per-device key, request or --tls-key option any more."
          },
          {
            "title": "Hebrew text and the compass",
            "shortDescription": "Fixes in the app runtime.",
            "descriptionMarkdown": "• Text in Hebrew (and other scripts) now uses Android's font fallback order, so it is drawn in Noto Sans Hebrew like on a phone.\n• Waze's compass sometimes drew empty after a size change; views are now invalidated when their size changes, as on Android."
          }
        ]
      },
      {
        "versionName": "v0.1.0 · 2026-11-11",
        "changelogItems": [
          {
            "title": "First public release",
            "shortDescription": "Waze in the Tesla browser, from a Raspberry Pi 4 running plain Linux.",
            "descriptionMarkdown": "The car joins the Pi's Wi-Fi hotspot and opens the car page. The page shows Waze's screen, streamed by a small Wayland compositor, sends your touches back, and passes the car's GPS position and Waze's sound between the car and the Pi. Waze runs under the Android Translation Layer (Android's framework reimplemented on GTK 4 with a standalone ART runtime): no Android image, no Google Play services, no container.\n\n• Waze end to end: map, search with the on-screen keyboard or the car's keyboard, places, routes, turn-by-turn navigation with voice prompts, settings and dialogs.\n• The car's GPS drives Waze's position, with the car's speed and heading.\n• Sound: Waze's voice plays in the car's browser.\n• Internet through a USB LTE stick, backup Wi-Fi or Ethernet, with the hotspot for the car."
          },
          {
            "title": "Install with one command",
            "shortDescription": "Raspberry Pi OS Lite 64-bit, nothing compiled on the Pi.",
            "descriptionMarkdown": "install.sh from the release sets up the hotspot, the LTE modem, the web page and Waze (--waze apkpure downloads it, --apk FILE installs a bundle you downloaded), keeps a backup of every system file it replaces, and can be run again at any time. Afterwards teslalinux doctor | status | rollback | uninstall."
          },
          {
            "title": "Waze updates itself",
            "shortDescription": "Daily checks, verified downloads, rollback.",
            "descriptionMarkdown": "The Pi checks for a new Waze every day, downloads it only on unmetered connections (or over LTE if you allow it), verifies that it carries Waze's original signature, and installs it when the car is not connected or at the next boot, with a health check and an automatic rollback. You can also upload an app bundle from the car page (Settings > App updates)."
          },
          {
            "title": "The car page",
            "shortDescription": "Tesla Android's page, as Teslalinux.",
            "descriptionMarkdown": "The car page is Tesla Android's web app with Teslalinux's name and settings: Status, Display, Wi-Fi and LTE, Audio, GPS, Language and region, Keyboard and App updates."
          }
        ]
      }
    ]
  };
})(window);
