# Privacy Policy — Casa Health

**Effective: July 29, 2026**

Casa Health is an offline home-maintenance tracker. The short version: **there is no account,
no server, no analytics, and no data collection.** Everything you enter stays in the app's
private storage on your device, is used only to show you your own maintenance schedule, and
leaves your device only when *you* export it.

---

## What the app stores on your device

All of the following lives in a local database in the app's private internal storage
(inaccessible to other apps without root). None of it is transmitted anywhere by this app.

**Your home model**
- Home style, floor plan, exterior features (garage, pool, deck, yard), and systems
  (HVAC, solar).
- Furniture and vehicle placements, rotations, and positions.

**Maintenance hub data** — the heart of the app, and the most personal data it holds:
- Your home's build year and purchase year (used only as a fallback when estimating an
  item's age).
- Per-item install years for appliances, fixtures, structural assets, and vehicles.
- Task completion dates ("Mark Done" timestamps) used to compute the next seasonal due date
  and the green/amber/red upkeep status.
- Per-task snooze and mute state, set from a task's detail popup in the To-Do tab.
- Which items you've marked "not in my home."

**Documents you attach**
- Warranties, manuals, and receipts are attached through Android's system file picker
  (Storage Access Framework). The app stores a *reference* (a persistable URI) plus the
  display name — it can read only the specific files you picked, and only to display them
  or include them in a backup you request. It never scans, copies, or requests broad access
  to your storage.

**Pro contacts**
- The Pros tab stores service contacts (name, trade, phone, email, notes) **that you type in
  yourself**. The app never reads your device's contact book — it has no contacts permission.
  Remember these entries describe other people; they are included in backups you export, so
  share those files thoughtfully.

## What the app never collects

- No analytics, crash reporting, advertising identifiers, or usage tracking of any kind.
- No account, sign-in, email address, or other personally identifying information is requested.
- No location, camera, microphone, or contact-book access.
- No data is transmitted off your device by this app, at any time, for any reason.

## Network

The app declares the `INTERNET` permission for two reasons:
1. So the system calendar-picker Intent works on all devices.
2. To communicate securely with the **Google Play Store** for in-app purchase validation and
   localized pricing.

The app itself makes **no other network requests**, has no background sync, and sends no
push notifications. It remains fully functional in airplane mode (except for processing new
purchases).

## Calendar

"Add to calendar" hands the task's title, description, and due date to **your own calendar
app** via a standard system Intent. Saving the event is your calendar app's action, under its
own account and permissions. Casa Health never reads your calendar and holds no calendar
permission.

## Backups — the only way data leaves the app

- **Backup App Data** writes a single `.zip` — your home database, maintenance records, pro
  contacts, and attached documents — to a location *you* choose in the system file picker.
  The file is **not encrypted** by the app; treat it like any personal file when storing or
  sharing it.
- **Restore App Data** reads a backup `.zip` you pick and overwrites current data after an
  explicit confirmation.
- Separately, the app allows **Android's own OS backup** (`allowBackup`), so your device may
  include the app's data in the device backup tied to your own Google account, under the
  backup settings and encryption *you* control in Android — this is an OS feature, not an app
  upload.

## Children's privacy

Casa Health is intended for adult homeowners and renters managing home maintenance. It is not
directed at children, does not knowingly collect personal information from anyone — including
children under 13. While the app includes a one-time in-app purchase to unlock premium features,
it contains no ads, chat, social features, or any other mechanism that could solicit information
from a child. Because the app has no tracking or data collection, there is no child data to
collect, sell, or share.

Physical safety matters more here than data: many maintenance tasks the app reminds you about
(garage doors, pool chemicals, power tools, vehicle fluids) are hazardous to children. See the
**child-safety warnings** in [USAGE.md](USAGE.md#safety--children) — the adult performing a
task is responsible for keeping the work area child-safe before, during, and after it.

## Deleting your data

Uninstalling the app (or clearing its storage in Android settings) permanently deletes the
local database and all maintenance records. Backup `.zip` files you exported remain wherever
you saved them — delete those yourself if you no longer want them. Attached original documents
are your files and are never touched by uninstalling.

## Changes to this policy

If a future version ever changes what is stored or introduces any network feature, this policy
will be updated in the app repository before that version ships, with the effective date above
revised.
