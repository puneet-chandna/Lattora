package org.puneet.cloudsimplus.hiippo.scenario;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredScenarioTest {
    private static GenerationSpec.HostClass host(int count,int pes,long mips) {
        return host(count,pes,mips,16384);
    }
    private static GenerationSpec.HostClass host(int count,int pes,long mips,long ram) {
        return new GenerationSpec.HostClass(count,pes,mips,ram,10000,1000000,100,200);
    }
    private static GenerationSpec.VmDemands vms(int count,List<Integer> pes,List<Long> mips,List<Long> ram) {
        return new GenerationSpec.VmDemands(count,pes,mips,ram,List.of(1000L),List.of(10000L));
    }
    private static ScenarioSpec.Inputs inputs(List<GenerationSpec.HostClass> hosts,GenerationSpec.VmDemands vms,List<Integer> minutes) {
        return ScenarioGenerator.configuredInputs(123456,"scenario","known-answer",0,new GenerationSpec(hosts,vms,new GenerationSpec.Workload(minutes)));
    }

    @Test void drawOrderIsDocumentedDeterministicAndIndependentOfHostClasses() {
        var spec=new GenerationSpec(List.of(host(2,4,3000)),vms(3,List.of(1,2),List.of(1000L,2000L,3000L),List.of(1024L,2048L)),
            new GenerationSpec.Workload(List.of(30,60)));
        var first=ScenarioGenerator.configuredInputs(123456,"scenario","draw-order",0,spec);
        assertEquals(first,ScenarioGenerator.configuredInputs(123456,"scenario","draw-order",0,spec));
        assertNotEquals(first,ScenarioGenerator.configuredInputs(123457,"scenario","draw-order",0,spec));
        assertNotEquals(first,ScenarioGenerator.configuredInputs(123456,"scenario","draw-order",1,spec));
        assertEquals(List.of(0,1),first.hosts().stream().map(ScenarioSpec.HostSpec::id).toList());
        for(var host:first.hosts()) assertEquals(3000,host.mipsPerPe());
        for(var host:first.hosts()) assertEquals(100,host.idleW());
        // One independent re-derivation of the documented per-VM draw order.
        var scenario=new Random(Seeds.derive(123456,"scenario","draw-order",0,"scenario"));
        var workload=new Random(Seeds.derive(123456,"scenario","draw-order",0,"workload"));
        for(int i=0;i<3;i++) {
            int pes=List.of(1,2).get(scenario.nextInt(2));
            long mips=List.of(1000L,2000L,3000L).get(scenario.nextInt(3));
            long ram=List.of(1024L,2048L).get(scenario.nextInt(2));
            assertEquals(1000L,List.of(1000L).get(scenario.nextInt(1)));
            assertEquals(10000L,List.of(10000L).get(scenario.nextInt(1)));
            var vm=first.vms().get(i); var cloudlet=first.cloudlets().get(i);
            assertEquals(pes,vm.pes()); assertEquals(mips,vm.mipsPerPe()); assertEquals(ram,vm.ramMiB());
            assertEquals(1000,vm.bwMbps()); assertEquals(10000,vm.storageMiB());
            assertEquals(i,vm.id()); assertEquals(i,cloudlet.id()); assertEquals(i,cloudlet.vmId()); assertEquals(pes,cloudlet.pes());
        }
        // The workload stream is drawn only once per VM, after every scenario-stream field.
        var minutes=new ArrayList<Integer>();
        for(int i=0;i<3;i++) minutes.add(List.of(30,60).get(workload.nextInt(2)));
        for(int i=0;i<3;i++) assertEquals(minutes.get(i).longValue()*first.vms().get(i).mipsPerPe(),first.cloudlets().get(i).lengthMi());
        assertEquals(List.of(30,60),spec.workload().lengthSeconds());
    }
    @Test void measuredReferencesMatchTheDrawnMinutesAndStayDeterministic() {
        var spec=new GenerationSpec(List.of(host(1,4,3000)),vms(2,List.of(1),List.of(3000L),List.of(1024L)),
            new GenerationSpec.Workload(List.of(30,60)));
        var generated=ScenarioGenerator.generateConfigured(123456,"scenario","references",0,spec);
        assertEquals(generated,ScenarioGenerator.generateConfigured(123456,"scenario","references",0,spec));
        var minutes=new ArrayList<Long>();
        for(int i=0;i<2;i++) {
            long drawn=generated.inputs().cloudlets().get(i).lengthMi()/generated.inputs().vms().get(i).mipsPerPe();
            assertTrue(drawn==30 || drawn==60,drawn+" must come from the declared workload");
            minutes.add(drawn);
            assertEquals((double)drawn,generated.referenceSeconds().get(i));
        }
        assertEquals(10*Collections.max(minutes),generated.censorSeconds());
        assertEquals(generated.fingerprint(),Seeds.hash(generated.canonicalText()));
        assertTrue(generated.canonicalText().contains("host,0,4,3000,16384,10000,1000000,100,200,,"));
        assertTrue(generated.canonicalText().contains("reference.0,"+generated.referenceSeconds().getFirst()+"\n"));
        // Distinct phase identities never collide with the frozen or stress derivations.
        assertNotEquals(Seeds.derive(123456,"main","Micro",0,"scenario"),Seeds.derive(123456,"scenario","references",0,"scenario"));
        assertNotEquals(Seeds.derive(123456,"stress","Static-V1-H1",0,"scenario"),Seeds.derive(123456,"scenario","references",0,"scenario"));
        assertNotEquals(Seeds.derive(123456,"scenario","references",0,"scenario"),Seeds.derive(123456,"scenario","references",0,"workload"));
    }
    @Test void feasibilityProvesWitnessAndReportsUnprovedGaps() {
        var constructible=inputs(List.of(host(2,8,3000)),vms(2,List.of(1),List.of(3000L),List.of(2048L)),List.of(30));
        assertEquals("CONSTRUCTIBLE",Feasibility.classify(constructible,0).kind());
        assertNull(Feasibility.classify(constructible,0).reason());
        assertTrue(Witness.constructs(constructible));
        assertDoesNotThrow(()->Witness.validate(constructible));

        var proved=inputs(List.of(host(1,4,3000,8192)),vms(2,List.of(1),List.of(3000L),List.of(8192L)),List.of(60));
        var total=Feasibility.classify(proved,0);
        assertEquals("INFEASIBLE_PROVED",total.kind());
        assertEquals("replication 0 total ram_mib demand 16384 exceeds total host ram_mib capacity 8192",total.reason());
        var exact=inputs(List.of(host(1,4,3000,16384)),vms(2,List.of(1),List.of(3000L),List.of(8192L)),List.of(60));
        assertEquals("CONSTRUCTIBLE",Feasibility.classify(exact,0).kind());

        var perPe=inputs(List.of(host(2,4,1000)),vms(2,List.of(1),List.of(3000L),List.of(1024L)),List.of(60));
        var slow=Feasibility.classify(perPe,0);
        assertEquals("INFEASIBLE_PROVED",slow.kind());
        assertEquals("replication 0 total mips_per_pe demand 3000 exceeds total host mips_per_pe capacity 1000",slow.reason());

        var unproved=inputs(List.of(host(1,2,3000),host(1,4,3000)),vms(3,List.of(2),List.of(3000L),List.of(1024L)),List.of(60));
        var gap=Feasibility.classify(unproved,3);
        assertEquals("UNPROVED",gap.kind());
        assertEquals("replication 3 witness placement rejected: host 0 pes demand 4 exceeds capacity 2",gap.reason());
        assertFalse(Witness.constructs(unproved));
        assertEquals("Witness capacity exceeded on host 0",assertThrows(IllegalArgumentException.class,()->Witness.validate(unproved)).getMessage());

        var narrow=inputs(List.of(host(1,2,2000),host(1,4,2000)),vms(2,List.of(1),List.of(3000L),List.of(1024L)),List.of(60));
        assertEquals("Witness per-PE MIPS exceeded",assertThrows(IllegalArgumentException.class,()->Witness.validate(narrow)).getMessage());
    }
    @Test void scenarioPhaseIdentityIsAcceptedAndRejected() {
        for(String name:List.of("a","known-answer-tight","heterogeneous-hosts","resource-pressure","a.b_c-d9"))
            assertDoesNotThrow(()->new ScenarioSpec.SeedMetadata(1,"scenario",name,0,1,2));
        for(String name:List.of("Bad","","1abc","A1","a".repeat(65),"Micro","Static-V1-H1","a b"))
            assertThrows(IllegalArgumentException.class,()->new ScenarioSpec.SeedMetadata(1,"scenario",name,0,1,2));
        assertThrows(IllegalArgumentException.class,()->new ScenarioSpec.SeedMetadata(1,"other","known-answer",0,1,2));
        assertThrows(IllegalArgumentException.class,()->new ScenarioSpec.SeedMetadata(1,"scenario","known-answer",-1,1,2));
        assertThrows(IllegalArgumentException.class,()->new ScenarioSpec.SeedMetadata(1,"scenario",null,0,1,2));
    }
    @Test void generationSpecRejectsEveryInvalidDeclaration() {
        assertThrows(IllegalArgumentException.class,()->new GenerationSpec(List.of(),vms(1,List.of(1),List.of(1000L),List.of(1024L)),new GenerationSpec.Workload(List.of(30))));
        assertThrows(IllegalArgumentException.class,()->new GenerationSpec.HostClass(0,1,1000,1,1,1,0,0));
        assertThrows(IllegalArgumentException.class,()->new GenerationSpec.HostClass(1,0,1000,1,1,1,0,0));
        assertThrows(IllegalArgumentException.class,()->new GenerationSpec.HostClass(1,1,1000,1,1,1,10,9));
        assertThrows(ArithmeticException.class,()->new GenerationSpec.HostClass(1,Integer.MAX_VALUE,Long.MAX_VALUE,1,1,1,0,0));
        assertEquals(2147483647L*2,new GenerationSpec.HostClass(1,2147483647,2,1,1,1,0,0).totalMips());
        assertThrows(IllegalArgumentException.class,()->vms(0,List.of(1),List.of(1000L),List.of(1024L)));
        assertThrows(IllegalArgumentException.class,()->vms(1,List.of(),List.of(1000L),List.of(1024L)));
        assertThrows(IllegalArgumentException.class,()->vms(1,List.of(2,1),List.of(1000L),List.of(1024L)));
        assertThrows(IllegalArgumentException.class,()->vms(1,List.of(0),List.of(1000L),List.of(1024L)));
        assertThrows(IllegalArgumentException.class,()->vms(1,List.of(1),List.of(),List.of(1024L)));
        assertThrows(IllegalArgumentException.class,()->new GenerationSpec.Workload(List.of()));
        assertThrows(IllegalArgumentException.class,()->new GenerationSpec.Workload(List.of(60,30)));
        assertThrows(IllegalArgumentException.class,()->new GenerationSpec.Workload(List.of(0)));
    }
}
