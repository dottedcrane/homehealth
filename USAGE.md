# Casa Health — Usage Tips

A practical guide to getting real value out of the app. The 3D home is the map; the
**maintenance hub is the point.**

---

## 1. Getting started

1. The app opens on the **neighborhood** — your own lot plus five model homes (The Classic,
   The Estate, The Townhouse, The Ranch, and The Penthouse condo tower) gathered around a
   central green, with the main road looping around the outside of them and footpaths running
   from each home to the park.
2. **Tap any home** to preview its exterior and floor plan; press **Mine** to claim it — or
   claim your own lot as-is. Either way you'll be asked for the **year built** and **year
   purchased**.
   - *Tip: enter these as accurately as you can. Every lifespan rating falls back to the build
     year when an item has no install year of its own, so one honest date immediately makes the
     whole inventory meaningful.*
3. Your claimed home saves automatically on-device and reloads every time you open the app.
   There is no account and nothing is uploaded — see [PRIVACY.md](PRIVACY.md).

## 2. The maintenance hub — how it actually works

Open it with the **wrench icon** — it's on every scene: Exterior, Floor Plan, Room, Driveway,
Attic, and Neighborhood. The hub's model in one paragraph:

> **What you build determines what you maintain.** Tasks exist only for what your home
> actually has — its style, its exterior features (garage, pool, deck, yard), its systems
> (HVAC, solar), the room types you've built, and the vehicles you've parked. Add a pool and
> pool tasks appear; remove the deck and deck tasks disappear. Each physical thing is tracked
> as its own instance with two independent signals: an **A/B/C lifespan rating** (how far into
> its expected service life it is) and a **green/amber/red upkeep dot** (whether its recurring
> maintenance is current). Marking a task done never ends it — the next occurrence lands in
> the next real calendar season.

### The shortcut: status icons in the scene

You rarely need to open the hub to answer "does anything here need me?". The **Room, Driveway,
and Attic** panes — all three the same pane now — each carry a short strip of **red / amber / green icons** — one per thing in
that place with recurring upkeep, worst first. **One tap opens that item's maintenance card**,
which lists its due tasks with dates and a mark-done button beside each.

- Kitchen: fridge, dishwasher. Laundry: washer, dryer, water heater. Driveway: your vehicles
  and the garage door. Attic: HVAC, ductwork, insulation, solar, EV battery — with a second row
  beside it saying which of those your home actually has (see §4a).
- Rooms with nothing to maintain — most bedrooms and living rooms — show no strip at all.
  That's expected, not a bug.
- **Amber means "due soon *or* never done"**, so a home you've just claimed reads amber across
  the board. That's the "get started" state, not a warning.
- *Tip: this is the fastest way to record install years. Walk the house, tap each coloured
  icon, fill in the year you remember. It beats scrolling the Score tab room by room.*

### To-Do tab

- Each row is one line — priority dot, title, due label, check — **tap it to open the full
  detail**: description, frequency/priority/cost chips, what it's tracked against, and a
  **View repair guide** button when the task has one.
- Order is **severity-weighted**: the most overdue, highest-priority tasks rise to the top;
  muted tasks always sink to the bottom. It's not simply soonest-due-first.
- **Snooze 14d** (in the detail popup) pushes a task's due date out two weeks without marking
  it done — use it for "I know, not this week." **Mute reminders** stops a task competing for
  attention entirely while keeping it visible in the list; un-mute any time.
- **The list follows you.** Standing in a room, on the driveway, or in the attic scopes the list to
  that place's own tasks — a header names the scope and offers **Show all** to see everything
  anyway. Exterior, Floor Plan, and Neighborhood always show the full list.
- Tasks are anchored to **real seasons**, not rolling countdowns: monthly tasks roll
  continuously; quarterly tasks anchor to March/June/September/December; biannual to March +
  September; annual tasks to the single season that fits the job (water heater flush before
  winter, HVAC service before cooling season, boat winterization in October).
- **Mark Done** (on the row, or in the detail popup) records today locally and recomputes the
  next due date.
- **Add to calendar**, from the detail popup, hands the task to your own calendar app,
  pre-filled — you press save there. The app itself never touches your calendar.
