# Things to try next

Ideas for once Calm Home is running. Each is a small project to do together with Claude: Claude writes the code
and runs the commands, you decide what you want and test it on the TV. Pick whatever sounds fun.

**For Claude:** ask a few questions about his home before suggesting (smart lights or plugs? guests often? grandchildren?
an Echo? a Raspberry Pi in a drawer?) and suggest the three that fit best. Before starting, say what it involves, what it
costs (if anything) and how to undo it. Keep secrets such as Wi-Fi passwords and API tokens out of anything committed:
put them in a git-ignored file.

## Easy: an afternoon each

### Guest Wi-Fi QR code
A **Guest Wi-Fi** tile on Home that shows a big QR code. Guests point their phone camera at the TV and join, no password
spelling needed. The QR holds a standard `WIFI:T:WPA;S:<network>;P:<password>;;` string; Claude can render it on the PC
(Python `qrcode`) into a git-ignored image that gets built into the app, or draw it in code. Nice extra: show it on the
Sleep Screen when guests are over.

### Your own photos as the Sleep Screen
Swap the Aerial films for a slideshow of family photos, from a folder on the PC copied to the stick or from a shared album,
with a slow Ken Burns pan to match the Aerials.

### "Movie time" with Alexa
Alexa routines (in the Alexa phone app) can chain things: "Alexa, movie time" opens Netflix and dims smart lights.
No code at all, a good warm-up.

### Bedtime timer
A Home menu option: "Turn off the TV in 30 / 60 / 90 minutes". The stick sends the TV a standby command over HDMI-CEC
when the timer ends.

### A morning glance
Add a few lines to the Sleep Screen: today's forecast, sunrise and sunset, a calendar's next event, or a headline.
Weather already comes from Open-Meteo for free; the rest are small additions.

## Medium: a weekend

### Smart lights that follow the TV
If there are Philips Hue lights (or any that Home Assistant can reach), the lights dim when a film starts and come back
up on pause or when Home is pressed. Hue has a simple local web API, so the stick can talk to the bridge directly.

### Doorbell and camera pop-ups
With a video doorbell, show a small picture-in-picture of the camera on the TV when someone rings, then fade it out.
Usually done through Home Assistant plus a notification app on the stick.

### Grandchildren mode
A second set of tiles (kids' apps only, bigger, brighter), switched from the settings menu, optionally with a PIN to get out.

### Your phone as a remote
A tiny web page served from the stick or PC: open it on your phone and get big buttons for your apps, volume and
"find my remote".

### Ad blocking for the whole house
Instead of blocking ads only on the TV, block them for every phone and laptop at home with Pi-hole on a Raspberry Pi,
or NextDNS in the router (no hardware). This also catches Amazon telemetry the TV-only setting misses.

## Bigger: for when you're hooked

### Home Assistant
A free home-automation hub (Raspberry Pi or old PC) that ties every smart device together, whatever the brand. Calm Home
can then show a "Home" panel on the TV: lights, heating, who's at the door, the bins schedule.

### Learn Git properly, and send improvements back
Make your own branch, keep your tweaks on it, pull in new versions from the original repo, and send a pull request when you
build something worth sharing. Claude can explain each step as you go; it's how all modern software teams work.

### Build your own feature from scratch
Anything you've wished your TV did. Describe it to Claude in plain words and build it together.
