// Generated from CHANGELOG.md by tools/changelog/changelog.py page. Do not edit by hand:
// change CHANGELOG.md and run the script again (tools/debvm/pack-dist.sh checks it).
(function (global) {
  "use strict";

  const TAHtml = global.TAHtml;

  TAHtml.releaseNotesData = {
    "versions": [
      {
        "versionName": "v0.2.0 · 2026-11-12",
        "changelogItems": [
          {
            "title": "Car interface",
            "shortDescription": "Waze's car screens, drawn in the car's browser.",
            "descriptionMarkdown": "Settings > Waze interface > Car starts Waze without its phone screens. Waze then draws only its map, and sends every other screen the way it does to a car display; the car page draws them over the map in its own rounded dark style, sized for a touch screen at arm's length.\n\n• Driving: a turn card with the maneuver, the distance, the road name and the lanes, and the step after it (\"Then\"); the trip estimate (minutes, arrival time, distance) with a button to end the drive; Waze's spoken prompts as before.\n• Before driving: the Drive now panel with recent places, saved places behind the bookmark button, and search with live results as you type; the place card (opening hours, rating, address, distance); the route preview with alternate routes and their times, and Go now with its countdown.\n• Waze's car settings, lists with sections, switches and choices, paging on long lists, alerts and short messages, the map buttons (zoom, recentre) folded into one ± button over the bare map, and the next step as a small pill when a panel covers the turn card.\n• While the car moves, as on any car display: long lists are shortened and typing in search is turned off (Waze's own driving restrictions, from the car's speed).\n• Day and night: the map turns dark after sunset at the car's position and light again in the morning.\n• Touches on the map still reach Waze directly: drag, pinch and tap work as in Phone mode."
          },
          {
            "title": "Graphite, a second look for the car interface",
            "shortDescription": "Settings > Waze interface > Car interface style.",
            "descriptionMarkdown": "Rounded (the default) keeps the dark rounded panels. Graphite is flat and quieter: frosted panels, light by day and dark by night, in the Inter typeface.\n\n• Driving: one turn card with the lanes and the step after it, and a trip card with the arrival time, the minutes and distance left, and End drive.\n• The map buttons stand in slim frosted columns at the right edge; search opens as a card over the live map, with the keyboard under it; lists have thin dividers between rows.\n• The choice is kept by the browser, like the display settings, and applies at once."
          },
          {
            "title": "Day and night",
            "shortDescription": "Settings > Waze interface > Day and night.",
            "descriptionMarkdown": "Automatic (the default) follows the sun at the car's position, as before; Day and Night fix it. Waze's map follows the choice, and so do the Graphite panels: the car page now hears when the car switches between day and night, at the same moment as Waze."
          },
          {
            "title": "Map colours",
            "shortDescription": "Settings > Waze interface > Map colours, with Graphite.",
            "descriptionMarkdown": "• Graphite: Waze draws its map in Graphite's own palette (neutral land and roads, the route in Graphite's blue), day and night. Waze's files are never changed: when Waze starts, it is handed a recoloured copy of its colour file in memory, and only for Waze versions verified with this palette (today 5.25.0.1). For any other version Settings says so and the map falls back to Muted. Switching Graphite on or off restarts Waze.\n• Muted (the default) shows Waze's map in calm neutral greys with the route in Graphite's blue, by day and by night; traffic, speed limits, report pins and the speedometer keep their colours. Only the car page's picture changes.\n• Waze's own shows the map exactly as Waze draws it.\n\nPhone mode always shows Waze's own colours."
          },
          {
            "title": "Smaller fixes",
            "shortDescription": "Things that were in the way.",
            "descriptionMarkdown": "• In Car mode, the page's mute and settings buttons no longer cover the top of Waze's buttons at the top right: those now start just below them.\n• The notice about a temporary certificate no longer covers the first buttons of the bottom bar on the app pages, and in Car mode it sits at the bottom centre, clear of End drive."
          },
          {
            "title": "Phone or Car, your choice",
            "shortDescription": "Settings > Waze interface.",
            "descriptionMarkdown": "Phone is the default. Switching restarts only Waze (a few seconds; your places and settings are the same in both), and every open car page follows the change by itself."
          },
          {
            "title": "Release notes in the car",
            "shortDescription": "This page now shows Teslalinux's own releases.",
            "descriptionMarkdown": "The Release Notes page used to show the history of Tesla Android, the project Teslalinux is forked from. It now lists Teslalinux's releases, generated from the project's changelog (CHANGELOG.md)."
          },
          {
            "title": "Known issues",
            "shortDescription": "What does not work yet.",
            "descriptionMarkdown": "• Waze 5.25 sends no arrival screen: at the destination the drive simply ends and the map comes back, without the arrival summary some car displays show.\n• Waze keeps redrawing its map while nothing moves, so the display stream sends frames even when the picture is still (more CPU and Wi-Fi use than needed).\n• In Phone mode, the Account and login screen does not scroll.\n• Some outlines drawn by the app may be missing: Paint.getFillPath (stroke to outline) is not implemented yet.\n• With the Rounded look, the panels keep their dark style in daytime; only the map and its buttons switch to day colours (Graphite switches its panels too).\n• Muted map colours need the h264 renderer (Settings > Display > Renderer); with MJPEG the map keeps Waze's own colours."
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
