# TV Player (Fire TV prototype)

Channels from github.com/iptv-org/iptv in UK, USA and Sports tabs (Sports includes every
sports channel in iptv-org), plus your own Favourites tab. Programme guide from github.com/iptv-org/epg.

## Run it on your Fire Stick
1. Fire Stick: Settings → My Fire TV → About → click the device name 7 times.
2. Settings → My Fire TV → Developer Options → turn on ADB debugging and Apps from Unknown Sources.
3. Note the stick's IP (Settings → My Fire TV → About → Network).
4. On your PC: `adb connect <stick-ip>:5555` and accept the prompt on the TV.
5. Open this folder in Android Studio, let Gradle sync, pick the Fire Stick and press Run.

## Remote controls
**Guide**
- Up/down through channels, left/right through time. Rewind/fast-forward jump 8 channels at a time.
- Select to watch. Hold select for options (add to favourites; reorder inside the Favourites tab).
- Menu (☰) opens search. Move up to reach Search, Settings and the tabs.

**Watching**
- Up/down: change channel within the tab you're watching from.
- Left: flip back to the previous channel.
- Select or right: mini guide (browse what's on without leaving the channel, select to switch).
- Menu: options for this channel. Back: return to the guide.

**Search**
- Alphabetical on-screen keyboard with live results; matches loosely ("news sky", "bbcone", "fn").
- "Phone or voice keyboard" opens the Fire TV keyboard for the phone app or dictation.

## Settings (on the stick)
Hide offline channels, hide geo-blocked channels, start on the last channel, instant channel change,
check all streams now, guide address, and the three playlist links.

## How offline channels are found
On launch the app checks each stream (favourites first, then the tab you're on, 6 at a time) by
fetching its first few bytes. Results are saved and re-checked every 12 hours. A channel that fails
during playback after three reconnect attempts is marked offline straight away; one that plays is
marked online. Checks run from your connection, so geo-blocked channels that don't work for you
show up as offline too.

## Programme guide
### Option A: automatic, always on (recommended)
1. Push this project to a public GitHub repository.
2. Repository → Settings → Actions → General → Workflow permissions → Read and write.
3. Actions tab → "Update TV guide" → Run workflow. The first run can take a while.
4. Settings → Pages → Deploy from branch → `gh-pages`, folder `/ (root)`.
5. In the app, Settings → Guide address:
   `https://<your-github-name>.github.io/<repo-name>/guide.xml.gz`

The workflow then runs every morning at 04:00 UTC. GitHub Pages is free on public repos; the
guide file contains only TV listings.

### Option B: on your PC
```
git clone --depth 1 https://github.com/iptv-org/epg.git
cd epg
npm install
python3 /path/to/TVPlayer/tools/make_epg_channels.py
npm run grab --- --channels=custom.channels.xml --output=guide.xml --days=2
python3 -m http.server 3000
```
Guide address: `http://<your-pc-ip>:3000/guide.xml`. Only works while the PC is on.

## Faster startup (Baseline Profile)
1. In Android Studio, create an Android TV emulator with API 33 or later.
2. Start it, then run the Gradle task `:app:generateBaselineProfile`.
3. Rebuild the release variant and install it on the stick.
The profile is saved into the app module and installed automatically with the app.

## Where things live
- `Config.kt`: defaults.
- `data/`: playlists, guide download and parsing, search ranking, stream checks, saved settings.
- `player/TvPlayer.kt`: two-player instant channel change and live-stream recovery.
- `ui/`: guide, search, player with mini guide, settings, channel options.
- `tools/make_epg_channels.py`: builds the iptv-org/epg channel list from the app's playlists.
- `.github/workflows/update-guide.yml`: daily guide build.
- `baselineprofile/`: startup profile generator.

## Performance testing
Test release builds (Build → Select Build Variant → release). Debug builds are much slower on sticks.
