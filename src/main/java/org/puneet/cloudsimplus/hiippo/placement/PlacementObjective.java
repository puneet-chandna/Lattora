package org.puneet.cloudsimplus.hiippo.placement;

import org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec;

/**
 * Placement-time predictions only. Power is steady reserved watts (never simulated energy);
 * utilization is the largest reserved MIPS fraction on any host (never observed utilization).
 * Both are available from the configuration and the plan alone, so search never simulates.
 */
public final class PlacementObjective {
    private PlacementObjective() {}
    /** Dimensionless components: power in [0,1], reserved peak utilization in (0,1], score in [0,1]. */
    public record Score(double powerW,double reservedPeakUtilization,double normalizedPower,
                        double normalizedUtilization,double objective) {}
    /** Protocol v2 steady power estimate in watts, never simulated energy. */
    public static double watts(ScenarioSpec.Inputs inputs,PlacementPlan plan) {
        return score(inputs,plan,Objective.power()).powerW();
    }
    public static Score score(ScenarioSpec.Inputs inputs,PlacementPlan plan,Objective objective) {
        plan.validate(inputs);
        long[] reserved=new long[inputs.hosts().size()];
        for(var vm:inputs.vms()) reserved[plan.hostIds().get(vm.id())]+=vm.totalMips();
        double total=0,peak=0;
        for(var host:inputs.hosts()) if(reserved[host.id()]>0) {
            double fraction=reserved[host.id()]/(double)host.totalMips();
            peak=Math.max(peak,fraction);
            total+=host.idleW()+(host.maxW()-host.idleW())*fraction;
        }
        long normalizer=normalizerWatts(inputs);
        double normalizedPower=total/(double)normalizer;
        double objectiveScore=objective.powerWeight()*normalizedPower+objective.utilizationWeight()*peak;
        return new Score(total,peak,normalizedPower,peak,objectiveScore);
    }
    /** Upper bound on predicted steady power; shared by the recorded normalization and the search score. */
    public static long normalizerWatts(ScenarioSpec.Inputs inputs) {
        long total=0;
        for(var host:inputs.hosts()) total=Math.addExact(total,host.maxW());
        return total;
    }
    /**
     * Deterministic repair fallback: the incremental objective contribution of admitting
     * {@code vm} on {@code host}. It is a placement heuristic only, never simulated evidence.
     */
    public static double incremental(ScenarioSpec.Inputs inputs,PlacementLedger ledger,int vm,int host,Objective objective) {
        double power=ledger.incrementalWatts(vm,host)/(double)normalizerWatts(inputs);
        double utilization=ledger.reservedFractionAfter(vm,host);
        return objective.powerWeight()*power+objective.utilizationWeight()*utilization;
    }
}