- *Tip: don't try to zero the list in one weekend. The seasonal anchors exist so three or four
  tasks surface at a time; a green To-Do list in March says nothing about September.*
- *Tip: scoping means most single rooms show an empty state — that's expected. Only the
  kitchen, laundry, and driveway carry place-attributable tasks; everything else lives at the
  home level and shows up under Exterior/Floor Plan or via Show all.*

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

Note: plain **furniture carries no recurring tasks** — nothing about a sofa or a desk needs
doing on a schedule, so they never reach the To-Do list. They do appear under **Other Items**,
where an install year gives them a lifespan rating like anything else.

### Guides tab

Step-by-step DIY guides (HVAC, Plumbing, Electrical, Exterior, Appliances) with warning signs,
numbered steps, safety notes, and — read these — **when to call a professional**. *Tip: if a
To-Do task already has a guide, its detail popup has a* **View repair guide** *shortcut — no
need to go hunting through this tab first.*

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

## 3. Vehicles and the driveway

- **Vehicles are added on the driveway**, not from the exterior. You walk onto it like a room:
  the **Garage icon** in the exterior pane (next to the Attic icon), the **garage door** in the
  exterior, or the **garage slab** in the floor plan.
- Once there, **long-press the car, boat, or motorcycle icon** and drag it onto the lot. A car
  dropped on the garage parks inside — that slot holds exactly one; everything else takes a
  free driveway spot (vehicles never stack). Tap the door to close/open it — when it's open a
  lip stays showing at the top of the opening, so you can close it without moving the camera;
  drag vehicles around the driveway; drag one to the street end to remove it.
- **Your cars stay where you put them, and show up everywhere.** One lot in real coordinates:
  the car halfway down the driveway is in the same spot in the exterior, in the floor plan, and
  seen through the wall from inside the house. Only the driveway lets you move them.
- **The garage isn't just for cars.** Its walls are see-through, so you can see what's in there
  from outside. On the driveway, the tray offers what a garage holds — water heater, spare fridge,
  washer, dryer, gym gear, keepsakes — and a drop lands *inside* the garage, not on the tarmac.
  How much fits is the garage's own floor, so another car never makes room for another freezer.
  If your garage is a room in the floor plan (Townhouse, Classic), it's the same garage either
  way: drop something from the driveway and you'll find it in the room, and vice versa.
- **No garage? You still get the lot.** A condo — which can own neither a garage feature nor
  a garage room — parks in the open: the same lot, driveway, and street, without a shell or
  overhead door, and its tray stays vehicles-only since there's nothing to put a water heater in.
  The same goes for a house before you've dragged a garage onto it. Every home can own and track
  vehicles.
- Every vehicle is a tracked inventory instance: tapping it in 3D opens its actions bar (paint
  color, gas/electric, Track → maintenance card, Remove).
- **Each vehicle keeps its own schedule.** Tasks are generated per vehicle from that vehicle's
  own drivetrain, so a gas car and an EV in the same garage list different work and completing
  one says nothing about the other. Titles are numbered ("Car 1: …", "Car 2: …") when you own
  more than one of a kind.
- **Switching a car to electric swaps only that car's tasks**: oil & filter and battery &
  fluids give way to an **EV battery & charging check** and **brake fluid & caliper service**
  (regenerative braking barely wears the pads, but calipers seize from disuse and the fluid
  still ages). Tires and annual service are shared by both drivetrains. A motorcycle swaps oil
  & chain for a battery & belt check. Anything already marked done keeps its date.
- *Tip: the coloured icons at the start of the garage pane are your vehicles and the garage
  door, tinted by how current their upkeep is — one tap opens a card. The plain icons after
  them are the drag tray, which places a new vehicle instead.*

## 3a. The attic

- **The attic is a place you walk into**, reached by the **Attic icon** — in the exterior pane, the
  floor plan's, and every room's, so it's one tap from anywhere in the home. Back returns you where
  you came from. Same pane, same camera presets, same maintenance strip as any room; orbit and
  you're looking down at your own street.
