# HomeBase — Digital Home Twin

A cross-platform (iOS + Android) home maintenance and digital twin app built with Expo + React Native. HomeBase tracks every structural element, appliance, and system in your home, calculates a real-time home health score, and keeps your maintenance calendar current — so you're never blindsided by an expensive surprise.
- **Home illustration** is rendered entirely with React Native `View` + `Animated` — no internet required, no WebView, works on every platform. Reflects wall color, roof color, stories, solar panels, pool, and garage.
- **All user data** lives on-device in AsyncStorage. Nothing leaves the phone unless you explicitly export a CSV.
- **CSV export / import** — tap `↓ CSV` to export all appliance data as a spreadsheet; tap `↑ Import` to load it back (or import a manually prepared CSV). Column order is flexible — the parser matches by header name.
- **Batch date onboarding** — entering your home purchase date in Step 4 stamps all quick-add appliances with that date in one step and pre-fills the purchase form in Step 5.
- **Demo homes** are preloaded — changes you make persist in your local store.
- **Long-press** appliances, contacts, or documents to delete them.
- **Health scores below 40%** trigger an at-risk alert in the appliance inventory.
- **Estimated categories** (marked `~` on the health score card) have no tracked items; add items to get a data-driven score.
- **Health score floors** — Structural & Exterior uses a minimum floor of 50 and Interior uses 55, so aging paint or siding doesn't tank the score the way a failing furnace would. Systems, Appliances, and Safety have no floor.
---

## Features

### Digital Twin Illustration
- Flat-design **animated house illustration** that reflects your home's real attributes — wall color, roof color, stories, solar panels, pool, garage, and more
- Gentle floating animation; fully offline, no internet required
- Pure React Native — no WebView or external dependencies

### Home Health Score
- **Letter grade (A–F)** calculated from a weighted model across 5 categories:
  - **Structural & Exterior** (30%) — roof, siding, windows, doors, garage
  - **Home Systems** (30%) — HVAC, electrical, plumbing infrastructure
  - **Interior** (15%) — paint, walls, flooring
  - **Appliances** (15%) — kitchen, laundry, other
  - **Safety** (10%) — smoke and CO detectors
- Categories without tracked items fall back to an age-based estimate
- Dashboard shows per-category progress bars and data-completeness indicator

### Onboarding Wizard (6 steps)
1. **Address** — street, city, state, ZIP (stored only on device)
2. **Home Details** — type, style, square footage, year built, beds/baths/stories
3. **Features** — solar, pool, garage, basement, attic, deck, fireplace, EV charger, generator, sprinkler, septic, well
4. **Appliances & Systems** — enter your home purchase / move-in date once; all quick-add items inherit that date automatically. Add 32 common items in one tap or open the custom form for model number, serial number, and warranty expiry. Lifetimes auto-populated from the database.
5. **Purchase History** — purchase price, updates at purchase, modifications since, wall and roof color pickers (purchase date pre-filled from Step 4)
6. **Complete** — animated illustration of your home, stats summary, 37 maintenance tasks pre-loaded

### Lifespan Database (42 items)
Track lifetime health for everything in your home:

| Category | Items |
|----------|-------|
| **HVAC** | Central AC, Gas Furnace, Heat Pump |
| **Plumbing (equipment)** | Water Heater (tank & tankless), Sump Pump |
| **Kitchen** | Refrigerator, Dishwasher, Gas/Electric Range, Microwave, Garbage Disposal |
| **Laundry** | Washing Machine, Dryer |
| **Electrical** | Electrical Panel, Solar Panels, Solar Inverter, EV Charger |
| **Safety** | Smoke Detectors, CO Detectors |
| **Exterior (structure)** | Roof (asphalt & metal), Vinyl/Fiber Cement/Wood Siding, Stucco, Brick/Stone Veneer, Exterior Doors (steel & wood), Sliding/Patio Door, Windows, Garage Door, Deck, Exterior Paint |
| **Interior** | Interior Paint, Drywall/Walls, Hardwood Flooring, Carpet, Luxury Vinyl (LVP) |
| **Plumbing (infrastructure)** | Copper Supply Lines, PEX Supply Lines, PVC Drain Lines, Cast Iron Drain Lines |
| **Other** | Pool Pump |

### Maintenance Calendar
- **37 pre-loaded tasks** across 8 frequencies: monthly, quarterly, biannual, annual
  - Monthly: HVAC filter, smoke/CO detector test, garbage disposal, dishwasher filter
  - Quarterly: Refrigerator coils, GFCI outlets, exterior walk-around
  - Biannual: Gutters, HVAC service, roof inspection, siding inspection, supply line check, visible plumbing leak check
  - Annual: Water heater flush, dryer vent, sump pump test, pest inspection, fireplace/chimney, drain snaking, interior wall inspection, paint touch-up, door/window caulk
- Mini calendar with task-dot indicators
- Overdue alert banner on dashboard
- Add custom tasks with recurrence, priority, and cost

