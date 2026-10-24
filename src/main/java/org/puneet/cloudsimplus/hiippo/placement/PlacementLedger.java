package org.puneet.cloudsimplus.hiippo.placement;

import org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec;

/** Integer reservations; subtraction avoids overflow when checking a new request. */
public final class PlacementLedger {
    private final ScenarioSpec.Inputs inputs;
    private final long[][] used;
    private final boolean[] assigned;
    public PlacementLedger(ScenarioSpec.Inputs inputs) {
        this.inputs=inputs; used=new long[inputs.hosts().size()][5]; assigned=new boolean[inputs.vms().size()];
    }
    private long[] capacity(int host) {
        var h=inputs.hosts().get(host);
        return new long[]{h.pes(),h.totalMips(),h.ramMiB(),h.bwMbps(),h.storageMiB()};
    }
    private long[] demand(int vm) {
        var v=inputs.vms().get(vm);
        return new long[]{v.pes(),v.totalMips(),v.ramMiB(),v.bwMbps(),v.storageMiB()};
    }
    public boolean canPlace(int vm,int host) {
        if(vm<0 || vm>=assigned.length || host<0 || host>=used.length) throw new IllegalArgumentException("Unknown VM or host");
        if(assigned[vm] || inputs.vms().get(vm).mipsPerPe()>inputs.hosts().get(host).mipsPerPe()) return false;
        var cap=capacity(host); var request=demand(vm);
        for(int d=0;d<5;d++) if(request[d]>cap[d]-used[host][d]) return false;
        return true;
    }
    public void place(int vm,int host) {
        if(!canPlace(vm,host)) throw new IllegalArgumentException("Infeasible or duplicate VM "+vm+" on host "+host);
        var request=demand(vm); for(int d=0;d<5;d++) used[host][d]+=request[d];
        assigned[vm]=true;
    }
    public double residualAfter(int vm,int host) {
        if(!canPlace(vm,host)) throw new IllegalArgumentException("Infeasible placement");
        var cap=capacity(host); var request=demand(vm); double sum=0;
        for(int d=0;d<5;d++) sum+=(double)(cap[d]-used[host][d]-request[d])/cap[d];
        return sum/5;
    }
    public double incrementalWatts(int vm,int host) {
        if(!canPlace(vm,host)) throw new IllegalArgumentException("Infeasible placement");
        var h=inputs.hosts().get(host);
        return (used[host][1]==0?h.idleW():0)+(h.maxW()-h.idleW())*(inputs.vms().get(vm).totalMips()/(double)h.totalMips());
    }
}
