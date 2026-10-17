# RadialMenu

A radial menu mod for Minecraft **1.21.1** (Fabric). Bind commands, keybinds, submenus,
macros and mod toggles to a ring that opens with a single key.

The mod is **universal** (`environment: "*"`): the same jar runs on the client and on a
dedicated server. Installing it server-side is optional, but it lets an operator publish
one configuration for everyone — see [Server-side settings](#server-side-settings).

---

## Requirements

| | |
| --- | --- |
| Minecraft | 1.21.1 |
| Fabric Loader | 0.19.1 or newer |
| Fabric API | required |
| Java | 21 or newer |

**Optional**, each unlocks extra features and is detected at runtime:

| Mod | What it adds |
| --- | --- |
| [YACL](https://modrinth.com/mod/yacl) | The main settings screen (opened with `N`). Without it, use `/radialmenu size`. |
| [Mod Menu](https://modrinth.com/mod/modmenu) | Entry point in the mod list, plus opening other mods' config screens. |
| [MaLiLib](https://modrinth.com/mod/malilib) | Triggering MaLiLib hotkeys from a slot, and binding slots to MaLiLib toggles. |

Cloth Config, YACL, Mezz Config and MaLiLib configs are all scanned when looking for
mod toggles to bind.

---

## Default keys

| Key | Action |
| --- | --- |
| `R` | Open the radial menu |
| `K` | Toggle edit mode |
| `N` | Open the radial settings screen |

All three are rebindable under **Options → Controls → Radial**.

---

## Using it

**Open and pick.** Press `R`, move the cursor into a slice, and release or click depending
on your activation mode. Right-click or `Esc` goes back one level in a submenu.

**Edit a slot.** Press `K` to enter edit mode, then right-click any slice to open the slot
editor. While in edit mode, clicking the centre disc opens the main settings screen.

**Configure the look.** The settings screen has a live preview: radius, thickness,
colours, gaps, borders, background blur, text, and reveal/hover animations are all
adjustable with the result visible immediately.

---

## Slot actions

Each slot is assigned one action mode:

| Mode | What it does |
| --- | --- |
| **Do Nothing** | Empty slot. |
| **Send Chat / Command** | Sends a chat message, or a command if it starts with `/`. |
| **Trigger Game Keybind** | Fires any keybind from Options → Controls. Supports combos such as `Ctrl+A`, and the `F3` debug shortcuts. |
| **Open Screen Shortcut** | Jumps straight to a vanilla screen — Options, Video, Audio, Chat, Controls, Keybinds, Language, and more. |
| **Open Submenu** | Opens a nested ring. Up to 5 levels deep, 2–12 slices each. |
| **Run Macro** | Runs a sequence of actions with optional delays. Up to 100 actions. |
| **Open Mod Settings Screen** | Opens another mod's config screen, when one can be found. |
| **Trigger MaLiLib Hotkey** | Fires a MaLiLib hotkey. Requires MaLiLib. |

### Macros

A macro is an ordered list of the same action modes plus **Add Delay**. Delays are
measured from the previous action, so `Chat, Delay 1, Chat` fires the second message
exactly one second later. Multiple macros can run at once, and running macros are
cancelled when you disconnect.

### Icons

A slot's icon can come from several sources:

- **Items** — any item, including full NBT via the `/give` component syntax.
- **Inventory** — a live view of a hotbar, inventory, armour or offhand slot, so the icon
  always matches what you are actually holding.
- **Effects** — any status effect icon.
- **Glyphs** — any Unicode character.
- **Icons** — the bundled Phosphor icon set, with a searchable picker.
- **Import Image** — load a `.png`/`.jpg` from disk. Near-black backgrounds are made
  transparent automatically.

Recently used icons are kept in a **Recent** tab. Phosphor and glyph icons can be tinted,
and every icon can have a name, a description and a colour.

Every icon also has an **Icon Size** row: enter a length and a width to draw the icon in a
custom box instead of the default 26x26, and pick how it fills that box — **Fit** scales the
icon uniformly so it stays undistorted, **Stretch** fills the whole box and may distort it.
Leave both fields empty to keep the default look, where items and effects are drawn at their
native size.

### Toggle slots

A slot can act as an on/off switch instead of a one-shot action. When enabled, activating
it flips the state and then runs the action configured for that state, so a single slot
can do one thing when turning on and another when turning off.

The icon can be tinted red/green by state, and a short status label is appended to the
hover hint.

A toggle can also be **bound to another mod's setting**. The state is then read from and
written to that mod, so the radial slot becomes a remote control for it. Supported sources
come from Cloth Config, YACL, Mezz Config and MaLiLib.

### Conditions

A slot can require a condition before it can be used:

| Type | Behaviour |
| --- | --- |
| **Inventory Item Check** | Usable only while you carry the listed items. |
| **Item/Durability Consume Unlock** | Consumes items (or item durability) to unlock. |
| **XP Points/Levels Consume Unlock** | Consumes experience points or levels to unlock. |

Several entries can be listed at once, and **all** of them must be met. When a single mod
ships the same item in many colours or styles under different IDs, group them into a
**set**: a set is met by **any one** of its members, so owning any variant satisfies the
constraint. Use **Add Set** to create one, or **To Set** on an existing entry to convert it
(keeping the current item as the first member). Members are edited in their own scrollable
screen — up to 100 per set — so a long list never crowds the condition editor.

The count and durability cost belong to the **set itself**, not to each member — they apply
to every item in it. That is what keeps "any one of these" meaningful: a single number
decides whether the condition is met, so there is no way to end up with one variant
satisfied and another not. Unlocking consumes one variant, drawn from whichever members you
actually have.

While a condition is unmet the slice is drawn in its own red-brown palette, optionally with
a slash and a dimmed icon. The hover hint shows **Right-click to view unlock requirements**
in red; **right-clicking** a slice opens a scrollable read-only screen listing every
requirement with a green tick or a red cross for what you currently have. When a
consume-type condition can be paid for, the hint turns into a green **Click to unlock**.
Once an inventory-check condition is satisfied, the slice simply shows its own selected hint
and is clickable again.

In edit mode no hover hint is shown at all, because right-click is reserved for opening the
slot editor.

Consume-type conditions unlock either **permanently** or for a **timed** duration, and the
unlock state is saved to the config, so it survives a restart. When a confirmation prompt is
enabled, it lists only what is **actually about to be consumed** — for a set, the single
variant that will be taken, not every candidate — and the panel grows and shrinks to fit the
list, wrapping onto more lines as needed.

#### Consumption is performed by the server

Unlocking by consuming items or XP **requires the mod on the server**. The client asks the
server to pay, the server checks that you really have the items or XP and takes them from
*your authoritative inventory*, and only then does the client mark the slot unlocked and run
its action.

This matters because a client-only deduction would be a lie: removing an `ItemStack` locally
only bumps a local counter and sends nothing, so on the next join the server re-syncs its own
inventory and the "spent" items come back — and if you also reset the unlock states, the items
are simply refunded to you. That is why consumption is never done locally.

If the server does not have the mod, consume-type conditions **cannot be unlocked** and the
client says so instead of pretending. Item-check conditions still work everywhere, since they
only read your inventory.

**Unlock state is owned by the server.** What you paid for is a player asset, so the record of
it lives in `config/radialmenu-server-unlocks.json` on the server, held in memory while the
server runs and written when a player disconnects (and again on shutdown). On join the server
sends your authoritative unlock state, and the client **replaces** its local record with it —
so editing your own `*.runtime.json` to hand yourself unlocks does nothing, and your progress
follows you to another machine instead of dying with your client files.

The **Reset Slot Unlock States** button therefore also clears the server-side record; clearing
only the local copy would be undone by the next sync. It affects only your own unlocks, so it
needs no operator permission.

### Sound

Each slot can play a click sound: any vanilla sound, or an imported `.mp3` (decoded
internally) dropped into the config folder.

---

## Activation modes

| Mode | Behaviour |
| --- | --- |
| **Cursor + Click** | Aim with the cursor, click to activate. |
| **Cursor + Release** | Aim with the cursor, release the open key to activate. |
| **Scroll + Click** | Pick with the scroll wheel, click to activate. |
| **Scroll + Release** | Pick with the scroll wheel, release the open key to activate. |

Edit mode temporarily falls back to cursor-based activation so you can aim normally while
editing.

---

## Commands

| Command | Side | Description |
| --- | --- | --- |
| `/radialmenu` | client | Reports the current radial size. |
| `/radialmenu size <0.5–3.0>` | client | Sets the radial size. Useful without YACL. |
| `/radialmenu-server reload` | server | Re-reads the server config and control files, then resyncs everyone. |
| `/radialmenu-server status` | server | Reports whether a config is loaded and the state of both switches. |

---

## Server-side settings

A server owner can configure the menu once and have every player use it automatically.
This requires the mod on the server as well as the client.

### How it works

1. An operator opens the settings and clicks **Apply as Server Settings**. This uploads
   their current settings as the server config (`config/radialmenu-server.json`, same
   format as the client's `radialmenu.json`) and starts distributing it. Clicking again
   overwrites it.
2. Every player who joins applies the server configuration automatically.

The two server-wide switches live in `config/radialmenu-server-control.json`, separate
from the configuration file itself, because that file's format must stay identical to the
client's.

| Setting | Effect |
| --- | --- |
| **Apply as Server Settings** | Uploads and distributes the server configuration. |
| **Force Clients To Use Server Settings** | Everyone uses the pristine server configuration. Players cannot customise it or switch back. Requires the first setting to be on. |

### Three configurations on the client

| Configuration | File | When it is used |
| --- | --- | --- |
| **Client global** | `config/radialmenu.json` | Your own settings, independent of any server. Used when the server is not applying its config, or when you switch back. |
| **Per-server custom copy** | `config/radialmenu-servers/<server>.json` | Used when the server applies its config without forcing. It is yours to customise, and stored per server so servers never overwrite each other. |
| **Pristine server config** | `config/radialmenu-servers/<server>.forced.json` | Used only when the server forces its settings. Re-downloaded on every join and held in memory; editing it has no effect. The on-disk file is a reference copy. |

Because the custom copy is per server, customising your menu on one server never leaks
into another. If an operator updates the server configuration, your custom copy is rebuilt
from the new one automatically, so server-side changes are never masked by a stale copy.

**Switch To Client / Server Settings** moves you between the server-provided
configuration and your own. It is a personal choice and needs no permission — switching to
the server's settings only affects you.

### Permissions

There are **no permission nodes**. Everything is decided by the vanilla **operator
permission level** (level 4 by default), so no permissions mod is needed.

| Action | Who |
| --- | --- |
| Apply / upload the server config, toggle **Force Clients To Use Server Settings** | OP only |
| `/radialmenu-server reload`, `/radialmenu-server status` | OP only |
| Switch between server settings and your own client settings | Everyone |
| Edit slots (right-click a slot, change any setting) | Everyone, **except** while the server is forcing |
| Enter edit mode (the `K` key) | Everyone, **except** non-OPs while the server is forcing |
| Open the settings screen (menu centre, or `N`) | Everyone, **except** non-OPs while the server is forcing |

While **Force Clients To Use Server Settings** is on, **nobody can edit slots or
settings** — including operators. That is deliberate: the forced configuration lives in
memory only, so an edit could not be saved, and allowing it would look like it worked and
then silently vanish.

Operators keep the `K` key on purpose. Edit mode is how you reach the settings screen by
clicking the menu centre, and `N` is not discoverable — disabling `K` would leave
operators unable to find the switch that turns forcing back off.

To change a forced configuration:

1. Open the settings and turn **Force Clients To Use Server Settings** off.
2. Edit the radial menu as usual.
3. Click **Apply as Server Settings** to upload it.
4. Turn the force switch back on.

Server-related options are hidden rather than greyed out, so the settings screen only
shows what actually applies. In singleplayer no server option is shown at all —
singleplayer always uses your own config.

Operator status is read live from the client's own permission level, which vanilla keeps
up to date the moment someone is opped or de-opped, so there is no cached-permission
staleness.

### If the buttons do nothing

Check the server log on join. The server prints one line per sync:

```
Sync radial config to <player>: isOp=true, applied=false, force=false, available=true
```

`isOp=false` for an operator means the server is not seeing them as OP — check
`op-permission-level` and that the player is in `ops.json`. If **no such line appears at
all**, the server does not have the mod installed, and the client reports *"The server does
not have the Radial mod"* when a button is clicked.

### Imported icons and sounds travel with the config

Icons imported with **Import Image** and sounds imported with **Import Sound** live only in
the importing player's `config/radialmenu/icons/` and `config/radialmenu/sounds/`, while the
config itself stores nothing but a reference (`imported:my_icon`, `radialmenu:custom.my_sound`).
Uploading the bare JSON would therefore leave every other player with blank icons and silent
slots, because the files never left the operator's machine.

So the files are uploaded with the config and share its lifecycle:

1. Clicking **Apply as Server Settings** reads every imported asset the config references and
   uploads the bytes alongside the JSON.
2. The server stores them in `config/radialmenu-server-assets/` and keeps them in memory.
3. Every join and every broadcast resends the config **and** its assets together, so players
   receive them automatically and write them into their own config folders.

There is no on-demand download: the assets are part of the config, swapped in and out with it.
Uploading a config that references no imported assets clears the server's copies, so removing
an icon upstream does not keep shipping the old one.

Two consequences worth knowing:

- Only assets that actually exist locally can be uploaded. A reference with no local file is
  logged as a warning and renders blank for everyone else.
- An uploaded asset set may not exceed about 1.5 MB. Uploads are chunked because a single
  client→server packet is capped at 32 KB by vanilla, and a single asset is capped at 8 MB.

---

## Config files

| File | Purpose |
| --- | --- |
| `config/radialmenu.json` | Client configuration. |
| `config/radial_icon_history.json` | Recently used icons. |
| `config/radialmenu-server.json` | Server configuration (server side). |
| `config/radialmenu-server-control.json` | Server switches and per-player preferences (server side). |
| `config/radialmenu-server-unlocks.json` | Server-side authoritative slot unlock states, per player (server side). |
| `config/radialmenu-server-assets/` | Imported icons and sounds published with the server config (server side). |
| `config/radialmenu-servers/<server>.json` | Per-server custom copies. |
| `config/radialmenu-servers/<server>.runtime.json` | Per-server runtime state: toggle states and condition unlocks. |
| `config/radialmenu-servers/<server>.forced.json` | Reference copy of a forced server configuration (read-only in effect). |
| `config/radialmenu/icons/` | Imported images. |
| `config/radialmenu/sounds/` | Imported audio. |

Configs are written atomically via a temp file, and an unreadable config is backed up to
`radialmenu.json.bak` rather than discarded. Older config versions are migrated
automatically on load.

---

## Extending it

The `api` package is the extension surface. A mod can register an entrypoint to add slot
modes or screen shortcuts:

```json
{
  "entrypoints": {
    "radialmenu": ["com.example.mymod.MyRadialPlugin"]
  }
}
```

```java
public class MyRadialPlugin implements RadialApiEntrypoint {
    @Override
    public void registerSlotModes() {
        SlotModeRegistry.register(
                ResourceLocation.fromNamespaceAndPath("mymod", "my_mode"),
                new MySlotMode());
    }

    @Override
    public void registerShortcuts() {
        ShortcutRegistry.register(
                ResourceLocation.fromNamespaceAndPath("mymod", "my_screen"),
                new ShortcutEntry(Component.literal("My Screen"), parent -> { /* ... */ }));
    }
}
```

Toggle sources from other config libraries can be contributed by implementing
`ToggleSourceProvider` and registering it with `ToggleSourceRegistry.registerProvider`.

---

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/`. Requires JDK 21.

---

## Version support

This mod follows a **forward-only** development cycle.

- Development and testing target the newest supported Minecraft version only.
- Features and bug fixes are **not backported** to older versions.
- Past releases stay available for older modpacks, but are provided as-is.
- Community backports and maintenance PRs for older versions are welcome.

---

## License

GPL-3.0. See [LICENSE](LICENSE).
