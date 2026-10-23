package org.puneet.cloudsimplus.hiippo.scenario;

import java.util.*;
import org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec.*;

public final class ScenarioGenerator {
    private ScenarioGenerator() {}
    public static Inputs inputs(long master,String phase,String name,int replication) {
        int count=switch(name) { case "Micro" -> 10; case "Small" -> 50; case "Medium" -> 100; default -> throw new IllegalArgumentException("Unknown scenario: "+name); };
        int hostCount=name.equals("Micro")?3:count/5;
        return inputs(master,phase,name,replication,count,hostCount);
    }
    private static Inputs inputs(long master,String phase,String name,int replication,int count,int hostCount) {
        var seeds=new SeedMetadata(master,phase,name,replication,Seeds.derive(master,phase,name,replication,"scenario"),Seeds.derive(master,phase,name,replication,"workload"));
        var scenario=new Random(seeds.scenarioSeed()); var workload=new Random(seeds.workloadSeed());
        var hosts=new ArrayList<HostSpec>(); var vms=new ArrayList<VmSpec>(); var cloudlets=new ArrayList<CloudletSpec>();
        for(int i=0;i<hostCount;i++) hosts.add(new HostSpec(i,16,3000,32768,10000,1000000,new int[]{175,210,280}[i%3],new int[]{250,300,400}[i%3]));
        for(int i=0;i<count;i++) {
            int pes=1+scenario.nextInt(2); long mips=1000L*(1+scenario.nextInt(3)); long ram=1024L<<scenario.nextInt(3);
            vms.add(new VmSpec(i,pes,mips,ram,1000,10000));
            cloudlets.add(new CloudletSpec(i,i,pes,Math.multiplyExact(mips,60L+workload.nextInt(61))));
        }
        var inputs=new Inputs(seeds,hosts,vms,cloudlets);
        Witness.validate(inputs);
        return inputs;
    }
    public static ScenarioSpec generate(long master,String phase,String name,int replication) {
        var inputs=inputs(master,phase,name,replication);
        Witness.validateNative(inputs);
        return new ScenarioSpec(inputs,ScenarioObjects.measureReferences(inputs));
    }
    public static ScenarioSpec generateStatic(long master,String phase,String name,int replication,int vmCount,int hostCount) {
        if(vmCount<1 || hostCount<1 || !List.of("stress","stress_calibration").contains(phase)
            || !("Static-V"+vmCount+"-H"+hostCount).equals(name))
            throw new IllegalArgumentException("Invalid static stress identity or dimensions");
        // Share distributions and draw order with the frozen generator; never retry a rejected witness.
        var inputs=inputs(master,phase,name,replication,vmCount,hostCount);
        Witness.validateNative(inputs);
        return new ScenarioSpec(inputs,ScenarioObjects.measureReferences(inputs));
    }
    /** Host classes are emitted verbatim in file order; each VM then draws one value per field in field order. */
    public static Inputs configuredInputs(long master,String phase,String name,int replication,GenerationSpec spec) {
        var seeds=new SeedMetadata(master,phase,name,replication,Seeds.derive(master,phase,name,replication,"scenario"),Seeds.derive(master,phase,name,replication,"workload"));
        var scenario=new Random(seeds.scenarioSeed()); var workload=new Random(seeds.workloadSeed());
        var hosts=new ArrayList<HostSpec>(); var vms=new ArrayList<VmSpec>(); var cloudlets=new ArrayList<CloudletSpec>();
        int hostId=0;
        for(var hostClass:spec.hostClasses()) for(int i=0;i<hostClass.count();i++)
            hosts.add(new HostSpec(hostId++,hostClass.pes(),hostClass.mipsPerPe(),hostClass.ramMiB(),hostClass.bwMbps(),
                hostClass.storageMiB(),hostClass.idleW(),hostClass.maxW()));
        var demands=spec.vms();
        for(int i=0;i<demands.count();i++) {
            int pes=demands.pes().get(scenario.nextInt(demands.pes().size()));
            long mips=demands.mipsPerPe().get(scenario.nextInt(demands.mipsPerPe().size()));
            long ram=demands.ramMiB().get(scenario.nextInt(demands.ramMiB().size()));
            long bw=demands.bwMbps().get(scenario.nextInt(demands.bwMbps().size()));
            long storage=demands.storageMiB().get(scenario.nextInt(demands.storageMiB().size()));
            int seconds=spec.workload().lengthSeconds().get(workload.nextInt(spec.workload().lengthSeconds().size()));
            vms.add(new VmSpec(i,pes,mips,ram,bw,storage));
            cloudlets.add(new CloudletSpec(i,i,pes,Math.multiplyExact(mips,seconds)));
        }
        return new Inputs(seeds,hosts,vms,cloudlets);
    }
    public static ScenarioSpec generateConfigured(long master,String phase,String name,int replication,GenerationSpec spec) {
        var inputs=configuredInputs(master,phase,name,replication,spec);
        Witness.validateNative(inputs);
        var references=ScenarioObjects.measureReferences(inputs,referenceHosts(inputs));
        for(int i=0;i<references.size();i++) if(references.get(i)!=(double)inputs.cloudlets().get(i).lengthMi()/inputs.vms().get(i).mipsPerPe())
            throw new IllegalArgumentException("Measured isolated reference "+references.get(i)+" differs from the declared workload length for VM "+i);
        return new ScenarioSpec(inputs,references);
    }
    /** Dedicated reference host sized to its own VM; an isolated full-utilization service time is capacity-independent. */
    private static List<HostSpec> referenceHosts(Inputs inputs) {
        var hosts=new ArrayList<HostSpec>();
        for(var vm:inputs.vms())
            hosts.add(new HostSpec(vm.id(),vm.pes(),vm.mipsPerPe(),vm.ramMiB(),vm.bwMbps(),vm.storageMiB(),175,250));
        return hosts;
    }
}
