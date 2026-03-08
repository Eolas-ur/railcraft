# Investigation Log

## Coke Oven / Mekanism Interop Investigation

### Files Inspected

| File | Purpose |
|---|---|
| `CokeOvenBlockEntity.java` | Capability provider, multiblock pattern, `getFluidCap()` |
| `CokeOvenModule.java` | Tank creation (`disableFill`), recipe processing, `getTank()` |
| `StandardTank.java` | `drain()` / `fill()` overrides, `disableFill`/`disableDrain` flags, `internalFill()` bypass |
| `MultiblockBlockEntity.java` | `getMembership()`, `getMasterBlockEntity()`, `setMembership()`, `evaluate()` |
| `Railcraft.java` | `handleRegisterCapabilities()`, `FluidHandler.BLOCK` registration for `COKE_OVEN` |
| `FluidLoaderBlockEntity.java` | `upkeep()`, `PULL_FROM` directions, `processCart()` |
| `FluidManipulatorBlockEntity.java` | `tankManager`, `tank`, base upkeep logic |
| `FluidTools.java` | `findNeighbors()`, `direction.getOpposite()` convention |
| `TankManager.java` | `TANK_FILTER`, `pull()`, `FluidUtil.tryFluidTransfer` usage |
| `TankBlockEntity.java` | `membershipChanged()` with `invalidateCapabilities()` (reference) |
| `ValveFluidHandler.java` | Height-based drain/fill gating (reference) |

### Behaviours Confirmed

1. `FluidHandler.BLOCK` capability IS registered for `COKE_OVEN` block entity type.
2. ALL 26 blocks of the formed multiblock expose the handler (no position filter).
3. The handler IS proxied through the multiblock master via `getMasterBlockEntity()`.
4. `getFluidCap()` IGNORES the `side` parameter — all faces return the same handler.
5. The returned `StandardTank` has `disableFill = true` and `disableDrain = false`.
6. `drain(int, SIMULATE)` returns creosote without modifying state.
7. `drain(int, EXECUTE)` returns creosote and reduces stored amount.
8. `drain(FluidStack, SIMULATE/EXECUTE)` both work correctly for matching fluids.
9. The Fluid Loader uses `level.getCapability(FluidHandler.BLOCK, pos, dir.getOpposite())` — same API as Mekanism.
10. The Fluid Loader queries fresh every tick (no caching).
11. The Fluid Loader uses `FluidUtil.tryFluidTransfer` which internally does simulate-then-execute.
12. `membershipChanged()` now calls `level.invalidateCapabilities()` (BUG-0009 fix).

### Discrepancies Discovered

1. Pre-BUG-0009: `membershipChanged()` did NOT call `invalidateCapabilities()`.
   For 'B' blocks (22 of 26: 9 top + 9 bottom + 4 middle corners), the block
   state did not change during formation (WINDOW was already `false`), so
   NeoForge's implicit cache invalidation never fired. This left any `BlockCapabilityCache` (used by Mekanism) holding
   stale `null`.

2. The Fluid Loader avoids this entirely because it calls
   `level.getCapability()` fresh every tick — no `BlockCapabilityCache`
   involved.

3. Post-BUG-0009: `invalidateCapabilities()` is called unconditionally in
   `membershipChanged()`. This should invalidate any external cache. However,
   the fix has NOT been tested against a live Mekanism installation.

### Does the Coke Oven Implementation Match Mekanism Expectations?

**Yes, with the BUG-0009 fix applied.** The implementation:

- Exposes `FluidHandler.BLOCK` on all blocks, all faces.
- Returns a non-null `IFluidHandler` when the multiblock is formed.
- `drain(SIMULATE)` returns the correct fluid and amount.
- `drain(EXECUTE)` removes the fluid.
- `fill()` returns 0 (drain-only, consistent with Mekanism not pushing in
  NORMAL mode).
- `invalidateCapabilities()` is called on formation/disbanding to refresh
  external caches.

The remaining risk is timing: if Mekanism's `BlockCapabilityCache` is created
during chunk load before the Coke Oven's `evaluate()` cycle runs, the cache
may miss the invalidation event. This would require Mekanism-side analysis.

---

## External Fluid Handler Wrapper Implementation

### What Was Changed

| File | Change |
|---|---|
| `CokeOvenFluidHandler.java` (new) | Drain-only `IFluidHandler` wrapper delegating to master's `StandardTank` |
| `CokeOvenBlockEntity.java` | Added `fluidHandler` field; wrapper created in `membershipChanged()` on formation, nulled on disband; `getFluidCap()` returns the stored wrapper |

### Why It Was Changed

The raw `StandardTank` was previously exposed directly to external automation
via the `FluidHandler.BLOCK` capability. While functionally correct (fill was
blocked by the `disableFill` flag), this exposed the internal tank object
directly to external mods, which is fragile:

- External mods cannot inspect the `disableFill` flag; they see `fill()` return
  0 without a clear contract reason.
- The raw tank is shared with internal recipe processing (`internalFill()`),
  creating unnecessary coupling.
- No clean extension point existed for future output-side logic.

The wrapper makes the drain-only contract explicit at the API boundary and
follows the existing `ValveFluidHandler` pattern used by Railcraft's tank
blocks.

### Was Gameplay Behaviour Altered Intentionally?

**No.** The drain and query behaviour is identical — the wrapper delegates all
drain/query methods to the same `StandardTank`. The only behavioural change is
that `fill()` is rejected by the wrapper itself rather than by the tank's
internal `disableFill` flag. The external result is the same: `fill()` returns 0.

### Classification

**Compatibility-focused refactor**, not a behavioural fix. The external API
contract is unchanged (drain-only, same tank contents, same simulate/execute
semantics). The internal implementation boundary is cleaner.
