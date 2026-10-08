# Tuning tools

None of this ships with the mod. These are desk tools for looking at the mod's
lighting without launching Minecraft, and they call the mod's real classes
rather than copies of them. If something looks right here, it looks right in
game. If it looks wrong here, sorry, it's wrong in game too.

Everything below is run **from the repo root**, not from inside `tuning/`. The
tools find the profile files, `gradle.properties` and their own log by relative
path, and from anywhere else they find nothing and say very little about it.

## Building

Every tool draws with whatever the mod last compiled to, so compile the mod
first, and again after every branch switch:

    gradlew compileJava

Then compile the tools. One line covers the colour tuner and everything it
leans on:

    javac -cp "build\classes\java\main;tuning;jna-5.14.0.jar" -d tuning tuning\ColorTuner.java tuning\PatternStudio.java tuning\HardwareBridge.java tuning\GifWriter.java tuning\BossThemes.java tuning\EffectThemes.java tuning\GameVersion.java

Editing a tool only needs its `javac` line again. `gradlew compileJava` is only
for changes to the mod itself.

`jna-5.14.0.jar` sits in the repo root and is gitignored (it's a public
download). It has to be on the classpath to **compile** anything that touches
`HardwareBridge`, because of the JNA imports. At **run** time it's only needed
for driving a real keyboard. Leave it off and everything else still works; the
keyboard checkbox just tells you what's missing.

## ColorTuner: colour on the real board

    java -cp "build\classes\java\main;tuning;jna-5.14.0.jar" ColorTuner

Every biome, portal, boss and effect the mod has, rendering its real pattern
with its colours on hue / saturation / brightness sliders, optionally mirrored
onto your actual keyboard while you drag.

It exists because tuning colour on a monitor does not work. A screen shows a
colour. A keyboard shows three LEDs mixing behind a diffuser, and whatever the
three channels share comes out as white light and dilutes the hue. A green
like `#56E063` looks green on screen and pale mint on a keyboard, and nothing
on the monitor will warn you. So the board on screen is for reading the
*animation*, and the numbers next to the sliders are for reading the *colour*.
Trust the hardware and the numbers, not the pixels.

### Headless modes

    java -cp "build\classes\java\main;tuning;jna-5.14.0.jar" ColorTuner --check
    java -cp "build\classes\java\main;tuning;jna-5.14.0.jar" ColorTuner --probe

**`--check`** is the thing to run first when the list looks wrong, because a
bad config path otherwise looks exactly like an empty list. It prints:

- the game version it detected and anything hidden as too new for it
- every file path it reads, each marked `[ok]` or `[MISSING]`
- profile counts by kind
- any portal field name that doesn't resolve to a real `DimensionProfile` field
- every boss and effect colour, per TOML key, as currently loaded
- one render of every profile, plus a warning for any that lights no keys
- six seconds of every stage of every boss and effect, plus a warning for any
  stage that never lights a key. Expect exactly one, `burning / not burning`,
  which is dark on purpose. That's the whole point of not burning.
- any pattern name `PatternFactory` didn't recognise. An unknown name quietly
  renders as a shimmer instead of failing, which is friendly in game and
  useless for catching typos, so it gets called out here.
