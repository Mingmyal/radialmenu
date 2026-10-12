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

Consume-type conditions unlock either **permanently** or for a **timed** duration, and the
unlock state is saved to the config, so it survives a restart. While a condition is unmet
the slice is drawn in its own red-brown palette, optionally with a slash and a dimmed
icon, and the hover hint explains what is needed. Confirmation prompts before consuming
can be turned off in the settings.

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

---

## Config files

| File | Purpose |
| --- | --- |
| `config/radialmenu.json` | Client configuration. |
| `config/radial_icon_history.json` | Recently used icons. |
| `config/radialmenu-server.json` | Server configuration (server side). |
| `config/radialmenu-server-control.json` | Server switches and per-player preferences (server side). |
| `config/radialmenu-servers/<server>.json` | Per-server custom copies. |
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