- **Attic or utility closet?** Tap the **bare attic floor** (or use the Home icon's dialog) to
  switch. A **full attic** is your whole footprint — rafters, joists, a framed gable end at the far
  wall, ductwork, and insulation batts either side of a boarded walkway you can stand on (and tap).
  A **utility closet** is a small cupboard holding just the air handler. Houses start as the first
  and condos as the second; change either any time. Your roof still decides whether the space is
  pitched, so a condo's full attic is flat-ceilinged.
- **Choosing a closet never removes a task.** It says what the *space* is, not what your home has.
  What your home has is the **row of icons in the pane** — HVAC, Ductwork, Insulation — tap one off
  and it leaves your To-Do list, the Inventory tab and the attic itself; tap it back on and it all
  returns, including anything you'd already marked done. Same toggles as the hub's Inventory tab.
- **Tap the floor hatch** to close or reopen it. **Tap any item** — air handler, a duct run, a batt
  — to open its maintenance card.
- **Stash keepsakes up there**: long-press **Treasure** in the tray and drag it in, like furnishing
  a room. It's tracked under Custom in the hub's Inventory, and a floor-plan edit downstairs can't
  delete it.

- **Your things aren't deleted when the house changes.** Remove a room, shrink the floor plan, or
  claim a different model, and anything in it that was yours — a keepsake, or anything with an
  install year or a document attached — moves to the garage if a garage can hold it, and to the
  attic if not. It keeps its maintenance history; only ordinary furniture goes with its room.

## 4. Building and furnishing the 3D home

- **Exterior pane**: long-press a tray icon — garage, pool, deck, HVAC, solar panel, EV
  battery, tree, gazebo — and drag it onto the yard to place it (solar panels only while the
  camera's Angle is Top, for an unobstructed view of the roof). Tap anything you've placed to
  Remove it or Track its maintenance (HVAC/solar/EV battery jump straight to their own card;
  everything else opens the Hub); a pool can also be rotated to the next side. The deck is
  always the full width of the back wall. Tap the front door to go inside.
- **Floor plan**: tap a room to step into it (works even when the tap lands on furniture);
  long-press a room for options (enter / change type / remove). Toggle the **pencil** for all
  editing — wall modes (Solid/Open/Door/Window), selecting empty tiles to Create Room, and
  adding/removing columns, rows, and floors. The gear is camera-only.
- **Room view**: the tray offers the pieces that suit *this* room — a kitchen its counters and
  appliances, a bathroom its fixtures — plus the sensible crossovers (a garage fridge, a
  bathroom washer). Long-press an icon and drag it in; tap placed furniture for
  rotate/flip/remove; long-press-drag to move it; drag it out through a wall to remove.
  Positions persist automatically.
  - Each icon shows a **×N count** of what the room holds and **dims when the room is full**.
    The cap is floor space, not a fixed number: a small bedroom takes one bed where a large one
    takes three. The first of anything always fits, however tight the room.
  - **All items** at the end of the tray reveals everything the filter left out, so nothing is
    ever out of reach.
  - A room's **walls are see-through**, and the **rest of the storey is drawn around it** — every
    other room at its true position with knee-high walls and ghosted furniture — with your street,
    the park and the model homes beyond the home's boundary. Orbiting shows where you're standing;
    from upstairs you look down on the neighborhood. All of it is scenery: it can't be tapped or
    edited, and furniture dropped over any of it still lands in the room you're in. Step into
    another room from the floor plan as usual.
  - *Tip: the coloured icons above the tray are a different thing entirely — they open
    maintenance cards (see §2). The tray's room-coloured icons place and remove furniture.*

## 5. Camera

| Gesture | Action |
|---|---|
| 1-finger drag | Orbit (or pan, in Map mode) |
| 2-finger pinch | Zoom |
| 2-finger drag | Pan |
| Press and hold an object, then drag | Move it |

Moving something is always a *hold, then drag* — a short buzz tells you it's picked up, and it
follows your finger from there. Dragging without holding first just looks around instead, so you
can never nudge the furniture by accident while turning the camera.

Every scene's **gear** has its own settings (Orbit/Pan mode, gesture toggles, sensitivity,
Reset view) and its own **angle presets**, all remembered per scene:

- **Side** — the oblique 3D overview. Every scene has one.
- **Top** — straight down, floor-plan style. Best for placing things accurately.
- **Eye** — standing inside at eye level. Room and garage scenes only.

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