### Appliance Inventory
- Health bar per item (green ≥70 · orange ≥40 · red <40)
- Age, brand, model, serial number, warranty expiry
- Category filter (HVAC, Kitchen, Laundry, Plumbing, Electrical, Exterior, Interior, Safety, Other)
- Long-press to delete; edit install date after a repair
- At-risk alert when any item drops below 40%
- **Export CSV** — tap `↓ CSV` to share a spreadsheet of all appliances (name, category, brand, model, serial number, purchase date, warranty expiry, expected lifetime, health %, age, notes) via email, Files, or cloud storage
- **Import CSV** — tap `↑ Import` to load a previously exported (or manually prepared) CSV back into the app; header order is flexible, rows with missing Name or invalid Category are skipped, and a summary is shown

### Problem Advisor
- 30+ DIY repair guides across 8 categories: Electrical, Plumbing, HVAC, Roofing, Foundation, Appliances, Exterior, Water Damage
- Each guide includes: warning signs, step-by-step DIY checklist, cost estimate (DIY vs pro), time to fix, when to call a pro, prevention tips, and safety warnings
- DIY level badges: Easy / Moderate / Hard / Pro Only
- Tap each step to check it off

### Service Contacts
- Store licensed/insured contractors with tap-to-call, email, website
- Ratings, last-used date, notes
- Type filter: electrician, plumber, HVAC, roofer, inspector, handyman, landscaper, pest control, appliance repair, general contractor

### Document Vault
- Capture via camera, photo library, or file picker (PDF, receipts, warranties, manuals)
- Filter by type: photo, receipt, warranty, manual, permit, inspection
- Long-press to delete

### Multi-Home Support
- Add unlimited homes from the home switcher on the dashboard
- Tap the address in the header → switch homes or **Add New Home** (re-enters the onboarding wizard without losing existing homes)

---

## Demo Homes

Three fully-populated demo homes load instantly from the welcome screen → **Explore Demo Homes**.

| Home | Location | Built | Size | Notable |
|------|----------|-------|------|---------|
| **The Modern Ranch** | Austin, TX | 1998 | 1,850 sqft | Vinyl siding nearing end-of-life, carpet due for replacement, copper pipes past midlife |
| **Solar Paradise** | Phoenix, AZ | 2015 | 2,450 sqft | 8.4 kW SunPower system, heated pool, PEX plumbing, EV charger, stucco exterior |
| **The Classic Colonial** | Boston, MA | 1985 | 3,200 sqft | GAF roof (2018), original cast iron drains, copper pipe repairs, hardwood refinishing due 2026, Generac generator |

Each demo home includes realistic appliances with 2025–2026 service dates, service contacts, and documents. Illustrations reflect each home's wall color, roof color, stories, and features (solar, pool, garage).

---

## Getting Started

### Prerequisites


## Data Model

```typescript
interface Home {
  id, nickname?, address, city, state, zip
  type: 'single-family' | 'condo' | 'townhouse' | 'duplex' | 'mobile'
  style: 'ranch' | 'colonial' | 'contemporary' | 'craftsman' | 'victorian' | 'split-level' | 'cape-cod' | 'other'
  squareFootage, bedrooms, bathrooms, stories, yearBuilt
  purchaseDate, purchasePrice?
  primaryColor?, roofColor?
  features: HomeFeature[]       // solar, pool, garage, deck, etc.
  appliances: Appliance[]       // all tracked items (equipment + structure + interior)
  maintenanceTasks: MaintenanceTask[]
  contacts: ServiceContact[]
  documents: HomeDocument[]
  isDemo?
}

interface Appliance {
  id, name, brand, model
  category: 'hvac' | 'kitchen' | 'laundry' | 'plumbing' | 'electrical'
           | 'exterior' | 'interior' | 'safety' | 'other'
  purchaseDate, installDate?, warrantyExpiry?
  expectedLifetimeYears
  lastMaintenanceDate?, nextMaintenanceDate?
  serialNumber?, notes?
}
```

All data is stored locally using **Zustand + AsyncStorage** — no account, no server, no internet required. The CSV export writes a file to the device cache and opens the OS share sheet.

---

## Home Health Score Algorithm

```
score = Σ (categoryScore × weight) − maintenancePenalty

Category scores (0–100):
  If items exist in category → average adjusted health across items (see below)
  If no items         → age-based estimate: max(20, 100 − (homeAge / defaultLifetime) × 75)

getHealthPercent(purchaseDate, lifetimeYears):    ← raw linear score, used in inventory bars
  age = years since purchaseDate
  return max(0, round((1 − age / lifetimeYears) × 100))

Category health score with floor remapping:
  rawAvg  = average getHealthPercent() across all items in category
  score   = floor + (rawAvg / 100) × (100 − floor)

  Category floors (cosmetic/surface items age gracefully — they don't stop working at EOL):
    Structural & Exterior  floor = 50  → worst-case aged siding/roof still scores 50
    Interior               floor = 55  → worn paint/carpet is cosmetic, not a failure
    Home Systems           floor =  0  → a dead furnace is a real emergency
    Appliances             floor =  0  → a broken dishwasher truly stops working
    Safety                 floor =  0  → an expired smoke detector must be replaced

Weights:
  Structural & Exterior  30%  (exterior category)
  Home Systems           30%  (hvac + electrical + plumbing)
  Interior               15%  (interior category)
  Appliances             15%  (kitchen + laundry + other)
  Safety                 10%  (safety category)

maintenancePenalty = min(20, overdueTaskCount × 2)

Grades:  A ≥ 90  ·  B ≥ 75  ·  C ≥ 60  ·  D ≥ 45  ·  F < 45

