# Coke Oven / Mekanism Mechanical Pipe Interop Investigation

## Purpose

Determine from source code why Mekanism Mechanical Pipes cannot extract
creosote from a formed Railcraft Coke Oven, while the Railcraft Fluid Loader
can. Identify the exact divergence point.

## Confirmed Mekanism Extraction Contract

From the separate Mekanism audit:

1. Pipes discover neighbours via `level.getCapability(FluidHandler.BLOCK, pos, direction)`.
2. The `direction` is the face of the TARGET block that touches the pipe.
3. Pipes use `BlockCapabilityCache` to cache the resolved handler.
4. In PULL mode, the pipe calls `drain(maxAmount, SIMULATE)` every tick.
5. If SIMULATE returns > 0, it calls `drain(maxAmount, EXECUTE)`.
6. If SIMULATE returns 0, no EXECUTE call is made.

## Railcraft Coke Oven Implementation

### Capability Registration

File: `src/main/java/mods/railcraft/Railcraft.java`, lines 220-222.

```java
event.registerBlockEntity(Capabilities.FluidHandler.BLOCK,
    RailcraftBlockEntityTypes.COKE_OVEN.get(), CokeOvenBlockEntity::getFluidCap);
```

Registered for the `COKE_OVEN` block entity type. Every block in the 3x3x3
multiblock is this type, so ALL 26 member blocks are eligible providers.

### Handler Resolution

File: `src/main/java/mods/railcraft/world/level/block/entity/CokeOvenBlockEntity.java`,
lines 119-125.

```java
public IFluidHandler getFluidCap(@Nullable Direction side) {
    var masterModule = this.getMasterBlockEntity()
        .map(CokeOvenBlockEntity::getCokeOvenModule);
    return masterModule
        .map(CokeOvenModule::getTank)
        .orElse(null);
}
```

- The `side` parameter is IGNORED. All faces return the same handler.
- `getMasterBlockEntity()` returns `Optional.empty()` if:
  - The multiblock is not formed (`this.membership == null`), OR
  - The call is on the client side (`this.level.isClientSide()` returns true).
- When formed and on the server, ALL 26 members resolve to the master's
  `CokeOvenModule.getTank()`.
- There is NO block position filtering, NO side filtering, and NO layer
  restriction.

### Tank Drain Behaviour

File: `src/main/java/mods/railcraft/world/level/material/StandardTank.java`.

The Coke Oven tank is created with:

```java
StandardTank.ofBuckets(64).disableFill().changeCallback(this::setChanged);
```

- `disableFill = true`: `fill()` returns 0. External mods cannot push fluid in.
- `disableDrain = false` (default): `drain()` delegates to `FluidTank.drain()`.

Both drain overloads:

```java
public FluidStack drain(FluidStack resource, FluidAction action) {
    return this.disableDrain ? FluidStack.EMPTY : super.drain(resource, action);
}

public FluidStack drain(int maxDrain, FluidAction action) {
    return this.disableDrain ? FluidStack.EMPTY : super.drain(maxDrain, action);
}
```

`FluidTank.drain(int, FluidAction)` (NeoForge base class):

- Returns a copy of the contained fluid, capped at `maxDrain`.
- SIMULATE: returns the FluidStack without modifying the tank.
- EXECUTE: returns the FluidStack and reduces the stored amount.
- Returns empty if the tank is empty.

There is NO difference in behaviour between SIMULATE and EXECUTE beyond state
mutation. Both return the same FluidStack.

### Multiblock Membership

File: `src/main/java/mods/railcraft/world/level/block/entity/multiblock/MultiblockBlockEntity.java`.

```java
public Optional<Membership<T>> getMembership() {
    return this.level.isClientSide() ? Optional.empty()
        : Optional.ofNullable(this.membership);
}

public Optional<T> getMasterBlockEntity() {
    return this.getMembership().map(Membership::master);
}
```

- Server-side only. Client returns empty.
- `this.membership` is set in `setMembership()` BEFORE `membershipChanged()`.
- All members of a formed multiblock have a non-null `membership` pointing to
  the master.

### Capability Cache Invalidation (BUG-0009 Fix)

File: `CokeOvenBlockEntity.java`, `membershipChanged()`.

