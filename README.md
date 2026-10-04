# MegaCityDonations 1.0.3

A donation tracking plugin for Paper 26.2 and Java 25. Players see their
purchased features, the amount paid for each purchase, and their total donations.
The plugin tracks payments and supports explicit operator-run permission activation.
It does not process payments. Recording or removing a purchase never runs commands automatically.

## Install

Stop the server, place `MegaCityDonations-1.0.3.jar` in `plugins/`, and start it.
This plugin can run alongside MegaCityLight. No additional plugins or libraries
are required. Developer metadata identifies Tommy10606 and links to
https://github.com/tommy10606/MegaCityDonations.

The first startup creates `plugins/MegaCityDonations/config.yml` containing
only the requested default IDs: `nickname` ($5.00, not repeatable) and `eff7`
($10.00, repeatable). Nickname lists `essentials.nick`, `essentials.nick.color`,
and `essentials.nick.format` as hover nodes. Neither definition creates any
player purchases. New IDs created by command still write `repeatable: false`.
Existing config files are preserved; changing bundled defaults does not overwrite
an existing server catalog.

## Player commands

| Command | Result |
| --- | --- |
| `/mydonations` | Your purchases and total donated. Available to everyone. |
| `/mydonations 2` | Page 2 of your purchases. |

The chat display uses an aqua heading, white feature names, and gold amounts in the configured currency:

```text
♦ Your Donations
2 purchases · Total donated: $15.00
 • Nickname · $5.00
 • Eff7 · $10.00
Page 1/1
```

There are no category tabs. When more than one page is needed, clickable
Previous/Next buttons appear. The default page size is eight purchases.
Hovering over a feature name shows its configured permission nodes. If its
permission list is missing or empty, there is no hover tooltip.

## OP and console commands

These commands are restricted in code to operator players and the local/remote
server console. Adding a permission through another plugin does not bypass the
OP requirement. Command blocks are not authorized. `/mydonations` for one's own
records requires neither OP nor a permission node.

| Command | Result |
| --- | --- |
| `/mydonations PLAYER [PAGE]` | View another player's purchases and total. |
| `/mydonations reload` | Reload the catalog and saved player records. |
| `/mydonations version` | Show the active plugin version (OP/console only). |
| `/adddonation PLAYER DONATIONID [--override]` | Record one purchase; `--override` skips prerequisite checks only. |
| `/removedonation PLAYER DONATIONID` | Remove the most recent matching purchase and subtract its original amount. |
| `/donation activate PLAYER DONATIONID` | Run the activation template as console for each listed permission node. |
| `/donation deactivate PLAYER DONATIONID` | Run the deactivation template as console for each listed permission node. |
| `/donation top [PAGE]` | Rank players by recorded total and show the grand total (OP/console only). |
| `/donation leaderboard [PAGE]` | Alias for `/donation top`. |
| `/donation total` | Grand total recorded across all players (OP/console only). |
| `/donation list` | List every ID as `ID - $AMOUNT - Repeatable/Not Repeatable`. |
| `/donation info ID` | Show name, price, repeatability, and permission nodes. |
| `/donation create ID PRICE NAME...` | Create an ID with an explicit `repeatable: false` and empty permission list. |
| `/donation rename ID NAME...` | Change the displayed feature name. |
| `/donation price ID AMOUNT` | Change its price for future assignments. |
| `/donation repeatable ID true` | Allow repeated purchases of this ID. |
| `/donation repeatable ID false` | Reject future duplicate assignments. |
| `/donation addpermission ID NODE` | Add a permission node to the hover list. |
| `/donation removepermission ID NODE` | Remove a permission node from the hover list. |
| `/donation adddependency ID REQUIRED_ID` | Require a recorded purchase of another donation ID. |
| `/donation removedependency ID REQUIRED_ID` | Remove that prerequisite. |

Catalog commands save to `config.yml` and apply immediately, without a reload.
Console commands use the same syntax, normally without the initial slash.
Command names and IDs have tab completion. Offline players can be assigned,
corrected, and viewed if they have previously joined this server. Player UUIDs
can also be used in place of names. Unknown names are rejected rather than
creating a record for a typo. UUIDs identify purchases, so name changes do not
create a new account. If multiple local identities share a name, use the UUID.

