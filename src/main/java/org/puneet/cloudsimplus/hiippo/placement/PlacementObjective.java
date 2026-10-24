package org.puneet.cloudsimplus.hiippo.placement;

import org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec;

/** Protocol steady power estimate in watts, never simulated energy. */
public final class PlacementObjective {
    private PlacementObjective() {}
    public static double watts(ScenarioSpec.Inputs inputs,PlacementPlan plan) {
        plan.validate(inputs);
        long[] mips=new long[inputs.hosts().size()];
        for(var vm:inputs.vms()) mips[plan.hostIds().get(vm.id())]+=vm.totalMips();
        double total=0;
        for(var h:inputs.hosts()) if(mips[h.id()]>0)
            total+=h.idleW()+(h.maxW()-h.idleW())*(mips[h.id()]/(double)h.totalMips());
        return total;
    }
}