```java
protected void membershipChanged(@Nullable Membership<CokeOvenBlockEntity> membership) {
    // ... block state updates ...
    this.level.invalidateCapabilities(this.getBlockPos());
}
```

Added in BUG-0009. Called for both formation (membership != null) and disbanding
(membership == null). Forces any `BlockCapabilityCache` held by external mods to
re-query on the next access.

## Railcraft Fluid Loader Extraction Implementation

### Extraction Path

File: `src/main/java/mods/railcraft/world/level/block/entity/manipulator/FluidLoaderBlockEntity.java`.

```java
protected void upkeep() {
    super.upkeep();
    this.tankManager.pull(FluidTools.findNeighbors(this.level, this.getBlockPos(),
        Predicates.notOfType(FluidLoaderBlockEntity.class), PULL_FROM), 0, TRANSFER_RATE);
}
```

- `PULL_FROM` = all directions except DOWN (UP, N, S, E, W).
- Called every server tick via `ManipulatorBlockEntity.serverTick()`.

### Neighbour Discovery

File: `src/main/java/mods/railcraft/util/fluids/FluidTools.java`, lines 221-242.

```java
public static Collection<IFluidHandler> findNeighbors(Level level, BlockPos centrePos,
    Predicate<BlockEntity> filter, Direction... directions) {
    List<IFluidHandler> targets = new ArrayList<>();
    for (var direction : directions) {
        var blockEntity = level.getBlockEntity(centrePos.relative(direction));
        if (blockEntity == null) continue;
        if (!TankManager.TANK_FILTER.apply(blockEntity, direction.getOpposite())) continue;
        if (!filter.test(blockEntity)) continue;
        var cap = level.getCapability(Capabilities.FluidHandler.BLOCK,
            blockEntity.getBlockPos(), direction.getOpposite());
        if (cap != null) targets.add(cap);
    }
    return targets;
}
```

- Uses `direction.getOpposite()`: queries the face of the NEIGHBOUR that
  touches the Fluid Loader. Same convention as Mekanism.
- `TANK_FILTER`: pre-check that also calls
  `level.getCapability(FluidHandler.BLOCK, pos, dir) != null`.
- Calls `level.getCapability()` FRESH every tick. No caching.

### Fluid Transfer

File: `src/main/java/mods/railcraft/world/level/material/TankManager.java`, line 130.

```java
public void pull(Collection<IFluidHandler> targets, int tankIndex, int amount) {
    this.transfer(targets, tankIndex,
        (me, them) -> FluidUtil.tryFluidTransfer(me, them, amount, true));
}
```

`FluidUtil.tryFluidTransfer(destination, source, maxAmount, true)` internally:

1. Calls `source.drain(maxAmount, SIMULATE)` to determine available fluid.
2. Calls `destination.fill(simulated, SIMULATE)` to determine acceptable amount.
3. Calls `source.drain(fillable, EXECUTE)` to remove fluid.
4. Calls `destination.fill(drained, EXECUTE)` to add fluid.

This is the standard NeoForge simulate-then-execute pattern.

## Contract Comparison

| Aspect | Mekanism Pipe | Railcraft Fluid Loader |
|---|---|---|
| Capability API | `FluidHandler.BLOCK` | `FluidHandler.BLOCK` |
| Direction convention | Face of target block | Face of target block (`direction.getOpposite()`) |
| Handler discovery | `level.getCapability()` | `level.getCapability()` |
| Caching | `BlockCapabilityCache` (persistent) | None (fresh query every tick) |
| Drain pattern | `drain(SIMULATE)` then `drain(EXECUTE)` | `FluidUtil.tryFluidTransfer` (same internally) |
| Drain overload | `drain(int, FluidAction)` | `drain(int, FluidAction)` via FluidUtil |
| Side queried | Face touching pipe | Face touching loader (`direction.getOpposite()`) |

Both paths use the **identical NeoForge API** and the **identical drain
semantics**. The ONLY structural difference is caching.

## Failure Candidates Ranked by Likelihood

### 1. Stale BlockCapabilityCache (BUG-0009) — HIGH

**Pre-fix**: `membershipChanged()` did not call `invalidateCapabilities()`.
For 'B' blocks (22 of 26: 9 top + 9 bottom + 4 middle corners), block state
changes were no-ops (`WINDOW` already `false`), so NeoForge's implicit cache
invalidation never fired.

