package org.puneet.cloudsimplus.hiippo.scenario;

import java.util.*;

/** Necessary-condition proof, constructive witness, or an explicit unproved gap between them. */
public final class Feasibility {
    private Feasibility() {}
    /** {@code kind} is CONSTRUCTIBLE, UNPROVED or INFEASIBLE_PROVED; {@code reason} is null only when constructible. */
    public record Result(String kind,String reason) {}
    private static final List<String> DIMENSIONS=List.of("pes","mips","ram_mib","bw_mbps","storage_mib");

    public static Result classify(ScenarioSpec.Inputs inputs,int replication) {
        long[] demand=demand(inputs), capacity=capacity(inputs);
        for(int dimension=0;dimension<DIMENSIONS.size();dimension++) if(demand[dimension]>capacity[dimension])
            return new Result("INFEASIBLE_PROVED","replication "+replication+" total "+DIMENSIONS.get(dimension)
                +" demand "+demand[dimension]+" exceeds total host "+DIMENSIONS.get(dimension)+" capacity "+capacity[dimension]);
        long largest=0; for(var host:inputs.hosts()) largest=Math.max(largest,host.mipsPerPe());
        for(var vm:inputs.vms()) if(vm.mipsPerPe()>largest)
            return new Result("INFEASIBLE_PROVED","replication "+replication+" total mips_per_pe demand "+vm.mipsPerPe()
                +" exceeds total host mips_per_pe capacity "+largest);
        var failure=Witness.failure(inputs);
        if(failure!=null) return new Result("UNPROVED","replication "+replication+" witness placement rejected: host "
            +failure.host()+" "+failure.dimension()+" demand "+failure.demand()+" exceeds capacity "+failure.capacity());
        return new Result("CONSTRUCTIBLE",null);
    }
    private static long[] demand(ScenarioSpec.Inputs inputs) {
        long[] totals=new long[5];
        for(var vm:inputs.vms()) {
            totals[0]=sum(totals[0],vm.pes()); totals[1]=sum(totals[1],vm.totalMips());
            totals[2]=sum(totals[2],vm.ramMiB()); totals[3]=sum(totals[3],vm.bwMbps());
            totals[4]=sum(totals[4],vm.storageMiB());
        }
        return totals;
    }
    private static long[] capacity(ScenarioSpec.Inputs inputs) {
        long[] totals=new long[5];
        for(var host:inputs.hosts()) {
            totals[0]=sum(totals[0],host.pes()); totals[1]=sum(totals[1],host.totalMips());
            totals[2]=sum(totals[2],host.ramMiB()); totals[3]=sum(totals[3],host.bwMbps());
            totals[4]=sum(totals[4],host.storageMiB());
        }
        return totals;
    }
    /** Saturating addition keeps the necessary-condition proof total for any declared configuration. */
    private static long sum(long total,long value) {
        return total>Long.MAX_VALUE-value?Long.MAX_VALUE:total+value;
    }
}
