package org.puneet.cloudsimplus.hiippo.scenario;

/** Independent constructive feasibility check; never a planner or repair input. */
public final class Witness {
    private Witness() {}
    /** First rejected dimension of the constructive witness, in host then dimension order; null when it constructs. */
    public record Failure(int host,String dimension,long demand,long capacity) {}
    public static void validate(ScenarioSpec.Inputs inputs) {
        var failure=failure(inputs);
        if(failure==null) return;
        if(failure.dimension().equals("per_pe_mips")) throw new IllegalArgumentException("Witness per-PE MIPS exceeded");
        throw new IllegalArgumentException("Witness capacity exceeded on host "+failure.host());
    }
    /** Non-throwing form of {@link #validate}; never a planner or repair input. */
    public static boolean constructs(ScenarioSpec.Inputs inputs) { return failure(inputs)==null; }
    public static Failure failure(ScenarioSpec.Inputs inputs) {
        for(var host:inputs.hosts()) {
            long pes=0,mips=0,ram=0,bw=0,storage=0;
            for(var vm:inputs.vms()) if(vm.id()%inputs.hosts().size()==host.id()) {
                if(vm.mipsPerPe()>host.mipsPerPe()) return new Failure(host.id(),"per_pe_mips",vm.mipsPerPe(),host.mipsPerPe());
                pes=Math.addExact(pes,vm.pes()); mips=Math.addExact(mips,vm.totalMips()); ram=Math.addExact(ram,vm.ramMiB());
                bw=Math.addExact(bw,vm.bwMbps()); storage=Math.addExact(storage,vm.storageMiB());
            }
            if(pes>host.pes()) return new Failure(host.id(),"pes",pes,host.pes());
            if(mips>host.totalMips()) return new Failure(host.id(),"mips",mips,host.totalMips());
            if(ram>host.ramMiB()) return new Failure(host.id(),"ram_mib",ram,host.ramMiB());
            if(bw>host.bwMbps()) return new Failure(host.id(),"bw_mbps",bw,host.bwMbps());
            if(storage>host.storageMiB()) return new Failure(host.id(),"storage_mib",storage,host.storageMiB());
        }
        return null;
    }
    public static void validateNative(ScenarioSpec.Inputs inputs) {
        var objects=ScenarioObjects.create(inputs);
        // Native allocations are cumulative and confined to disposable fresh objects.
        for(var vm:objects.vms()) {
            var host=objects.hosts().get((int)(vm.getId()%objects.hosts().size()));
            if(!host.createVm(vm).fully()) throw new IllegalArgumentException("Native witness rejected VM "+vm.getId());
        }
    }
}