- the palette ranked by white fraction, most washed out first (see
  [The `white` number](#the-white-number))

**`--probe`** connects to the Corsair board, builds the mapping from the drawn
keyboard to the real LEDs, prints it and disconnects. It's read-only and never
pushes a frame, so whatever the keyboard is showing stays put.

### Where the profiles come from

The shipped defaults unioned with your live config, your copy winning, exactly
as `JsonProfileLoader` does it. So the list is the list the game sees,
including anything in your config that has drifted from the defaults.

| What | Shipped default | Live copy |
| --- | --- | --- |
| Biomes | `src/main/resources/everythingrgbprofile_defaults/biome_profiles.json` | `<config>/everythingrgbprofiles/biome_profiles.json` |
| Portals | `.../dimension_profiles.json` | same folder |
| Boss-bar bosses | `.../boss_profiles.json` | same folder |
| Boss and effect colours | `ColorPalette` constants | `<config>/everythingrgbprofiles-common.toml` |

The live config defaults to the Modrinth instance this was built against,
`%APPDATA%/ModrinthApp/profiles/Forge Everything/config/`, which is almost
certainly not where yours is. Point it at yours with:

    -Dtuner.config=<path to biome_profiles.json>

The other two JSON files are picked up from the same folder and the TOML from
its parent, so the one property moves all four together. A missing live file
is fine and just means defaults only. A missing TOML is fine too: the boss and
effect entries fall back to the shipped `ColorPalette` colours, the same ones
the mod falls back to, and the status line says which of the two you're
looking at.

**reload** re-reads all of it from disk without restarting.

### The window

The keyboard is on the left and the sidebar on the right. The sidebar hides
any section that doesn't apply to the selection rather than greying it out,
so a biome never shows boss controls and a boss never shows the painter.

- **Profile**: a category filter (`all`, `biomes`, `portals`, `bosses`,
  `effects`, `tools`) and the list itself.
- **Colours**: one card per colour, each with hue, sat and val sliders, a
  swatch and a readout of hex, HSV and `white`. Biomes and portals get a base
  colour and an optional accent. Untick **accent colour** and the pattern
  derives its accent from the base, which is what the mod does with a profile
  that has none; tick it to add one. Boss and effect layers name their colours
  after what they are (`bone`, `eyes`, `powered` for the Wither) and get a
  third card when they have a third colour.
- **Encounter**: stage, progress and loop controls for bosses and effects.
  See [Bosses and effects](#bosses-and-effects).
- **Key painter**: only for [Paint your own](#paint-your-own).
- **Keyboard**: **drive my real keyboard** puts the board on your Corsair
  keyboard while it's ticked.
- **Tuning log**: a note box and **log tweak**. See
  [Getting colours into the game](#getting-colours-into-the-game).
- At the bottom, always visible: **copy JSON**, **reset** (throw away slider
  changes) and **reload**, then a three-line status line that says what's on
  the board and what progress means for it.

### The `white` number

Next to each swatch, and the number actually worth watching. It's the fraction
of that colour emitted as white light, `min(r,g,b) / max(r,g,b)`. 0.00 is a
pure hue. Anything getting near 0.40 will read pale on hardware no matter how
good it looks on screen.

Brightening a colour by raising all three channels, which is the instinct from
paint and from screens, pushes this number up. Raise `val` and leave `sat`
alone instead.

Grey and white things legitimately sit near 1.00. The Wither is grey, the
moon is pale, a windswept hill is mostly rock. The number flags dilution, not
wrongness, so read it against what the thing is supposed to look like.

### Hardware

**drive my real keyboard** connects through `HardwareBridge`, which talks to
iCUE through the mod's own `ICueSdk` bindings. It's Corsair only; the tuning
tools don't use the other backends. iCUE has to be running with SDK access
enabled, and the status line says so when it isn't.

It's a separate class from the mod's `CueSdkBridge` on purpose. That one logs
through `RGBProfileMod`, which drags NeoForge in, which isn't on a plain
`java -cp` classpath, and the result is a `NoClassDefFoundError` that looks
like a missing JNA jar and is nothing of the sort. The SDK bindings are still
shared; only the connect-and-push logic is duplicated.

The on-screen board is always the drawn ANSI full-size layout. Connecting
doesn't replace it. Instead each real LED is matched to the drawn key it
should show, row by row, so every physical LED gets a colour even when the two
layouts disagree about where keys are. `--probe` prints that mapping.

The only keyboard this project has been tested on is a Corsair K70 RGB
RAPIDFIRE.

### Biomes

Plain entries like `minecraft:plains`, rendering the profile's named pattern
and preset with its `color` and `accentColor`.

### Portals

Entries prefixed `[portal]`, from `dimension_profiles.json`. They render the
real `CoreEmitterPattern` field that the portal charge and arrival use, built
through the mod's own `DimensionProfile`, so the hand-fitted gradients can be
judged on hardware instead of on a monitor, which lies about gradients more
than about anything.

The prefix isn't decoration. `minecraft:the_end` is both a biome and a
dimension, with different colours and a different pattern, and a flat merge
would quietly drop one of them.

A portal profile that authors its own `gradient` ignores the colour sliders,
because the mod ignores them too. The status line says so, so nobody spends
ten minutes dragging a slider that does nothing.

### Bosses and effects

Entries prefixed `[boss]` and `[effect]`. These aren't patterns. They're the
mod's real effect controllers, which are state machines that render their
opening frame forever until something tells them what the game is doing. In
game that's the event layer. Here it's a script per theme (in `BossThemes` and
`EffectThemes`) driven by the **progress** slider.

#### Boss-bar bosses

One `[boss] <entity id>` per `boss_profiles.json` profile, rendering the
generic boss-bar pulse through `BossEncounterEffect` with the profile's `color`
and `enrageColor`. Progress is health draining, and a profile with an enrage
threshold gets `calm` and `enraged` phases split at it.

The profile's `pattern` field is ignored here because the mod ignores it too:
the boss layer builds its own whole-board pulse and takes the frequency from
health.

#### Dedicated boss layers

These keep their colours in the TOML config, not in JSON:

| Entry | TOML table | Colours |
| --- | --- | --- |
| `[boss] elder guardian` | `elderGuardian` | water, eye, beam |
| `[boss] wither` | `wither` | bone, eyes, powered |
| `[boss] naga` | `naga` | scale, head |
| `[boss] ender dragon` | `enderDragon` | void, accent, breath fire |
| `[boss] slider` | `slider` | stone, runes, critical |
| `[boss] sun spirit` | `sunSpirit` | sun, flame, ice |
| `[boss] valkyrie queen` | `valkyrieQueen` | silver, gold, lightning |
| `[boss] warden emergence` | `wardenEncounter` | emergence |

The Wither, the dragon and the Elder Guardian also have boss-bar entries
(`[boss] minecraft:wither` and so on), so they show up twice. That's correct:
the mod really has two layers for each, at different priorities, and the
dedicated one wins in game. The boss-bar entry is what you get with the
dedicated one turned off.

The scripts make the same calls the event layer makes, in the same order, with
values in the same ranges. They don't claim the same timing. A real dragon
holds a pose as long as its own AI says, not the 2.6 seconds used here. For
colour work what matters is that every state is reachable and then holds
still long enough to look at.

What progress walks through, per boss:

- **elder guardian** has no health input at all, because its layer is about
  the attack cycle. Progress walks that: swimming, the three-second wind-up,
  the beam landing, then cursed with Mining Fatigue. The beam and the curse are
  short flashes, so a parked slider replays them.
- **wither**: the 220-tick summon, then the fight, with the armour going on at
  half health. `poweredColor` never shows above that, which is exactly why the
  slider exists.
- **naga**: health, with a body segment shed about every 12%. It circles and
  charges off the clock, a charge every eight seconds.
- **ender dragon**: the respawn ritual, then the fight cycling its five poses,
  then the death.
- **slider**: asleep in the middle of its room, then woken and sliding round a
  fixed route while a marker for you wanders about. Some moves end on a wall
  and some stop short, because only the first kind lights the wall. Health
  comes off in eight whole hits, the runes go critical at a quarter, and at the
  end the cube breaks apart (a parked slider replays that).
- **sun spirit**: asleep, then flying a circle at its real speed, throwing a
  crystal every 2.5 seconds (every fifth one ice) that bounce off the walls,
  leaving fire behind it and keeping the room's four corner fires lit. Once
  every 20 seconds it freezes for the real 8.75, long enough to watch the
  countdown ring run out. Then the sunset. Two things it doesn't do: knock an
  ice crystal back, so the streak never shows, and burn fires out at vanilla's
  random rate (each lasts six seconds here).
- **valkyrie queen**: waiting on a throne against the north wall of a 24 by 20
  room, then hunting a marker for you. She teleports to seven blocks from you
  every nine seconds and closes in, jumping and lunging, and throws a thunder
  crystal every twelve that homes by the crystal's own rule and becomes a bolt
  when it runs out. Every other crystal gets hit when it drifts into reach,
  which knocks it away and brings its bolt forward. Teleports and crystals come
  round about twice as often as in game so you actually get to see them. Her
  silver sits high on `white` on purpose, because she's silver; judge it
  against the white of a hit landing rather than against zero.
- **warden emergence** is a one-shot envelope, so progress scrubs through it
  like a transport control.

The slider, sun spirit and queen run their movement off the clock, not the
progress slider, so dragging progress changes health and colour without
teleporting anybody.

#### Effects

The other effects with colours in the TOML:

| Entry | TOML table | Progress means |
| --- | --- | --- |
| `[effect] burning` | `burning` | which of five heights: not burning, protected by Fire Resistance, burning, standing in fire, in lava |
| `[effect] drowning` | `drowning` | how much of your air is gone; past the panic threshold the water pulses |
| `[effect] rain` | `rainThunderstorm` | nothing |
| `[effect] night moon` | `nightIndicator` | how far through the night it is |
| `[effect] death blood` | `deathFlash` | nothing |
| `[effect] warden presence` | `wardenEncounter` | nothing |
| `[effect] raid` | `raidWarning` | the Raid Omen counting down, then the raid with the horn every few seconds, then victory, then defeat |
| `[effect] level up` | `progressionEffects` | nothing; it's a one-shot, replayed on a loop |
| `[effect] menu vanilla` | `menuTheme` | nothing |

Plus `[effect] menu sift` and `[effect] menu sulfur` on branches that have
them; see [Version-bound entries](#version-bound-entries).

Only the colours are live. Drop counts, drip intervals and panic thresholds
are the shipped defaults, not read from your config.

The mineshaft menu theme isn't in the list. It takes its colours once, when
it's built, so recolouring it live would restart its drill and sparks on every
slider nudge.

**Overlays.** Burning, drowning, rain and the moon sit over the biome in game,
and a lot of what makes each one work is what it lets through. So the tuner
draws them over a real biome with its real pattern, composited the way the
game composites them. The biome is **the last one you selected**, starting
from plains: pick a desert, then `[effect] burning`, and the fire is over
desert. The status line names the biome.

That's not fixed to plains because plains is green, and green showing above
the flames looks like something the fire is drawing. **draw over black**
removes the biome entirely, so the only lit keys are the effect's own and
anything it leaves transparent is unlit.

#### Stage

The **stage** dropdown pins a theme to one thing it does, on repeat, instead of
the whole script. It's hidden for anything with no stages. Pick `fire breath`
and the dragon lands and breathes fire until you tell it to stop, which it
would frankly prefer.

- **ender dragon**: `ritual`, `fight` (all five poses cycling), `flying`,
  `lunging`, `hovering`, `landing` (hovers, drops onto the portal, repeats),
  `perched`, `fire breath`, `dying`.
- **elder guardian**: `swimming`, `wind-up`, `charge and fire` (the real
  three-second wind-up, the beam, a 1.2 second breather, on loop), `beam`,
  `cursed`.
- **naga**: `prowling`, `charging`.
- **sun spirit**: `asleep`, `fight`, `flying` (never frozen), `frozen` (two
  seconds of flight, then the whole 8.75 second freeze, on loop), `sunset`.
- Everything else with named phases gets one stage per phase: the Wither's
  `summoning`, `fight` and `powered`; the Slider's `asleep`, `fight`,
  `critical` and `destroyed`; the Queen's `waiting`, `fight` and `defeated`;
  burning's five heights; drowning's `rising` and `panic`; the raid's `omen`,
  `raid`, `victory` and `defeat`; a boss-bar boss's `calm` and `enraged`.

The stages that aren't phases exist because a phase is a stretch of the
progress slider, and the dragon's poses and the guardian's attack run off the
clock instead. The `frozen` sun flies first because the effect only believes a
freeze after it has seen the spirit fly at full speed. Freeze it from frame one
and the effect, correctly, sees a sleepy sun.

Inside a stage, the progress slider covers whatever is left to vary. For the
dragon poses, the Naga and the sun that's health. For a stage made from a
phase it's that phase's own stretch, stretched out to the full slider. A few
stages, like `charge and fire`, have nothing left for it to do, and the status
line says so.

The stage is kept by name, so switching from the dragon to the Wither with
`fight` picked shows the Wither's fight. A theme without that stage plays its
whole script.

#### Loop

Holding the slider shows one moment. The **loop** controls play a stretch of
progress instead, and **play the loop on the board** runs it live, on screen
and on the keyboard, with the readout under the slider following along.

- **Phase**: `whole encounter`, one of the theme's named phases (listed with
  their percentages), or `custom`. A phase loop stays a step inside its
  boundaries, so the neighbouring stage never flashes up at a turn.
- **Shape**: `there and back` runs start to end and back, so the loop is
  continuous. `start to end, repeating` only goes forwards, for things that
  make no sense in reverse like a summon charging or a death. `hold at the
  slider` sits on the slider's value.
- **start here / end here** set a custom range from wherever the slider is.
  Pressed while a named phase is chosen, the other end stays where that phase
  had it, so narrowing a phase is one click.
- **Length** in seconds, 0.5 to 120. Lengthen it to catch the events that run
  off the clock: the sun freezes once every 20 seconds, the Queen teleports
  every 9, the dragon's five poses take 13.

While a stage is pinned, the phase list empties out, because the stage already
is the phase; `whole encounter` then means the whole stage. The phase is kept
by name across themes, the same as the stage.

### Paint your own

The `Paint your own` entry, under `tools`, is a key painter for sketching a
still design before anyone writes a pattern for it. No animation. You click
keys and they change colour.

- **Left-click** paints a key with the brush. **Drag** to paint a run.
- **Right-click** un-paints it.
- **Middle-click** or **alt-click** picks a painted key's colour up into the
  brush.
- The brush is the colour sliders, `white` readout included, so a design
  drawn in washed-out colours tells you before it gets anywhere near hardware.
- **fill all** paints every key the brush colour, handy for a background.
  **clear** wipes the board. **key labels** draws each key's legend.

The design survives switching to another entry and back while the window is
open, so you can go borrow a biome's colour and come back. With the keyboard
ticked it shows on the hardware too, unpainted keys off.

### Version-bound entries

`tuning/` is one folder shared by every branch, but not every branch is the
same game. The sulfur caves arrived in Minecraft 26.2, so on 1.21.x there is
no `SulfurCavePattern` to draw them with. `GameVersion` reads
`minecraft_version` from the checked-out branch's `gradle.properties` and keeps
anything newer out of the list:

- `[effect] menu sulfur`, the 26.2 title-screen theme (`sulfurRockColor` and
  `sulfurPoolColor` under `[menuTheme]`).
- `minecraft:sulfur_caves`, and any biome drawn with `sulfur-cave`, even one
  sitting in a live config a 26.2 instance wrote.

Those classes are looked up by name rather than imported, so the tuner still
compiles on older branches. It's gated twice: the version decides whether an
entry should exist, and the class lookup decides whether it can. If they
disagree, say a 26.2 branch whose `build/classes` is left over from 1.21.x,
the entry stays out and the tuner prints a warning telling you to run
`gradlew compileJava`. `--check` opens with the version it found and anything
it hid. `-Dtuner.mcVersion=26.2` overrides the file.

`[effect] menu sift` (`siftMesaColor` and `siftSkyColor` under `[menuTheme]`)
is looked up the same way but isn't tied to a game version. It shows up on any
branch whose build has `SiftPattern` and is silently absent elsewhere.

To add the next version-bound thing, put its pattern or biome in
`GameVersion`'s tables and look its classes up through
`GameVersion.required`, the way `EffectThemes` does for the sulfur menu.

### Getting colours into the game

**copy JSON** puts the current entry on the clipboard in the shape its own
file wants, despite the button's name:

- a **biome**: one `biome_profiles.json` line
- a **portal**: the whole `dimension_profiles.json` block, every other authored
  key carried through, so pasting it back can't strip the portal's geometry
- a **boss-bar boss**: the whole `boss_profiles.json` block, likewise. That
  matters, because it keeps `bossBarNamePattern`, which for a mod with no clean
  entity hook is the only reason its boss is detected at all
- a **dedicated boss or effect**: a TOML snippet with just its colour keys.
  Paste the keys into the existing table; don't replace the table with it, or
  the timing and geometry keys in there go with it

**log tweak** (or Enter in the note box) appends an entry to
`tuning/color_tuning_log.md`: where each colour started, where it ended, the
HSV and `white` changes, the copy block, and your note. The note is the
valuable part. It's the only record of what a colour was *supposed* to look
like, and no measurement can recover that afterwards.

`apply_tuning_log.py` writes logged biome colours back for you:

    python tuning/apply_tuning_log.py --dry-run   show what would change
    python tuning/apply_tuning_log.py             apply it

It takes the last ```json block per id from the log and rewrites that line in
**both** the shipped `biome_profiles.json` and the live one, keeping every
other line where it was. It only knows about `biome_profiles.json`. Boss-bar
blocks just come out as "not found", but a logged `[portal] minecraft:the_end`
block shares its id with the End *biome* and would get written over that
line. Apply portal, boss and effect tweaks by hand. The script's live path is
the same hard-coded Modrinth one as the tuner's, with no override, so edit
`LIVE` at the top if yours lives elsewhere.

**The caveat that will bite otherwise:** the mod merges configs with
`putIfAbsent`, so changing a colour in the shipped defaults does **not** reach
a config file that already has that entry. New entries arrive automatically;
changed colours don't. Paste tuned values into your live config, or delete the
entry there so the default takes over. The script does both files for exactly
this reason.

### Options

All passed as `-D<name>=<value>` before the class name.

| Property | Does |
| --- | --- |
| `tuner.config` | path to the live `biome_profiles.json`; the other three files follow it |
| `tuner.select` | open on this entry instead of the first, e.g. `-Dtuner.select="[boss] wither"` |
| `tuner.progress` | where the progress slider starts, 0 to 1 (default 0.25) |
| `tuner.stage` | start pinned to this stage, e.g. `-Dtuner.stage="fire breath"` |
| `tuner.effect.backdrop` | the biome overlays start over, or `black` |
| `tuner.mcVersion` | override the version read from `gradle.properties` |

## PatternStudio: pattern shape

    java -cp "build\classes\java\main;tuning;jna-5.14.0.jar" PatternStudio

ColorTuner tunes colour. PatternStudio tunes *shape*: an animated keyboard at
30 Hz with the parameters of the portal's `CoreEmitterPattern` on sliders
(emitter, wave, core and timing), rendered through the mod's real classes. It's
how that pattern got fitted to the reference animations without launching the
game once per tweak.

- **Pattern** switches between the core emitter, the full portal transition,
  the pillar field, and a few simpler patterns (shimmer, sweep, ring expand,
  twinkle). **Palette**, **Emission** and **Curve** pick the rest.
- The white crosshair marks the spiral centre. Everything in
  `CoreEmitterPattern` is measured from that point, so "why is it lopsided"
  usually answers itself once you look at it.
- **φ** sets the winding for a true golden spiral at the current core size.
- **Copy JSON** puts the settings on the clipboard as a
  `dimension_profiles.json` block.
- **PNG** saves the board to `preview3/studio_<timestamp>.png`.
- **Drive real keyboard** mirrors the frames onto the Corsair board through
  the same `HardwareBridge` as the colour tuner. `HardwareBridge` isn't loaded
  until the box is ticked, so the studio runs fine without JNA.

## Hardware backends, without the hardware

The mod drives Corsair, Razer, Logitech, SteelSeries and OpenRGB. The only
keyboard this project has been tested on is a Corsair K70 RGB RAPIDFIRE, so
every other backend is experimental, and checked as far as the vendor's
software alone allows and no further.

The two Gradle tasks run against the mod's real runtime classpath plus
`tuning/`, so compile the probes first:

    javac -cp "build\classes\java\main;tuning;jna-5.14.0.jar" -d tuning tuning\VendorProbe.java tuning\OpenRgbProbe.java tuning\MirrorCheck.java

Then:

    gradlew vendorProbe                           Razer / Logitech / SteelSeries: open and close a session
    gradlew vendorProbe -PprobeArgs=--send        ...and push three seconds of alternating frames
    gradlew openRgbProbe                          OpenRGB: read every controller, write nothing
    gradlew openRgbProbe -PorgbArgs="<host> <port>"   a server other than 127.0.0.1:6742
    java -cp "build\classes\java\main;tuning;jna-5.14.0.jar" MirrorCheck

`vendorProbe` also proves the path most people take. Auto mode tries every
backend at startup, so a vendor that isn't installed has to fail fast and
quietly. On a machine with none of them it reports each as not available in
well under a second.

`openRgbProbe` drives the real `OpenRgbBackend` against a running OpenRGB SDK
server and prints the geometry it parsed. It never writes a colour.

`MirrorCheck` connects to the real Corsair board read-only, builds the fixed
grids Razer (22x6) and Logitech (21x6) expose, and checks that auto mode's
mirroring sends each row to the matching physical row. A correct mapping is a
clean diagonal. It exists because nearest-neighbour mapping is exactly the
kind of thing that sends a number row to the F-row and leaves keys dark.

**What "accepted" proves** differs per vendor, and each was checked against
the real software:

| Vendor | Checks what it's sent? | So a clean `--send` proves |
| --- | --- | --- |
| Razer | **Yes**: the wrong shape gets `result 87` naming the expected 6x22 | the connection *and* the payload shape |
| SteelSeries | No: a malformed bitmap or unknown event still gets 200 | the connection only |
| Logitech | No: returns true even with no Logitech keyboard attached | that G HUB took the call |

Razer reports errors with HTTP 200, so the backend checks the body for
`result 0`, not the status. None of the three can show a key actually changing
colour. That still needs someone who owns the keyboard.

## SpiralWavePreview (doesn't compile)

Contact sheets: a synthetic K70 grid, eight frames per palette, tiled into a
PNG in `preview3/`. It imports `SpiralWavePattern`, which was replaced by
`CoreEmitterPattern` and no longer exists, so it doesn't build any more. It's
kept because the harness is still right; point its two references at
`CoreEmitterPattern` and it works again.

## The rest of the folder

- `stubs/`: fake Minecraft, NeoForge, Gson and JNA classes, so the tools can
  compile without the game on the classpath. Not used by the commands above.
- `color_tuning_log.md`: the tuning log `log tweak` writes to.
- `MappingPatch.java.txt`: a placeholder with nothing in it.
- The `.class` files are build output from the `javac` lines above.
