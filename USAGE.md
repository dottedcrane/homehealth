# Casa Health — Usage Tips

A practical guide to getting real value out of the app. The 3D home is the map; the
**maintenance hub is the point.**

---

## 1. Getting started

1. The app opens on the **neighborhood** — your own lot plus five model homes around a
   cul-de-sac (The Classic, The Estate, The Townhouse, The Ranch, and The Penthouse condo
   tower).
2. **Tap any home** to preview its exterior and floor plan; press **Mine** to claim it — or
   claim your own lot as-is. Either way you'll be asked for the **year built** and **year
   purchased**.
   - *Tip: enter these as accurately as you can. Every lifespan rating falls back to the build
     year when an item has no install year of its own, so one honest date immediately makes the
     whole inventory meaningful.*
3. Your claimed home saves automatically on-device and reloads every time you open the app.
   There is no account and nothing is uploaded — see [PRIVACY.md](PRIVACY.md).

## 2. The maintenance hub — how it actually works

Open it with the **wrench icon** (Exterior, Floor Plan, Room, and Neighborhood views). The
hub's model in one paragraph:

> **What you build determines what you maintain.** Tasks exist only for what your home
> actually has — its style, its exterior features (garage, pool, deck, yard), its systems
> (HVAC, solar), the room types you've built, and the vehicles you've parked. Add a pool and
> pool tasks appear; remove the deck and deck tasks disappear. Each physical thing is tracked
> as its own instance with two independent signals: an **A/B/C lifespan rating** (how far into
> its expected service life it is) and a **green/amber/red upkeep dot** (whether its recurring
> maintenance is current). Marking a task done never ends it — the next occurrence lands in
> the next real calendar season.

### To-Do tab

- Tasks are sorted by due date and anchored to **real seasons**, not rolling countdowns:
  monthly tasks roll continuously; quarterly tasks anchor to March/June/September/December;
  biannual to March + September; annual tasks to the single season that fits the job (water
  heater flush before winter, HVAC service before cooling season, boat winterization in
  October).
- **Mark Done** records today locally and recomputes the next due date.
- **Add to calendar** hands the task to your own calendar app, pre-filled — you press save
  there. The app itself never touches your calendar.
- *Tip: don't try to zero the list in one weekend. The seasonal anchors exist so three or four
  tasks surface at a time; a green To-Do list in March says nothing about September.*

### Score tab

Grouped so the actionable things sit on top:

- **Needs Maintenance** — every item with a recurring task, per room, with both badges.
  Multiple copies are independent: three bathrooms means "Toilet 1/2/3", each with its own
  install year and rating.
- **Vehicles** — every car, boat, and motorcycle parked on your lot, tracked individually
  ("Car 1", "Boat 1", …). Tapping a row opens its card; tapping the vehicle in 3D opens its
  actions bar instead (paint color, gas/electric, Track → card, Remove).
- **Structural** — home-wide assets that aren't 3D objects: Roof, Siding, Plumbing, and
  Electrical Panel always; Garage Door, Smoke & CO Detectors, Windows & Doors, Fire
  Extinguisher, Deck, Lawn Equipment, Pool Equipment, and Balcony when they apply to your
  home.
- **Not in my home** on any card removes the item — and for structural assets also drops its
  tasks from the Checklist entirely. Removed structural assets wait in **Available to Add**
  at the bottom; tap to restore.
- **Other Items** — tracked items with no recurring schedule; tap to record install year and
  condition anyway.
- *Tip: work through the inventory once, room by room, marking what you don't own and setting
  install years for big-ticket items (water heater, HVAC, roof, appliances, car). Fifteen
  minutes of setup is what turns the ratings from generic averages into your home's truth.*

Note: plain **furniture is deliberately not tracked** — sofas and desks are décor you drag
into rooms, not maintenance items. The hub's scope is upkeep.

### Guides tab

Step-by-step DIY guides (HVAC, Plumbing, Electrical, Exterior, Appliances) with warning signs,
numbered steps, safety notes, and — read these — **when to call a professional**.

### Pros tab

Your own list of service contacts (name, trade, phone, email, notes), typed in by you and
stored only on-device. *Tip: add the tech's name right after a good service visit, in the
notes put what they charged — future-you will thank you.*

### Documents

Any item card can attach warranties, manuals, and receipts via the system file picker. The app
stores references, not copies, and includes the files in backups you export.

