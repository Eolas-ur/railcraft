package mods.railcraft.world.level.block.entity;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public class CokeOvenFluidHandler implements IFluidHandler {

  private final CokeOvenBlockEntity master;

  public CokeOvenFluidHandler(CokeOvenBlockEntity master) {
    this.master = master;
  }

  private IFluidHandler getDelegate() {
    return this.master.getCokeOvenModule().getTank();
  }

  @Override
  public int getTanks() {
    return this.getDelegate().getTanks();
  }

  @Override
  public FluidStack getFluidInTank(int tank) {
    return this.getDelegate().getFluidInTank(tank);
  }

  @Override
  public int getTankCapacity(int tank) {
    return this.getDelegate().getTankCapacity(tank);
  }

  @Override
  public boolean isFluidValid(int tank, FluidStack stack) {
    return this.getDelegate().isFluidValid(tank, stack);
  }

  @Override
  public int fill(FluidStack resource, FluidAction action) {
    return 0;
  }

  @Override
  public FluidStack drain(FluidStack resource, FluidAction action) {
    return this.getDelegate().drain(resource, action);
  }

  @Override
  public FluidStack drain(int maxDrain, FluidAction action) {
    return this.getDelegate().drain(maxDrain, action);
  }
}