## Example workflow

```text
/donation info nickname
/adddonation Tommy10606 nickname
/mydonations Tommy10606
```

The default ID `nickname` already exists in the generated config. For a new ID:

```text
/donation create specialtool 9.00 Special Tool
/donation repeatable specialtool true
/adddonation Tommy10606 specialtool
/adddonation Tommy10606 specialtool
/removedonation Tommy10606 specialtool
```

This leaves one $9.00 tool purchase. A duplicate assignment of a non-repeatable
ID is rejected without changing the total. Removing that purchase allows the
same ID to be assigned again. Disabling repeatability after multiple purchases
does not erase those existing purchases; it only rejects further duplicates.

## Configuration

```yaml
page-size: 8
currency: USD
donations:
  nickname:
    name: Nickname
    price: '5.00'
    repeatable: false
    permissions:
      - essentials.nick
      - essentials.nick.color
      - essentials.nick.format
  eff7:
    name: Eff7
    price: '10.00'
    repeatable: true
    permissions: []
```

IDs use lowercase letters, numbers, underscores, or hyphens, up to 64 characters.
Prices are nonnegative values in the configured currency with at most two decimal places. Quote prices
in YAML as shown above. `repeatable` defaults to false when omitted. Page size
must be a whole number from 1 to 20. Permission lists supply hover information and the nodes used by explicit activation/deactivation.
They do not verify the player currently has those permissions.

## Currency display

`currency` defaults to `USD`, even if omitted. Set a supported currency code,
such as `EUR`, `GBP`, or `CAD`, and run `/mydonations reload`. The plugin uses
that currency's symbol in player summaries, donation ID lists, information,
and add/remove confirmations. For example, `EUR` displays `€5.00`. All amounts
retain two decimal places. Invalid codes are rejected on reload and preserve
the previous live configuration.

This is a single-currency tracker. Changing the setting changes the symbol for
all purchases and totals; it does not perform exchange-rate conversion or store
separate currencies per purchase. The numerical amounts paid remain unchanged.

## Saved records and corrections

`plugins/MegaCityDonations/players.yml` contains player UUIDs, last known names,
and individual purchases. Each purchase records its ID, original name, exact
amount paid, and assignment time. Player records and catalog edits are saved
immediately using temporary files and atomic replacement where supported.
Failed writes do not commit the change to live memory.

Totals are calculated from the recorded payments using decimal arithmetic.
They include every repeat purchase, and reflect corrections made with
`/removedonation`. Changes to catalog prices never rewrite historical amounts.
Creating a new ID for a new price works too. A generic donation can be tracked
by defining a repeatable ID even when no feature accompanies it.

The current catalog controls feature display names and hover permissions.
Renaming a feature changes its displayed name on existing purchases. If its ID
is removed from the catalog, its purchases and total remain visible using the
stored original name, and corrections still work. Such an entry has no hover
permissions. Removing a record corrects tracking only; it does not issue a
refund or revoke a permission. Adds and removals are also logged to the console.

Keep and back up the whole plugin folder when updating or moving servers.
When manually editing YAML, use `/mydonations reload`; invalid files retain the
previous live state. Invalid files at boot disable the plugin rather than
silently resetting records. Changing a server between online/offline UUID modes
requires deliberate migration of player records.

## Build and validation

Use JDK 25 and Maven 3.9 or newer:

```sh
mvn clean package
```

The JAR is `target/MegaCityDonations-1.0.3.jar`. The Maven version supplies the
plugin version automatically. Tests use MockBukkit for Paper 26.2 and cover
access controls, duplicate rejection, repeat purchases and latest corrections,
offline players, restart persistence, immutable paid amounts, catalog commands,
hover presence/absence, pagination, safe reloads, and failed writes. Results
are included in `TEST-RESULTS.txt`. Live client/server gameplay still needs a
quick verification after installation, especially hover text and pagination.

