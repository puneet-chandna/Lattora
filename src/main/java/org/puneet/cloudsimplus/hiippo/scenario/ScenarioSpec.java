package org.puneet.cloudsimplus.hiippo.scenario;

import java.util.*;

/** Immutable inputs only; CloudSim objects must never be retained here. */
public record ScenarioSpec(Inputs inputs, List<Double> referenceSeconds) {
    public ScenarioSpec {
        Objects.requireNonNull(inputs);
        referenceSeconds=List.copyOf(referenceSeconds);
        if(referenceSeconds.size()!=inputs.cloudlets().size()) throw new IllegalArgumentException("Reference count mismatch");
        for(double seconds:referenceSeconds)
            if(!Double.isFinite(seconds) || seconds<=0 || !Double.isFinite(10*seconds))
                throw new IllegalArgumentException("Invalid isolated reference");
    }
    public record SeedMetadata(long master,String phase,String scenario,int replication,long scenarioSeed,long workloadSeed) {
        public SeedMetadata {
            boolean legacy=List.of("main","sensitivity").contains(phase) && List.of("Micro","Small","Medium").contains(scenario);
            boolean stress=List.of("stress","stress_calibration").contains(phase) && scenario!=null
                && scenario.matches("Static-V[1-9][0-9]*-H[1-9][0-9]*");
            boolean configured=phase!=null && phase.equals("scenario") && scenario!=null
                && scenario.matches("[a-z][a-z0-9._-]{0,63}");
            if((!legacy && !stress && !configured) || replication<0)
                throw new IllegalArgumentException("Invalid scenario identity");
            if(stress) {
                int separator=scenario.indexOf("-H");
                Integer.parseInt(scenario.substring(8,separator));
                Integer.parseInt(scenario.substring(separator+2));
            }
        }
    }
    public record HostSpec(int id,int pes,long mipsPerPe,long ramMiB,long bwMbps,long storageMiB,int idleW,int maxW) {
        public HostSpec { resources(id,pes,mipsPerPe,ramMiB,bwMbps,storageMiB); if(idleW<0 || maxW<idleW) throw new IllegalArgumentException("Invalid power"); }
        public long totalMips() { return Math.multiplyExact(pes,mipsPerPe); }
    }
    public record VmSpec(int id,int pes,long mipsPerPe,long ramMiB,long bwMbps,long storageMiB) {
        public VmSpec { resources(id,pes,mipsPerPe,ramMiB,bwMbps,storageMiB); }
        public long totalMips() { return Math.multiplyExact(pes,mipsPerPe); }
    }
    public record CloudletSpec(int id,int vmId,int pes,long lengthMi) {
        public CloudletSpec {
            if(id<0 || vmId<0 || pes<=0 || lengthMi<=0) throw new IllegalArgumentException("Invalid cloudlet");
            Math.multiplyExact(pes,lengthMi);
        }
    }
    public record Inputs(SeedMetadata seeds,List<HostSpec> hosts,List<VmSpec> vms,List<CloudletSpec> cloudlets) {
        public Inputs {
            Objects.requireNonNull(seeds);
            hosts=List.copyOf(hosts); vms=List.copyOf(vms); cloudlets=List.copyOf(cloudlets);
            if(hosts.isEmpty() || vms.isEmpty() || cloudlets.size()!=vms.size()) throw new IllegalArgumentException("Invalid input sizes");
            for(int i=0;i<hosts.size();i++) if(hosts.get(i).id()!=i) throw new IllegalArgumentException("Host ID order");
            for(int i=0;i<vms.size();i++) {
                var c=cloudlets.get(i); var v=vms.get(i);
                if(v.id()!=i || c.id()!=i || c.vmId()!=i || c.pes()!=v.pes()) throw new IllegalArgumentException("VM/cloudlet ID or PE mismatch");
            }
        }
    }
    private static void resources(int id,int pes,long mips,long ram,long bw,long storage) {
        if(id<0 || pes<=0 || mips<=0 || ram<=0 || bw<=0 || storage<=0) throw new IllegalArgumentException("Invalid resources");
        Math.multiplyExact(pes,mips);
    }
    public double censorSeconds() { return 10*Collections.max(referenceSeconds); }
    public String canonicalText() {
        var text=new StringBuilder("kind,id,pes,mips_per_pe,ram_mib,bw_mbps,storage_mib,idle_w,max_w,vm_id,length_mi\n");
        for(var h:inputs.hosts()) text.append("host,").append(h.id()).append(',').append(h.pes()).append(',').append(h.mipsPerPe()).append(',').append(h.ramMiB()).append(',').append(h.bwMbps()).append(',').append(h.storageMiB()).append(',').append(h.idleW()).append(',').append(h.maxW()).append(",,\n");
        for(var v:inputs.vms()) text.append("vm,").append(v.id()).append(',').append(v.pes()).append(',').append(v.mipsPerPe()).append(',').append(v.ramMiB()).append(',').append(v.bwMbps()).append(',').append(v.storageMiB()).append(",,,,\n");
        for(var c:inputs.cloudlets()) text.append("cloudlet,").append(c.id()).append(',').append(c.pes()).append(",,,,,,,").append(c.vmId()).append(',').append(c.lengthMi()).append('\n');
        var keys=new TreeMap<String,String>();
        keys.put("cloudlet_scheduler","CloudletSchedulerSpaceShared"); keys.put("host_scheduler","VmSchedulerSpaceShared");
        keys.put("min_event_interval_s",Double.toString(ScenarioObjects.MIN_EVENT_INTERVAL_SECONDS));
        keys.put("power_policy","used_on_until_horizon"); keys.put("power_curve","linear");
        keys.put("release_s","0.0"); keys.put("utilization_model","constant_full"); keys.put("censor_s",Double.toString(censorSeconds()));
        for(int i=0;i<referenceSeconds.size();i++) keys.put("reference."+i,Double.toString(referenceSeconds.get(i)));
        text.append("key,value\n"); keys.forEach((k,v)->text.append(k).append(',').append(v).append('\n'));
        return text.toString();
    }
    public String fingerprint() { return Seeds.hash(canonicalText()); }
}
