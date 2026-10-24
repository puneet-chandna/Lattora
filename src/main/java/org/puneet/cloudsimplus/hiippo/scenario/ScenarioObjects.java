package org.puneet.cloudsimplus.hiippo.scenario;

import java.util.*;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.hosts.*;
import org.cloudsimplus.vms.*;
import org.cloudsimplus.cloudlets.*;
import org.cloudsimplus.resources.*;
import org.cloudsimplus.schedulers.vm.VmSchedulerSpaceShared;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.power.models.PowerModelHostSimple;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;

/** Single fresh-object factory shared by references and the later production simulator. */
public record ScenarioObjects(CloudSimPlus simulation,List<Host> hosts,List<Vm> vms,List<Cloudlet> cloudlets) {
    public static final double MIN_EVENT_INTERVAL_SECONDS=0.125;
    public static ScenarioObjects create(ScenarioSpec.Inputs inputs) {
        var hosts=new ArrayList<Host>(); var vms=new ArrayList<Vm>(); var cloudlets=new ArrayList<Cloudlet>();
        for(var h:inputs.hosts()) hosts.add(host(h));
        for(var v:inputs.vms()) vms.add(vm(v));
        for(var c:inputs.cloudlets()) {
            var cloudlet=new CloudletSimple(c.id(),c.lengthMi(),c.pes());
            cloudlet.setUtilizationModel(new UtilizationModelFull());
            cloudlet.setVm(vms.get(c.vmId()));
            cloudlets.add(cloudlet);
        }
        // Exact binary time prevents integer-MI truncation at integer-duration completion events.
        return new ScenarioObjects(new CloudSimPlus(MIN_EVENT_INTERVAL_SECONDS),List.copyOf(hosts),List.copyOf(vms),List.copyOf(cloudlets));
    }
    private static Host host(ScenarioSpec.HostSpec spec) {
        var pes=new ArrayList<Pe>(); for(int i=0;i<spec.pes();i++) pes.add(new PeSimple(spec.mipsPerPe()));
        var host=new HostSimple(spec.ramMiB(),spec.bwMbps(),spec.storageMiB(),pes);
        host.setId(spec.id()); host.setVmScheduler(new VmSchedulerSpaceShared());
        host.setPowerModel(new PowerModelHostSimple(spec.maxW(),spec.idleW()));
        return host;
    }
    private static Vm vm(ScenarioSpec.VmSpec spec) {
        var vm=new VmSimple(spec.id(),spec.mipsPerPe(),spec.pes());
        vm.setRam(spec.ramMiB()).setBw(spec.bwMbps()).setSize(spec.storageMiB());
        vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
        return vm;
    }
    public static List<Double> measureReferences(ScenarioSpec.Inputs inputs) {
        // One VM per dedicated identical-capacity host: no shared resources or network topology.
        var hosts=new ArrayList<ScenarioSpec.HostSpec>();
        for(var vm:inputs.vms()) hosts.add(new ScenarioSpec.HostSpec(vm.id(),16,3000,32768,10000,1000000,175,250));
        return measureReferences(inputs,hosts);
    }
    /** Reference hosts must be one per VM, indexed by VM id; capacity never affects an isolated reference. */
    public static List<Double> measureReferences(ScenarioSpec.Inputs inputs,List<ScenarioSpec.HostSpec> hosts) {
        var objects=create(new ScenarioSpec.Inputs(inputs.seeds(),hosts,inputs.vms(),inputs.cloudlets()));
        var policy=new VmAllocationPolicySimple((p,vm)->Optional.of(objects.hosts().get((int)vm.getId())));
        new DatacenterSimple(objects.simulation(),objects.hosts(),policy);
        var broker=new DatacenterBrokerSimple(objects.simulation());
        broker.setVmDestructionDelay(-1);
        broker.submitVmList(objects.vms()); broker.submitCloudletList(objects.cloudlets());
        objects.simulation().start();
        var references=new ArrayList<Double>();
        for(var cloudlet:objects.cloudlets()) {
            double seconds=cloudlet.getFinishTime()-cloudlet.getStartTime();
            if(cloudlet.getStatus()!=Cloudlet.Status.SUCCESS || cloudlet.getStartTime()!=cloudlet.getDcArrivalTime()
                || !Double.isFinite(seconds) || seconds<=0)
                throw new IllegalStateException("Incomplete isolated reference for cloudlet "+cloudlet.getId());
            references.add(seconds);
        }
        return List.copyOf(references);
    }
}
