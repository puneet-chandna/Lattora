package org.puneet.cloudsimplus.hiippo;

import org.junit.jupiter.api.Test;
import org.puneet.cloudsimplus.hiippo.placement.*;
import java.util.*;
import java.nio.file.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import com.google.gson.GsonBuilder;
import static org.junit.jupiter.api.Assertions.*;

class SearchTest {
    static org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec.Inputs tiny() {
        return PlacementTest.input(List.of(PlacementTest.host(0,2,3000,20,20,20),PlacementTest.host(1,4,3000,40,40,40),
            new org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec.HostSpec(2,4,3000,40,40,40,210,300)),
            java.util.stream.IntStream.range(0,4).mapToObj(i->PlacementTest.vm(i,1,3000,10,10,10)).toList());
    }
    static class Tape extends Random {
        final int offset; int u,g;
        Tape(int offset) { this.offset=offset; }
        @Override public double nextDouble() { return ((37*u++ +11+offset)%101)/101.0; }
        @Override public int nextInt(int bound) { return (int)(nextDouble()*bound); }
        @Override public double nextGaussian() { return new double[]{0,-1.25,.5,0,2,-.75,1.5,-2,.25}[g++%9]; }
    }
    // Access the supplied-RNG overload without expanding the production public API.
    static Search.StreamResult stream(org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec.Inputs inputs,
            Search.Algorithm algorithm,int n,int t,Random random,Search.EvaluationSink sink) throws Exception {
        var method=Search.class.getDeclaredMethod("runStreaming",inputs.getClass(),Search.Algorithm.class,
            int.class,int.class,Random.class,Search.EvaluationSink.class);
        method.setAccessible(true);
        try { return (Search.StreamResult)method.invoke(null,inputs,algorithm,n,t,random,sink); }
        catch(InvocationTargetException e) { throw (Exception)e.getCause(); }
    }
    @Test void scalarEventsMatchDetailedEvidenceAndSuppliedDrawCounts() throws Exception {
        for(var algorithm:Search.Algorithm.values()) for(int[] setting:new int[][]{{4,4,0},{4,4,17},{4,4,49},{6,2,22}}) {
            int n=setting[0],t=setting[1],offset=setting[2];
            var fullRandom=new Tape(offset); var streamRandom=new Tape(offset);
            var full=Search.run(tiny(),algorithm,n,t,fullRandom);
            var events=new ArrayList<Search.Evaluation>();
            var streamed=stream(tiny(),algorithm,n,t,streamRandom,events::add);
            assertEquals(full.best(),streamed.best());
            assertEquals(Search.budget(n,t),streamed.evaluations());
            assertEquals(streamed.evaluations(),events.size());
            assertEquals(List.of(fullRandom.u,fullRandom.g),List.of(streamRandom.u,streamRandom.g));
            Double best=null;
            for(int i=0;i<events.size();i++) {
                var detail=full.trace().get(i); var event=events.get(i);
                if(detail.accepted() && detail.watts().isPresent())
                    best=best==null?detail.watts().orElseThrow():Math.min(best,detail.watts().orElseThrow());
                assertEquals(new Search.Evaluation(i+1,detail.iteration(),detail.phase(),detail.member(),
                    detail.plan().isPresent(),detail.watts().orElse(null),detail.accepted(),best),event);
            }
            assertEquals(streamed.best().orElseThrow().watts(),events.getLast().bestWatts());
        }
    }
    @Test void seededStreamingAndImpossibleCandidatesKeepDetailedSemantics() throws Exception {
        var impossible=PlacementTest.input(List.of(PlacementTest.host(0,1,3000,10,10,10)),tiny().vms());
        for(var algorithm:Search.Algorithm.values()) for(var input:List.of(tiny(),impossible)) {
            var full=Search.run(input,algorithm,2,1,42);
            int[] count={0};
            var streamed=Search.runStreaming(input,algorithm,2,1,42,e->{
                assertEquals(++count[0],e.evaluation());
                if(input==impossible) { assertFalse(e.feasible()); assertNull(e.watts()); assertNull(e.bestWatts()); }
            });
            assertEquals(8,count[0]); assertEquals(8,streamed.evaluations());
            assertEquals(full.best(),streamed.best());
        }
        assertThrows(NullPointerException.class,()->Search.runStreaming(tiny(),Search.Algorithm.HO,2,1,42,null));
    }
    @Test void streamingSinkIOExceptionStopsBeforeFurtherEvaluationsOrDraws() throws Exception {
        for(var algorithm:Search.Algorithm.values()) {
            var random=new Tape(17); var failure=new IOException("sink failed");
            int[] count={0},drawsAtFailure={0,0};
            assertSame(failure,assertThrows(IOException.class,()->stream(tiny(),algorithm,4,4,random,e->{
                if(++count[0]==6) { drawsAtFailure[0]=random.u; drawsAtFailure[1]=random.g; throw failure; }
            })));
            assertEquals(6,count[0]); assertArrayEquals(drawsAtFailure,new int[]{random.u,random.g});
        }
    }
    @Test void scalarStreamingDoesNotRetainEvaluationHistoryUnderSmallHeap() throws Exception {
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx32m",
            "-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
            SearchTest.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS),"bounded streaming check timed out");
            var output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),output);
            assertTrue(output.contains("bounded streaming PASS: 600004 scalar evaluations"),output);
        } finally { process.destroyForcibly(); }
    }
    public static void main(String[] args) throws IOException {
        int total=0;
        for(var algorithm:Search.Algorithm.values()) {
            int[] count={0};
            var result=Search.runStreaming(tiny(),algorithm,2,50000,42,e->{
                if(e.evaluation()!=++count[0]) throw new AssertionError("evaluation order");
            });
            if(count[0]!=300002 || result.evaluations()!=300002 || result.best().isEmpty())
                throw new AssertionError("stream result");
            result.best().orElseThrow().plan().validate(tiny()); total+=count[0];
        }
        System.out.println("bounded streaming PASS: "+total+" scalar evaluations");
    }
    @Test void fullFixedDrawFixturesMatchIndependentPythonEquations() throws Exception {
        for(var algorithm:Search.Algorithm.values()) for(int[] setting:new int[][]{{4,4,0},{4,4,17},{4,4,49},{6,2,22}}) {
            int n=setting[0],t=setting[1],offset=setting[2];
            var random=new Tape(offset); var result=Search.run(tiny(),algorithm,n,t,random);
            var rows=new ArrayList<Map<String,Object>>();
            for(var r:result.trace()) {
                var row=new LinkedHashMap<String,Object>();
                row.put("evaluation",r.evaluation()); row.put("iteration",r.iteration()); row.put("phase",r.phase()); row.put("member",r.member());
                row.put("dominant",r.dominant()); row.put("genes",r.genes().orElse(null)); row.put("plan",r.plan().map(PlacementPlan::hostIds).orElse(null));
                row.put("watts",r.watts().orElse(null)); row.put("accepted",r.accepted()); rows.add(row);
            }
            var b=result.best().orElseThrow();
            var fixture=Map.of("algorithm",algorithm.name(),"n",n,"t",t,"offset",offset,"trace",rows,"draws",List.of(random.u,random.g),
                "best",Map.of("genes",b.genes(),"plan",b.plan().hostIds(),"watts",b.watts(),"creation",b.creation()));
            var path=Path.of("target","oracle-"+algorithm+"-"+offset+".json");
            Files.writeString(path,new GsonBuilder().serializeNulls().create().toJson(fixture));
            var process=new ProcessBuilder("python3","scripts/optimizer_oracle.py",path.toString()).redirectErrorStream(true).start();
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0,process.waitFor(),output); System.out.print(output);
            assertEquals(Search.budget(n,t),result.trace().size());
            if(algorithm==Search.Algorithm.GA && offset==22) {
                assertEquals(31,b.creation()); // Incumbent already holding the minimum host-relative power is retained.
                assertEquals(250.0,b.watts());
                assertEquals(8,result.trace().getLast().iteration());
            }
            if(algorithm==Search.Algorithm.HO) {
                assertTrue(result.trace().stream().anyMatch(r->r.iteration()>0 && r.accepted()));
                assertTrue(result.trace().stream().anyMatch(r->r.phase().equals("RIVER_FEMALE") && !r.accepted()));
                assertTrue(result.trace().stream().filter(r->r.phase().equals("PREDATOR")).noneMatch(Search.Trace::accepted));
            }
        }
    }
    @Test void allProfileBudgetsAndFailureAreExplicit() {
        var impossible=PlacementTest.input(List.of(PlacementTest.host(0,1,3000,10,10,10)),tiny().vms());
        for(var algorithm:Search.Algorithm.values()) {
            for(int[] setting:new int[][]{{10,4,130},{20,20,1220},{30,40,3630}}) {
                assertEquals(setting[2],Search.budget(setting[0],setting[1]));
                var result=Search.run(tiny(),algorithm,setting[0],setting[1],42);
                assertEquals(setting[2],result.trace().size());
                for(int i=0;i<result.trace().size();i++) assertEquals(i+1,result.trace().get(i).evaluation());
            }
            var result=Search.run(impossible,algorithm,2,1,1);
            assertTrue(result.best().isEmpty()); assertEquals(8,result.trace().size());
            assertTrue(result.trace().stream().allMatch(r->r.plan().isEmpty() && r.watts().isEmpty()));
        }
        assertThrows(IllegalArgumentException.class,()->Search.budget(3,1));
        assertThrows(IllegalArgumentException.class,()->Search.budget(2,0));
    }
    @Test void repairBoundariesTiesExhaustiveMappingsAndImmutability() {
        var input=tiny(); int feasible=0;
        for(int a=0;a<81;a++) {
            int bits=a; var ids=new ArrayList<Integer>(); double[] genes=new double[4];
            for(int j=0;j<4;j++) { ids.add(bits%3); genes[j]=(bits%3+.5)/3; bits/=3; }
            var plan=Search.repair(input,genes).orElseThrow();
            assertTrue(Collections.frequency(plan.hostIds(),0)<=2);
            if(Collections.frequency(ids,0)<=2) { feasible++; assertEquals(ids,plan.hostIds()); }
            double expected=0;
            for(int h=0;h<3;h++) { int count=Collections.frequency(plan.hostIds(),h); if(count>0) expected+=(h==2?210:175)+(h==2?90:75)*count/(h==0?2.0:4.0); }
            assertEquals(expected,PlacementObjective.watts(input,plan));
        }
        assertEquals(72,feasible);
        var tied=PlacementTest.input(List.of(PlacementTest.host(0,4,3000,40,40,40),PlacementTest.host(1,4,3000,40,40,40),
            PlacementTest.host(2,1,3000,10,10,10)),input.vms());
        assertEquals(List.of(2,0,0,0),Search.repair(tied,new double[]{1,1,1,1}).orElseThrow().hostIds());
        double[] raw={-1,1,2,0},copy=raw.clone();
        assertEquals(List.of(0,2,2,0),Search.repair(input,raw).orElseThrow().hostIds()); assertArrayEquals(copy,raw);
        var result=Search.run(input,Search.Algorithm.HO,4,4,1); var best=result.best().orElseThrow();
        assertThrows(UnsupportedOperationException.class,()->best.genes().set(0,.5));
        assertThrows(UnsupportedOperationException.class,()->result.trace().clear());
        assertThrows(UnsupportedOperationException.class,()->result.trace().getFirst().genes().orElseThrow().clear());
    }
    @Test void predatorCannotBecomeBestEvenWhenCheaperThanEveryMember() throws Exception {
        var hosts=List.of(new org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec.HostSpec(0,4,3000,40,40,40,200,200),
            new org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec.HostSpec(1,4,3000,40,40,40,1,1),
            new org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec.HostSpec(2,4,3000,40,40,40,100,100));
        var input=PlacementTest.input(hosts,List.of(PlacementTest.vm(0,1,3000,10,10,10)));
        java.util.function.Supplier<Random> random=()->new Random() {
            int count;
            @Override public double nextDouble() { return count++==9?.4:.8; }
            @Override public int nextInt(int bound) { return 0; }
            @Override public double nextGaussian() { return .5; }
        };
        var result=Search.run(input,Search.Algorithm.HO,2,1,random.get());
        assertEquals(1,result.trace().stream().filter(r->r.phase().equals("PREDATOR")).findFirst().orElseThrow().watts().orElseThrow());
        assertEquals(100,result.best().orElseThrow().watts());
        assertEquals(0,result.best().orElseThrow().creation()); // Equal proposals retain the incumbent.
        var events=new ArrayList<Search.Evaluation>();
        var streamed=stream(input,Search.Algorithm.HO,2,1,random.get(),events::add);
        assertEquals(result.best(),streamed.best());
        var predator=events.stream().filter(e->e.phase().equals("PREDATOR")).findFirst().orElseThrow();
        assertEquals(1,predator.watts()); assertFalse(predator.accepted()); assertEquals(100,predator.bestWatts());
        assertTrue(events.stream().allMatch(e->e.bestWatts()==100));
    }
    @Test void nonfiniteGeneratedDefenseIsInvalidBeforeClampingAndStillCounted() {
        var random=new Random() {
            int g;
            @Override public double nextDouble() { return .5; }
            @Override public int nextInt(int bound) { return 0; }
            @Override public double nextGaussian() { return g++%2==0?Double.MAX_VALUE:Double.MIN_VALUE; }
        };
        var result=Search.run(tiny(),Search.Algorithm.HO,2,1,random);
        var defense=result.trace().stream().filter(r->r.phase().equals("DEFENSE")).findFirst().orElseThrow();
        assertTrue(defense.genes().isEmpty()); assertTrue(defense.watts().isEmpty()); assertFalse(defense.accepted());
        assertEquals(8,result.trace().size());
    }
    @Test void exactlyZeroReciprocalDistanceUsesMinNormalAndStaysFiniteWhenPossible() {
        var random=new Random() {
            @Override public double nextDouble() { return 0; }
            @Override public int nextInt(int bound) { return 0; }
            @Override public double nextGaussian() { return 1; }
        };
        var result=Search.run(tiny(),Search.Algorithm.HO,2,1,random);
        var defense=result.trace().stream().filter(r->r.phase().equals("DEFENSE")).findFirst().orElseThrow();
        assertEquals(List.of(0.0,0.0,0.0,0.0),defense.genes().orElseThrow());
        assertTrue(defense.watts().isPresent()); assertFalse(defense.accepted());
    }
    @Test void zeroScalarDefenseDenominatorHasNoUndocumentedEpsilon() {
        double gUniform=5.0/12;
        double cUniform=2*(2*Math.cos(2*Math.PI*(2*gUniform-1))-1);
        assertTrue(cUniform>=0 && cUniform<1);
        assertEquals(0,(1+.5*cUniform)-2*Math.cos(2*Math.PI*(2*gUniform-1)));
        var random=new Random() {
            int u;
            @Override public double nextDouble() { int at=u++; return at==33?cUniform:at==35?gUniform:0; }
            @Override public int nextInt(int bound) { return 0; }
            @Override public double nextGaussian() { return 1; }
        };
        var result=Search.run(tiny(),Search.Algorithm.HO,2,1,random);
        var defense=result.trace().stream().filter(r->r.phase().equals("DEFENSE")).findFirst().orElseThrow();
        assertTrue(defense.genes().isEmpty()); assertTrue(defense.watts().isEmpty()); assertEquals(8,result.trace().size());
    }
    @Test void pairedScenariosHaveDistinctOptimizerSeedsAndDeterministicNativePlans() {
        var in=org.puneet.cloudsimplus.hiippo.scenario.ScenarioGenerator.inputs(123456,"main","Micro",0);
        long ho=org.puneet.cloudsimplus.hiippo.scenario.Seeds.derive(123456,"main","Micro",0,"optimizer","HO");
        long ga=org.puneet.cloudsimplus.hiippo.scenario.Seeds.derive(123456,"main","Micro",0,"optimizer","GA");
        assertNotEquals(ho,ga);
        for(var algorithm:Search.Algorithm.values()) {
            long seed=algorithm==Search.Algorithm.HO?ho:ga;
            var result=Search.run(in,algorithm,10,4,seed);
            assertEquals(result,Search.run(in,algorithm,10,4,seed));
            var nativeRun=PlannedSimulation.create(in,result.best().orElseThrow().plan());
            int[] started={0};
            nativeRun.objects().cloudlets().forEach(c->c.addOnStartListener(e->{ nativeRun.verifyActualPlacement(); started[0]++; }));
            nativeRun.objects().simulation().start();
            assertTrue(nativeRun.placementVerified()); assertEquals(in.vms().size(),started[0]);
        }
    }
    @Test void repairRetainsDecodedHostsAndCountsEveryCall() {
        var in=PlacementTest.input(List.of(PlacementTest.host(0,2,3000,20,20,20),PlacementTest.host(1,4,3000,40,40,40)),
            List.of(PlacementTest.vm(0,1,3000,10,10,10),PlacementTest.vm(1,1,3000,10,10,10),PlacementTest.vm(2,1,3000,10,10,10)));
        assertEquals(List.of(0,0,1),Search.repair(in,new double[]{0,0,0}).orElseThrow().hostIds());
        assertTrue(Search.repair(in,new double[]{0,Double.NaN,0}).isEmpty());
        for(var algorithm:Search.Algorithm.values()) {
            var run=Search.run(in,algorithm,10,4,42);
            assertEquals(130,run.trace().size());
            assertTrue(run.best().isPresent());
            assertEquals(run,Search.run(in,algorithm,10,4,42));
        }
    }
}