- If a pipe was placed before multiblock formation, its cache stored `null` and
  was never invalidated.
- If a pipe was placed after formation BUT the chunk was previously loaded with
  the un-formed oven, the cache may still hold stale data.

**Post-fix**: `invalidateCapabilities()` is now called on every membership
change. This should resolve the cache issue.

**Status**: Fix implemented. Not verified against live Mekanism.

### 2. Mekanism BlockCapabilityCache Listener Timing — LOW-MEDIUM

Mekanism may initialise its `BlockCapabilityCache` when the pipe block entity
loads, not when the pipe face is configured to PULL. If the cache is created
before the Coke Oven forms, and `invalidateCapabilities()` fires only during
`membershipChanged()`, the pipe may miss the invalidation if its cache was
created during chunk load before the oven's evaluate cycle runs.

This is a timing/ordering concern, not a Railcraft code defect. No evidence
from Railcraft code supports or refutes this hypothesis.

**Status**: Speculative. Requires Mekanism cache lifecycle analysis to confirm
or rule out.

### 3. Mekanism Side Filtering — LOW

Mekanism may apply additional filtering based on the `side` parameter or the
handler's `getTanks()` / `getFluidInTank()` results before attempting drain.
The Railcraft handler ignores `side` and reports the full tank contents. This
should satisfy any reasonable filter.

**Status**: Unlikely. Would need Mekanism source confirmation.

### 4. Handler Identity / Equality Check — LOW

Mekanism may perform identity checks on the returned `IFluidHandler` across
ticks. The Coke Oven returns the SAME `StandardTank` instance (the master's
tank) for all blocks and all queries. This should be stable.

**Status**: No evidence of failure.

## Explicit Answers

### Should a Mekanism pipe connected to a bottom Coke Oven brick in PULL mode work?

**Yes.** From Railcraft's implementation:

- All 26 blocks of the formed multiblock expose `FluidHandler.BLOCK`.
- `getFluidCap()` ignores the `side` parameter; all faces return the handler.
- The returned `StandardTank` has `disableDrain = false`.
- `drain(int, SIMULATE)` returns creosote if the tank is non-empty.
- `drain(int, EXECUTE)` removes and returns creosote.
- There is no position, layer, or face restriction.

The Railcraft Coke Oven implementation satisfies the Mekanism drain contract.

### If not, why? / What part prevents it?

The most likely cause is **stale capability cache** (BUG-0009). Before the fix,
`membershipChanged()` did not call `invalidateCapabilities()`, so Mekanism's
`BlockCapabilityCache` retained stale `null` values from before multiblock
formation. The Fluid Loader avoids this because it queries fresh every tick.

### Where does behaviour diverge?

**Caching**. The Fluid Loader calls `level.getCapability()` directly each tick
(file: `FluidTools.java:235`). Mekanism pipes use `BlockCapabilityCache`.
Pre-BUG-0009, the cache was never invalidated for 'B' blocks because their
block state didn't change during formation (file:
`CokeOvenBlockEntity.java:membershipChanged()`).

## Evidence Limits

This investigation was conducted from Railcraft source code only. The Mekanism
pipe contract is taken from a separate audit summary provided as input.

The following remain unverified:

- Whether the BUG-0009 fix resolves the issue in practice (requires live
  Mekanism test).
- Whether Mekanism applies additional filters (e.g., fluid type, handler
  identity) beyond the drain contract described above.
- The exact lifecycle and invalidation behaviour of Mekanism's
  `BlockCapabilityCache` implementation.

Until live verification is performed, the recommended workaround is the
Railcraft Fluid Loader.

## Coke Oven Classes Responsible for Fluid Exposure

| Class | Role |
|---|---|
| `CokeOvenBlockEntity` | Capability provider, multiblock member, master resolution |
| `CokeOvenModule` | Tank owner, recipe processing, creosote generation |
| `StandardTank` | Fluid storage, drain/fill logic, disable flags |
| `MultiblockBlockEntity` | Membership management, master reference |
| `Railcraft` (mod class) | Capability registration via `RegisterCapabilitiesEvent` |