### Backup & Restore (Premium)

From the home icon → **Backup App Data** exports one `.zip` (database + documents) wherever
you choose; **Restore App Data** overwrites everything from a backup after confirmation and
restarts the app. These features require a one-time premium purchase to unlock.

*Tip: export a backup after the initial Score setup and after any big
edit — the zip is small, and restore is all-or-nothing.*

## 3. Vehicles and the garage

- In the exterior view, **long-press the car, boat, or motorcycle icon** in the pane
  and drag it onto the lot. A car dropped on the garage parks inside — the garage slot holds
  exactly one; everything else takes a free driveway spot (vehicles never stack).
- **Tap the garage door** (or the garage slab in the floor plan) to step into the **garage
  scene** — the door rolls open, a parked car pulls out onto the driveway. Tap the door to
  close/open it; drag vehicles around the driveway; drag one to the street end to remove it.
- Every vehicle is a tracked inventory instance: tapping it in 3D opens its actions bar (paint
  color, gas/electric, Track → maintenance card, Remove), and its tasks (oil/tires/fluids/
  service for cars, winterization/hull for boats, oil-and-chain/season-prep for motorcycles)
  join the Checklist automatically — mark a vehicle electric and its oil-change task drops off
  once every one of that type on the lot is electric too.

## 4. Building and furnishing the 3D home

- **Exterior pane**: toggle features (garage/yard/pool/deck — tap again to cycle sides) and
  systems (HVAC, solar). Tap the front door to go inside; tap HVAC/solar for their cards.
- **Floor plan**: tap a room to step into it (works even when the tap lands on furniture);
  long-press a room for options (enter / change type / remove). Toggle the **pencil** for all
  editing — wall modes (Solid/Open/Door/Window), selecting empty tiles to Create Room, and
  adding/removing columns, rows, and floors. The gear is camera-only.
- **Room view**: long-press a furniture icon in the tray and drag it in; tap placed furniture
  for rotate/flip/remove; long-press-drag to move it; drag it out through a wall to remove.
  Appliances and fixtures are toggled from the pane, and tapping one opens the same actions
  bar with a Track icon that opens its maintenance card.
  Positions persist automatically.

## 5. Camera

| Gesture | Action |
|---|---|
| 1-finger drag | Orbit (or pan, in Map mode) |
| 2-finger pinch | Zoom |
| 2-finger drag | Pan |

Every scene's **gear** has its own settings (Orbit/Pan mode, gesture toggles, sensitivity,
Reset view), remembered per scene. The room view adds **angle presets**: Eye (doorway),
Corner (whole room), Top (overhead).

---

## Safety — children

Casa Health schedules reminders; **you** create the conditions the work happens in. Several
tasks it will remind you about involve real hazards to children if performed carelessly or
left in an unsafe state:

- **Garage doors** — a leading cause of child injury around the home. Keep the auto-reverse
  test current, keep remotes and keypads away from small children, and never let a child
  stand or play in the door's path — in the real garage, not the 3D one.
- **Vehicles and driveways** — never leave children unattended around a vehicle being
  serviced. Jacked-up cars, hot engines and exhausts, and running engines in a closed garage
  (carbon-monoxide risk) are all adult-only zones. Keep keys out of reach.
- **Vehicle fluids** — motor oil, coolant/antifreeze (sweet-tasting and highly toxic), brake
  fluid, and fuel must be stored locked away and spills cleaned immediately; the same goes for
  boat fuel stabilizer and battery acid.
- **Pool equipment and chemicals** — store chemicals locked away regardless of how current
  the water-chemistry task is, and maintain active adult supervision at the pool. Equipment
  maintenance is never a substitute for supervision or a proper fence.
- **Fire extinguishers and smoke/CO detectors** — installed, tested, accessible to adults,
  and out of reach of young children who could discharge or disable them.
- **Tools, ladders, and chemicals for any task** (gutters, deck sealing, HVAC coils, caulk,
  lawn equipment) — keep children and pets out of the work area while a task is in progress,
  and secure everything immediately afterward, not just once the checklist shows green.
- **Electrical panels, HVAC, and solar** — adult-only and, in most cases,
  professional-supervised. Keep panel access restricted at all times.

The DIY guides are informational only — follow manufacturer instructions and local codes, and
when in doubt (especially with electrical, gas, or structural work), use the **Pros** tab for
what it's for.