The plugin uses public APIs and declares `api-version: '26.2'` as its minimum.
Future Paper versions can work without rebuilding if those APIs remain
compatible, but future releases are not guaranteed or tested. This plugin does
include the GitHub update checker described below.

## Version and GitHub update verification

`/mydonations version` displays the active version from plugin metadata and
requires OP or console access. Paper's built-in `/version MegaCityDonations`
also shows the version, developer, and GitHub website. Access to Paper's own
version command follows the server's own permissions.

Once per server boot, after a one-second delay, the plugin checks:

```text
https://api.github.com/repos/tommy10606/MegaCityDonations/releases/latest
```

The request runs asynchronously with five-second connection and read timeouts.
It does not block the game thread. It prints one of the following to console:

```text
[MegaCityDonations] Update Available!
[MegaCityDonations] Running the most up to date version.
```

An available update also prints the installed version, latest tag, and download
link. No public stable release produces an unknown-status message; network
failures and rate limits produce a warning. There are no periodic checks,
repeating notices, chat notifications, automatic downloads, or installations.
Donation tracking continues if GitHub is unavailable.

Publish a GitHub Release with a stable version tag such as `v1.0.1` or `v1.0.2`,
not just a Git tag. Mark the desired stable version as the latest release.
Drafts and prereleases are not used. Versions are compared numerically. Public
release checks require no token and need outbound HTTPS to `api.github.com`.

To disable the check, set this in `config.yml`, then restart the server:

```yaml
update-checker:
  enabled: false
```

## Explicit permission activation and deactivation

These commands require OP or console access:

```text
/donation activate PLAYER DONATIONID
/donation deactivate PLAYER DONATIONID
```

They execute one console command for every permission node listed for that
ID in the live catalog. Configure the templates in `config.yml`:

```yaml
permission-commands:
  activate: 'manuaddp {player} {permission}'
  deactivate: 'manudelp {player} {permission}'
```

These GroupManager-style commands are also the defaults when the settings
are absent. Change the templates to your permission plugin's documented
commands, then run `/mydonations reload`. No permission plugin is bundled.
An empty string disables an action. Each template is a single command string;
use `{player}` for the local player name, `{uuid}` for their UUID, and
`{permission}` for the node. Templates need `{permission}` and either
`{player}` or `{uuid}`. A leading slash is optional and removed automatically.
Permission nodes, including any leading minus sign, are passed literally.

For example, a `pv1` definition listing `playervaults.amount.1` makes
`/donation activate Tommy10606 pv1` submit
`manuaddp Tommy10606 playervaults.amount.1` as console. Deactivation submits
`manudelp Tommy10606 playervaults.amount.1`. Use the exact node spelling from
your permission plugin; MegaCityDonations does not correct node names.

Activation and deactivation are separate from payment tracking. They never create a recorded purchase, change payment totals, or mark a
purchase active/inactive. Activation requires that the donation ID itself is
already assigned to the target player. Deactivation does not require a
recorded purchase, so it remains usable for cleanup after removal. `/adddonation` and `/removedonation` still only
change records. Running an action again submits the commands again. An ID
with no nodes reports that nothing is configured. Targets must be known local
players. Whether commands support offline players depends on the permission
plugin; name templates require a cached valid player name, while UUID templates
can target known UUIDs without a cached name.

The plugin reports how many commands were accepted for dispatch, and logs the
operator, target, ID, and dispatch count. A permission plugin can accept a
command but report its own error, so inspect its output to confirm changes.
Partial failures are reported and are not rolled back. Templates are never
executed on boot, reload, purchase assignment, or purchase removal.

## Donation purchase dependencies

Set `requires` under an ID in `config.yml`, then run `/mydonations reload`:

```yaml
donations:
  pv1:
    name: Player Vault 1
    price: '10.00'
    repeatable: false
    permissions: [playervaults.amount.1]
    requires: []
  pv2:
    name: Player Vault 2
    price: '9.00'
    repeatable: false
    permissions: [playervaults.amount.2]
    requires: [pv1]
```

