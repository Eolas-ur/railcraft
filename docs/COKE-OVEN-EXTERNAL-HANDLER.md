# Coke Oven External Fluid Handler

## Purpose

Replace the Coke Oven's direct external exposure of its raw `StandardTank` with
a dedicated drain-only wrapper (`CokeOvenFluidHandler`), improving compatibility
with standard NeoForge fluid automation including Mekanism Mechanical Pipes.

## Problem Statement

The Coke Oven previously exposed its internal `StandardTank` directly via the
`FluidHandler.BLOCK` capability. While the `StandardTank` itself is configured
with `disableFill()`, exposing the raw tank to external automation is fragile:

- External mods see a handler that reports `isFluidValid() == true` and a
  non-zero capacity, but `fill()` silently returns 0 due to the `disableFill`
  flag. This is confusing at the API contract level.
- A dedicated wrapper makes the drain-only intent explicit at the interface
  boundary, independent of the internal tank's configuration.
- The wrapper provides a clean extension point for future output-side logic
  (rate limiting, filtering, etc.) without modifying the shared `StandardTank`.

## Existing External Fluid Exposure Path (Before)

```
External mod
  → level.getCapability(FluidHandler.BLOCK, pos, side)
  → CokeOvenBlockEntity.getFluidCap(side)
  → getMasterBlockEntity() → CokeOvenModule.getTank()
  → raw StandardTank (disableFill=true, disableDrain=false)
```

Each call to `getFluidCap()` resolved the master dynamically via `Optional`
chaining and returned the raw `StandardTank`.

## New Handler Design (After)

```
External mod
  → level.getCapability(FluidHandler.BLOCK, pos, side)
  → CokeOvenBlockEntity.getFluidCap(side)
  → this.fluidHandler (CokeOvenFluidHandler instance, or null if unformed)
  → delegates drain/query to master's StandardTank
  → fill() always returns 0
```

### CokeOvenFluidHandler

- Implements `IFluidHandler` (same interface used by `ValveFluidHandler`)
- Created once during multiblock formation in `membershipChanged()`
- Stored as a field on each member block entity
- Set to `null` when the multiblock disbands
- Holds a reference to the master `CokeOvenBlockEntity`, resolves the tank
  through `master.getCokeOvenModule().getTank()` on each delegate call
- `fill()` unconditionally returns 0
- `drain(FluidStack, FluidAction)` and `drain(int, FluidAction)` delegate to
  the master's tank
- `getTanks()`, `getFluidInTank()`, `getTankCapacity()`, `isFluidValid()`
  delegate to the master's tank for accurate reporting

### Why This Follows Existing Patterns

The `ValveFluidHandler` in `tank/` uses the same architecture:

- Implements `IFluidHandler`
- Holds references to the block entity and master
- Delegates to the master's tank via `getDelegate()`
- Gates `fill()`/`drain()` based on position

`CokeOvenFluidHandler` simplifies this: no position-based gating, just
unconditional drain-only.

## Why This Preserves Fluid Loader Compatibility

The Railcraft Fluid Loader (`FluidLoaderBlockEntity`) extracts fluid through:

1. `FluidTools.findNeighbors()` → `level.getCapability(FluidHandler.BLOCK, ...)`
2. `FluidUtil.tryFluidTransfer()` → `drain(SIMULATE)` then `drain(EXECUTE)`

The wrapper satisfies this contract identically to the raw tank:

- `drain(SIMULATE)` returns the same `FluidStack` (delegates to same tank)
- `drain(EXECUTE)` removes the same amount (delegates to same tank)
- `fill()` returning 0 is irrelevant — the Fluid Loader never fills the source

No Fluid Loader changes are needed.

## Why This Should Improve Standard Mod Interoperability

1. **Stable handler identity**: The wrapper is created once at formation and
   returned for all capability queries. Mods that cache the handler object
   (via `BlockCapabilityCache`) get a consistent reference.

2. **Clean fill rejection**: `fill()` returns 0 unconditionally from the
   wrapper itself, rather than relying on an internal flag inside `StandardTank`
   that the external mod cannot inspect.

3. **Lifecycle correctness**: The wrapper is nulled on disband and recreated on
   formation, aligned with `invalidateCapabilities()` calls. This ensures
   external caches are invalidated and re-query gets the correct state.

4. **No side-channel coupling**: External mods interact only with the wrapper's
   `IFluidHandler` contract. Internal tank behaviour (recipe output via
   `internalFill()`, capacity changes, etc.) remains encapsulated.

## Classes/Files Changed

| File | Change |
|---|---|
| `CokeOvenFluidHandler.java` (new) | Drain-only `IFluidHandler` wrapper |
| `CokeOvenBlockEntity.java` | Added `fluidHandler` field; create wrapper in `membershipChanged()`; return wrapper from `getFluidCap()` |

## Risks and Limitations

- **Not tested against live Mekanism**: The wrapper addresses the API contract
  correctly, but live gameplay verification with Mekanism Mechanical Pipes is
  still required.
- **Master reference lifecycle**: The wrapper holds a reference to the master
  block entity. If the master is unloaded while a member's wrapper is still
  cached externally, the delegate call could NPE. This is mitigated by
  `invalidateCapabilities()` on disband, which should clear external caches.
- **No rate limiting**: The wrapper does not throttle drain speed. If rate
  limiting is needed in the future, this is the correct place to add it.

## Recommended Gameplay Verification Steps

1. Build a 3×3×3 Coke Oven and let it produce creosote from coal/charcoal.
2. Attach a Railcraft Fluid Loader to any face → verify creosote is extracted
   into an adjacent tank. (Regression test for existing behaviour.)
3. Attach a Mekanism Mechanical Pipe in PULL mode to any face → verify
   creosote is extracted into a connected tank.
4. Test both pipes attached before AND after the Coke Oven forms.
5. Break and reform the Coke Oven with pipes attached → verify extraction
   resumes after reformation.
6. Attempt to pump fluid INTO the Coke Oven from an external source → verify
   fill is rejected (0 returned).
