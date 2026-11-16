# Rooms

Your [Home Assistant](https://www.home-assistant.io) rooms on the TV, in the launcher's look: the launcher's own
wallpapers, a clock-style line with the indoor temperature and the weather, and one tile per room (Home Assistant's
areas) with what is on there. A room with a light or the aircon on glows warm; one with a door open or a lock open
turns amber. OK opens the room: OK switches a light, ◀ ▶ dim it or move a thermostat by half a degree. Blinds, shutters and
garage doors (`cover.*`): OK opens what is closed, closes what is open and stops one that is moving; ◀ ▶ move one that
reports its position by 10 %. Open shutters count in the room's line; an open garage door or gate turns it amber.
Aircon zone dampers are listed but never counted. Locks are shown,
never opened. A community add-on, not made by or affiliated with the Home Assistant project.

Built from the [Smart Home](../smart-home/) add-on (@maxhayim): the same Home Assistant client, config push and
filters, with a room screen in place of its list. The room screen follows
[HA Calm TV](https://github.com/NeoCarlos/ha-calm-tv) (MIT, Carlos Montero), whose sofa, cutlery, desk, bed, stairs
and house icons are used here; the other room icons are drawn in the same style.

## Setup

You need the server's address (`http://<ip>:8123` on the home network) and a long-lived access token from an **admin**
user: rooms come from Home Assistant's areas, read through `POST /api/template`, which needs one. Put them in a file
outside the repo (`backup/` is git-ignored) and push it:

```bash
echo '{"url": "http://192.168.1.10:8123", "token": "eyJ...", "hide_unavailable": true}' > backup/ha-rooms.json
TV_HOST=<tv ip> addons/ha-rooms/install.sh backup/ha-rooms.json
```

The script builds and installs the app, pushes the file (the app takes it and deletes it), and copies the launcher's
wallpapers into the app's own folder, since one app can't read another's. Run it again after changing the
launcher's wallpapers. Without wallpapers the screen is a dark gradient.

| Key | What it does |
|---|---|
| `show` | Only these entity ids. Rooms come in the order of their first entity here, rows in this order within a room. |
| `hide` | Leave these out: entity ids, or a prefix ending in `*`. |
| `hide_unavailable` | `true` leaves out what Home Assistant reports as unavailable; a room with nothing left goes too. |
| `glance` | The weather entity for the top line; without it, the first weather entity. |
| `indoor` | The big temperature: a sensor or climate entity. Without it, the first room's temperature sensor. |
| `room_icons` | `{"Backroom": "desk"}`. Icons: `sofa`, `kitchen`, `bed`, `desk`, `stairs`, `music`, `tree`, `garage`, `pool`, `door`, `house`. Without it, the icon is guessed from the room's name. |

Entities in no area are in a room called Other, last. Areas are read every 5 minutes, states every 10 seconds while
the screen is open, and nothing when it is closed.

### A Home button in the launcher's top bar

The app's icon is a house that is white at rest and dark when focused, so it can sit in the launcher's top bar with
the Inputs, network and Settings icons: add `"top_bar_apps": ["au.id.altus.harooms"]` to your theme's `config.json`
and push it with `tools/theme.sh`. An app in the top bar leaves the grid, so the card goes with it.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Your Home Assistant server, and nothing else. |

## Build

JDK 17 and Android SDK platform 34 with build-tools 34.0.0. `./gradlew testReleaseUnitTest lintRelease assembleRelease`
in this folder. The Outfit font is the launcher's (SIL OFL, `FONT-LICENSE-OFL.txt`).

## Tested on

TCL 43C725 (Android 11), against a real Home Assistant with 11 areas.