This example is documentation only; the bundled defaults remain `nickname`
and `eff7`. `/adddonation PLAYER pv2` requires a saved purchase of `pv1`.
Every ID listed in `requires` must be present in that player's purchase
records. The checks use UUID records and work for offline players. At least
one purchase of each prerequisite is sufficient, including repeatable IDs.
An omitted or empty list means no prerequisites. New IDs created by command
write `requires: []` as well as `repeatable: false`.

Operators and the console can manage prerequisites immediately with:

```text
/donation adddependency pv2 pv1
/donation removedependency pv2 pv1
/donation info pv2
```

The first ID is the donation being restricted; the second is the required
purchase. Info lists every prerequisite. Unknown IDs, self-dependencies,
and cycles are rejected before saving or replacing the live configuration.
Remove references to an ID before deleting its catalog definition.

For migration of previous donors, explicitly bypass prerequisites with:

```text
/adddonation PLAYER DONATIONID --override
```

The override is OP/console only, is logged as `[DEPENDENCY OVERRIDE]`, and
skips only the prerequisite check. It still rejects duplicate assignments
unless `repeatable: true`, and records the catalog's current price as usual.
It does not assign the missing prerequisites or run permission commands.
Invalid flags are rejected. There is no global bypass setting.

Existing purchases remain untouched when dependencies change. Corrections
may remove a prerequisite without erasing a dependent purchase or revoking
permissions. Future assignments check the currently recorded prerequisites.
Only explicitly listed IDs are checked; prerequisites are not recursively
rechecked against old purchases. Activation instead checks ownership of the
ID itself, so migrated assignments can activate normally. Activation has no
`--override` option. Deactivation remains available after records are removed.
Direct manual edits to `players.yml` are not retroactively dependency-checked;
reload preserves historical records and original payment amounts.

## Changes in 1.0.3

- Added configurable prerequisite donation IDs and commands to add/remove them.
- Rejects missing references, self-dependencies, and circular dependencies.
- Added OP/console-only `--override` on `/adddonation` for historical migrations.
- Activation now requires the target donation ID to be assigned to the player.
- Existing records, totals, deactivation cleanup, and duplicate rules are preserved.
- `/mydonations version` reports **1.0.3**.

## Donation leaderboard and grand total

Run `/donation top` or `/donation top 2` to see players sorted by the sum of
payments in `players.yml`, highest first. `/donation leaderboard` is an alias.
Offline players are included. Players with zero totals are omitted from the
ranking. Ties sort by name, then UUID; displayed positions are sequential.
Pages use the existing `page-size` setting and clickable Previous/Next links.
The grand total across every player's records is shown on each page; use
`/donation total` to show it alone. Both commands require OP or console access.

Totals use original recorded payment amounts, include repeat purchases, and
reflect corrections immediately. Catalog price changes do not rewrite them.
Manual changes to `players.yml` take effect after `/mydonations reload`.
All amounts use the configured currency. These are tracking totals, not a
payment-provider balance or verification of received payments.

## Changes in 1.0.2

Added the operator-only leaderboard, grand total, and explicit configurable
permission activation/deactivation commands. Names in the
catalog must be one line, and commands containing pasted line breaks are
rejected before any changes are made. Enter console commands individually.
If a previous bulk paste corrupted a catalog name, correct that `name` in
`config.yml` before upgrading. Existing malformed catalog names are reported
rather than silently truncated. Editing a purchase's name in `players.yml`
does not override the current catalog's display name.

`/mydonations version` was updated to **1.0.2** in that release.

## Changes in 1.0.1

Fixed tab completion and player targeting triggering Minecraft offline-player
NBT loads and data conversion on the server thread. Older offline identities are
indexed once in the background from player-data filenames and `usercache.json`.
Modern `players/data` and legacy `playerdata` directories are supported. Tab
completion and targeting use in-memory identities only. Players saved in
`players.yml` remain available immediately; older identities become available
when the background index finishes. If an old name is no longer cached, use
the player's UUID or have them join once. No player NBT is read or modified.

`/mydonations version` was verified for OPs and the console in this release. The existing
boot-only GitHub release check remains enabled by default.

To update, stop the server, remove the old MegaCityDonations JAR, install the
new JAR, and restart. Keep the plugin folder, config, and player records.
