package org.puneet.cloudsimplus.hiippo;

import org.junit.jupiter.api.Test;
import org.puneet.cloudsimplus.hiippo.scenario.*;
import org.puneet.cloudsimplus.hiippo.placement.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlacementTest {
    static ScenarioSpec.Inputs input(List<ScenarioSpec.HostSpec> hosts, List<ScenarioSpec.VmSpec> vms) {
        var clouds=vms.stream().map(v->new ScenarioSpec.CloudletSpec(v.id(),v.id(),v.pes(),v.mipsPerPe()*2)).toList();
        return new ScenarioSpec.Inputs(new ScenarioSpec.SeedMetadata(1,"main","Micro",0,1,2),hosts,vms,clouds);
    }
    static ScenarioSpec.HostSpec host(int id,int pes,long mips,long ram,long bw,long disk) {
        return new ScenarioSpec.HostSpec(id,pes,mips,ram,bw,disk,175,250);
    }
    static ScenarioSpec.VmSpec vm(int id,int pes,long mips,long ram,long bw,long disk) {
        return new ScenarioSpec.VmSpec(id,pes,mips,ram,bw,disk);
    }
    @Test void cumulativeBoundariesAndCompletePlans() {
        var h=host(0,2,3000,20,20,20);
        var exact=input(List.of(h),List.of(vm(0,1,3000,10,10,10),vm(1,1,3000,10,10,10)));
        new PlacementPlan(List.of(0,0)).validate(exact);
        var ledger=new PlacementLedger(exact); ledger.place(0,0);
        assertThrows(IllegalArgumentException.class,()->ledger.place(0,0));
        for(var bad:List.of(vm(1,2,3000,10,10,10),vm(1,1,3001,10,10,10),vm(1,1,3000,11,10,10),vm(1,1,3000,10,11,10),vm(1,1,3000,10,10,11))) {
            var over=input(List.of(h),List.of(exact.vms().getFirst(),bad));
            assertThrows(IllegalArgumentException.class,()->new PlacementPlan(List.of(0,0)).validate(over));
        }
        for(var mapping:List.of(List.of(0),List.of(0,0,0),List.of(0,1),List.of(0,-1)))
            assertThrows(IllegalArgumentException.class,()->new PlacementPlan(mapping).validate(exact));
        assertThrows(IllegalArgumentException.class,()->host(0,2,3000,-1,20,20));
        assertThrows(UnsupportedOperationException.class,()->new PlacementPlan(List.of(0,0)).hostIds().set(0,1));
    }
    @Test void nativeExactCapacityAndOneUnitOverAgreeWithLedger() {
        var h=host(0,2,3000,20,20,20);
        var first=vm(0,1,3000,10,10,10);
        for(var second:List.of(vm(1,1,3000,10,10,10),vm(1,2,3000,10,10,10),vm(1,1,3001,10,10,10),
            vm(1,1,3000,11,10,10),vm(1,1,3000,10,11,10),vm(1,1,3000,10,10,11))) {
            var in=input(List.of(h),List.of(first,second)); var objects=ScenarioObjects.create(in);
            assertTrue(objects.hosts().getFirst().createVm(objects.vms().getFirst()).fully());
            var ledger=new PlacementLedger(in); ledger.place(0,0);
            assertEquals(ledger.canPlace(1,0),objects.hosts().getFirst().createVm(objects.vms().get(1)).fully());
        }
        // Per-PE mismatch with enough PEs and total MIPS must still fail.
        var perPe=input(List.of(host(0,4,1000,20,20,20)),List.of(vm(0,1,1001,1,1,1)));
        assertFalse(new PlacementLedger(perPe).canPlace(0,0));
        var nativePerPe=ScenarioObjects.create(perPe);
        assertFalse(nativePerPe.hosts().getFirst().createVm(nativePerPe.vms().getFirst()).fully());
    }
    @Test void strictPolicyReturnsRealSuitabilityAndRejectsNonfiniteNativeValues() {
        var in=input(List.of(host(0,2,3000,20,20,20)),List.of(vm(0,1,3000,10,10,10)));
        var plan=new PlacementPlan(List.of(0));
        var run=PlannedSimulation.create(in,plan);
        var suitability=run.policy().allocateHostForVm(run.objects().vms().getFirst());
        assertNotSame(org.cloudsimplus.hosts.HostSuitability.NULL,suitability); assertTrue(suitability.fully());
        for(double invalid:new double[]{Double.NaN,Double.POSITIVE_INFINITY}) {
            var bad=PlannedSimulation.create(in,plan);
            bad.objects().vms().getFirst().getProcessor().setMips(invalid);
            assertThrows(IllegalStateException.class,()->bad.objects().simulation().start());
            assertFalse(bad.placementVerified());
        }
    }
    @Test void bestFitBreaksEqualResidualTiesByLowestHostId() {
        var in=input(List.of(host(0,2,3000,20,20,20),host(1,2,3000,20,20,20)),
            List.of(vm(0,1,3000,10,10,10)));
        var ledger=new PlacementLedger(in);
        assertEquals(ledger.residualAfter(0,0),ledger.residualAfter(0,1));
        assertEquals(List.of(0),BaselinePlanner.bestFit(in).orElseThrow().plan().hostIds());
    }
    @Test void tinyExhaustiveMappingsHaveIndependentFeasibilityAndPower() {
        var in=input(List.of(host(0,2,3000,20,20,20),host(1,4,3000,40,40,40)),
            List.of(vm(0,1,3000,10,10,10),vm(1,1,3000,10,10,10),vm(2,1,3000,10,10,10)));
        int feasible=0;
        for(int bits=0;bits<8;bits++) {
            var ids=List.of(bits&1,(bits>>1)&1,(bits>>2)&1);
            var plan=new PlacementPlan(ids);
            int zero=Collections.frequency(ids,0),one=3-zero;
            if(zero>2) assertThrows(IllegalArgumentException.class,()->plan.validate(in));
            else {
                feasible++; plan.validate(in);
                double expected=(zero==0?0:175+75*zero/2.0)+(one==0?0:175+75*one/4.0);
                assertEquals(expected,PlacementObjective.watts(in,plan));
            }
        }
        assertEquals(7,feasible);
        for(boolean best:List.of(false,true)) {
            var result=(best?BaselinePlanner.bestFit(in):BaselinePlanner.firstFit(in)).orElseThrow();
            assertEquals(List.of(0,0,1),result.plan().hostIds());
            assertEquals(1,result.evaluations()); assertEquals(443.75,result.objectiveWatts());
        }
        var preferTight=input(List.of(host(0,4,3000,40,40,40),host(1,2,3000,20,20,20)),in.vms());
        assertEquals(List.of(1,1,0),BaselinePlanner.bestFit(preferTight).orElseThrow().plan().hostIds());
        var impossible=input(List.of(in.hosts().getFirst()),in.vms());
        assertTrue(BaselinePlanner.firstFit(impossible).isEmpty());
    }
    @Test void nativePlanIsCompleteBeforeAnyCloudletStartsAndRejectionAborts() {
        var in=input(List.of(host(0,2,3000,20,20,20),host(1,2,3000,20,20,20)),
            List.of(vm(0,1,3000,10,10,10),vm(1,1,3000,10,10,10)));
        var plan=new PlacementPlan(List.of(1,0));
        var run=PlannedSimulation.create(in,plan);
        int[] starts={0};
        run.objects().cloudlets().forEach(c->c.addOnStartListener(e->{
            assertTrue(run.placementVerified());
            run.verifyActualPlacement(); starts[0]++;
        }));
        run.objects().simulation().start(); assertEquals(2,starts[0]);
        assertEquals(2,run.policy().attempts());
        assertThrows(IllegalStateException.class,()->run.policy().allocateHostForVm(run.objects().vms().getFirst()));
        var rejected=PlannedSimulation.create(in,plan);
        rejected.objects().hosts().get(0).getRamProvisioner().allocateResourceForVm(new org.cloudsimplus.vms.VmSimple(99,1000,1).setRam(20),20);
        assertThrows(IllegalStateException.class,()->rejected.objects().simulation().start());
        assertFalse(rejected.placementVerified());
        assertEquals(2,rejected.policy().attempts());
        assertTrue(rejected.objects().vms().getFirst().isCreated());
        assertTrue(rejected.objects().cloudlets().stream().allMatch(c->c.getFinishedLengthSoFar()==0));
    }
    @Test void frozenHostCapacityStillReproducesTheDocumentedFixedDenominatorFormula() {
        // 16 PEs x 3000 MIPS is the frozen host class; host-relative utilization must equal /48000 there.
        var in=input(List.of(host(0,16,3000,32768,10000,1000000)),List.of(vm(0,1,3000,1024,1000,10000)));
        var plan=new PlacementPlan(List.of(0));
        assertEquals(175+75*3000/48000.0,PlacementObjective.watts(in,plan));
        assertEquals(179.6875,PlacementObjective.watts(in,plan));
        var ledger=new PlacementLedger(in);
        assertEquals(179.6875,ledger.incrementalWatts(0,0));
    }
}
