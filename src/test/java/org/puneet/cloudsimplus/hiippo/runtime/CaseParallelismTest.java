package org.puneet.cloudsimplus.hiippo.runtime;

import com.google.gson.JsonParser;
import org.apache.commons.csv.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import org.puneet.cloudsimplus.hiippo.placement.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseParallelismTest {
    @TempDir Path temp;

    private void workers(String value,ThrowingAction action) throws Exception {
        String previous=System.getProperty("cloudsim.workers");
        try { if(value==null) System.clearProperty("cloudsim.workers"); else System.setProperty("cloudsim.workers",value); action.run(); }
        finally { if(previous==null) System.clearProperty("cloudsim.workers"); else System.setProperty("cloudsim.workers",previous); }
    }
    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
    private static Path stress(StressConfig config) throws Exception {
        try(var output=StressOutput.create(config)) { return StaticCampaignRunner.execute(config,output); }
    }
    private List<Map<String,String>> scientific(Path file) throws Exception {
        try(var reader=Files.newBufferedReader(file); var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            var result=new ArrayList<Map<String,String>>();
            for(var row:parser) { var values=new TreeMap<>(row.toMap()); values.remove("run_id"); values.remove("allocation_wall_ns"); values.remove("allocation_mean_ns"); result.add(values); }
            return result;
        }
    }
    @Test void twoWorkersOverlapCasesButPublishOnlyInCanonicalOrder() throws Exception {
        workers("2",()->{
            var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
            var entered=new CountDownLatch(2); var active=new AtomicInteger(); var maximum=new AtomicInteger();
            try(var output=RunOutput.create(config)) {
                assertDoesNotThrow(()->RunCoordinator.execute(config,output,(spec,key)->{
                    int count=active.incrementAndGet(); maximum.accumulateAndGet(count,Math::max); entered.countDown();
                    try { if(!entered.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Cases did not overlap"); return RunCoordinator.evaluate(spec,key); }
                    finally { active.decrementAndGet(); }
                }));
                assertEquals(2,maximum.get()); assertEquals(0,active.get());
                assertEquals(List.of("HO","GA","FirstFit","BestFit"),scientific(output.directory().resolve("raw/main_results.csv")).stream().map(r->r.get("algorithm")).toList());
            }
        });
    }
    @Test void researchSeedsPlacementsNumericMetricsAndTracesMatchSerialExecution() throws Exception {
        var directories=new ArrayList<Path>();
        for(String count:List.of("1","3")) workers(count,()->{
            var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
            try(var output=RunOutput.create(config)) { RunCoordinator.execute(config,output); directories.add(output.directory()); }
        });
        for(String file:List.of("raw/main_results.csv","raw/placements.csv","raw/optimizer_trace.csv"))
            assertEquals(scientific(directories.get(0).resolve(file)),scientific(directories.get(1).resolve(file)),file);
        assertEquals(Files.readString(directories.get(0).resolve("effective.properties")),Files.readString(directories.get(1).resolve("effective.properties")));
        var first=JsonParser.parseString(Files.readString(directories.get(0).resolve("run.json"))).getAsJsonObject();
        var parallel=JsonParser.parseString(Files.readString(directories.get(1).resolve("run.json"))).getAsJsonObject();
        assertEquals(first.get("specifications"),parallel.get("specifications"));
        assertTrue(parallel.has("execution_workers"));
        assertEquals(3,parallel.get("execution_workers").getAsInt());
        assertFalse(parallel.getAsJsonObject("effective_config").has("cloudsim.workers"));
    }
    @Test void stressScalarEvidenceAndScenarioPairingMatchSerialExecution() throws Exception {
        var directories=new ArrayList<Path>();
        var config=new StressConfig(10,3,4,2,3,123456,"stress",temp);
        for(String count:List.of("1","3")) workers(count,()->directories.add(stress(config)));
        for(String file:List.of("raw/cases.csv","raw/placements.csv","raw/evaluations.csv","analysis/summary.csv"))
            assertEquals(scientific(directories.get(0).resolve(file)),scientific(directories.get(1).resolve(file)),file);
        for(int replication=0;replication<3;replication++) assertEquals(Files.readString(directories.get(0).resolve("scenarios/replication-"+replication+".json")),Files.readString(directories.get(1).resolve("scenarios/replication-"+replication+".json")));
        try(var files=Files.walk(directories.get(1))) { assertFalse(files.anyMatch(p->p.getFileName().toString().endsWith(".spool"))); }
        assertEquals(3,JsonParser.parseString(Files.readString(directories.get(1).resolve("run.json"))).getAsJsonObject().get("execution_workers").getAsInt());
    }
    @Test void invalidWorkerSettingsAreRejectedBeforeCreatingRunArtifacts() throws Exception {
        for(String value:List.of("0","-1","33","auto","2.0"," 2","2147483648","")) workers(value,()->{
            assertThrows(IllegalArgumentException.class,()->RunOutput.create(new RunConfig(Profile.smoke,123456,"INFO",temp)));
            assertThrows(IllegalArgumentException.class,()->stress(new StressConfig(10,3,4,2,1,123456,"stress",temp)));
        });
        try(var files=Files.list(temp)) { assertEquals(0,files.count()); }
    }
    @Test void aLaterFailedFutureCancelsActiveCasesAndCannotPublishComplete() throws Exception {
        workers("2",()->{
            var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
            var firstEntered=new CountDownLatch(1); var active=new AtomicInteger(); var interrupted=new CountDownLatch(1);
            try(var output=RunOutput.create(config)) {
                assertTimeoutPreemptively(Duration.ofSeconds(5),()->assertThrows(Exception.class,()->RunCoordinator.execute(config,output,(spec,key)->{
                    active.incrementAndGet();
                    try {
                        if(key.algorithm().equals("HO")) {
                            firstEntered.countDown();
                            try { new CountDownLatch(1).await(); } catch(InterruptedException e) { interrupted.countDown(); throw e; }
                        }
                        if(!firstEntered.await(2,TimeUnit.SECONDS)) throw new IllegalStateException("First case never started");
                        throw new IllegalStateException("injected GA failure");
                    } finally { active.decrementAndGet(); }
                })));
                assertEquals(0,active.get()); assertEquals(0,interrupted.getCount());
                var manifest=JsonParser.parseString(Files.readString(output.directory().resolve("run.json"))).getAsJsonObject();
                assertEquals("FAILED",manifest.get("state").getAsString()); assertEquals(0,manifest.get("successful_cases").getAsInt());
                assertTrue(manifest.getAsJsonObject("error").get("message").getAsString().contains("injected GA failure"));
                assertFalse(Files.exists(output.directory().resolve("analysis")));
            }
        });
    }
    @Test void anInterruptedCoordinatorStopsAllItsWorkersBeforeReturning() throws Exception {
        workers("2",()->{
            var config=new RunConfig(Profile.smoke,123456,"INFO",temp); var entered=new CountDownLatch(2); var active=new AtomicInteger();
            try(var output=RunOutput.create(config)) {
                var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
                var owner=new Thread(()->{
                    try { RunCoordinator.execute(config,output,(spec,key)->{
                        active.incrementAndGet(); entered.countDown();
                        try { new CountDownLatch(1).await(); return RunCoordinator.evaluate(spec,key); }
                        finally { active.decrementAndGet(); }
                    }); } catch(Throwable e) { failure.set(e); }
                });
                owner.start();
                try { assertTrue(entered.await(3,TimeUnit.SECONDS)); }
                finally { owner.interrupt(); owner.join(3000); }
                assertFalse(owner.isAlive()); assertNotNull(failure.get()); assertEquals(0,active.get());
                assertEquals("FAILED",JsonParser.parseString(Files.readString(output.directory().resolve("run.json"))).getAsJsonObject().get("state").getAsString());
            }
        });
    }
    @Test void interruptedPlacementWorkStopsBeforeAnyFurtherScientificEvaluation() {
        var inputs=NativeMetricsTest.tiny().inputs();
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,()->Search.run(inputs,Search.Algorithm.HO,4,2,123456));
            assertThrows(CancellationException.class,()->BaselinePlanner.firstFit(inputs));
            assertThrows(CancellationException.class,()->BaselinePlanner.bestFit(inputs));
        } finally { Thread.interrupted(); }
    }
    @Test void stressFailureRetainsPartialScalarEvidenceAndClosesOwnedSpools() throws Exception {
        workers("2",()->{
            var config=new StressConfig(10,3,4,2,2,123456,"stress",temp); var entered=new CountDownLatch(1); var active=new AtomicInteger();
            try(var output=StressOutput.create(config)) {
                assertTimeoutPreemptively(Duration.ofSeconds(5),()->assertThrows(Exception.class,()->StaticCampaignRunner.executeParallel(config,output,(c,spec,key,sink,phase)->{
                    active.incrementAndGet();
                    try {
                        if(key.algorithm().equals("HO")) {
                            sink.accept(new Search.Evaluation(1,0,"INITIAL",0,true,
                                new PlacementObjective.Score(100.0,0.5,0.25,0.5,0.25),true,100.0,0.25)); entered.countDown();
                            new CountDownLatch(1).await();
                        }
                        if(!entered.await(2,TimeUnit.SECONDS)) throw new IllegalStateException("First stress case never started");
                        throw new IllegalStateException("injected stress failure");
                    } finally { active.decrementAndGet(); }
                })));
                assertEquals(0,active.get()); assertFalse(Files.exists(output.directory().resolve("case-spools")));
                var manifest=JsonParser.parseString(Files.readString(output.directory().resolve("run.json"))).getAsJsonObject();
                assertEquals("FAILED",manifest.get("state").getAsString()); assertEquals(1,manifest.get("completed_evaluations").getAsInt());
                assertEquals(1,scientific(output.directory().resolve("raw/evaluations.csv")).size());
                assertFalse(Files.exists(output.directory().resolve("analysis/summary.csv")));
            }
        });
    }
    @Test void aWorkerErrorStillPublishesAFailedManifest() throws Exception {
        workers("2",()->{
            var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
            try(var output=RunOutput.create(config)) {
                assertThrows(Exception.class,()->RunCoordinator.execute(config,output,(spec,key)->{
                    if(!key.algorithm().equals("HO")) new CountDownLatch(1).await();
                    throw new AssertionError("injected worker error");
                }));
                assertEquals("FAILED",JsonParser.parseString(Files.readString(output.directory().resolve("run.json"))).getAsJsonObject().get("state").getAsString());
            }
        });
    }
    @Test void stressLiveProgressAdvancesBeforePublishingAnyCanonicalEvidence() throws Exception {
        workers("2",()->{
            var config=new StressConfig(10,3,4,2,1,123456,"stress",temp);
            var firstEvaluation=new CountDownLatch(1); var release=new CountDownLatch(1);
            var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            try(var output=StressOutput.create(config)) {
                var owner=new Thread(()->{
                    try { StaticCampaignRunner.executeParallel(config,output,(c,spec,key,sink,phase)->{
                        if(key.algorithm().equals("HO")) {
                            sink.accept(new Search.Evaluation(1,0,"INITIAL",0,true,
                                new PlacementObjective.Score(100.0,0.5,0.25,0.5,0.25),true,100.0,0.25));
                            firstEvaluation.countDown();
                        }
                        release.await(); throw new IllegalStateException("end controlled progress test");
                    }); } catch(Throwable e) { failure.set(e); }
                });
                owner.start();
                try {
                    assertTrue(firstEvaluation.await(3,TimeUnit.SECONDS));
                    boolean seen=false; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
                    while(System.nanoTime()<deadline) {
                        var progress=JsonParser.parseString(Files.readString(output.directory().resolve("progress.json"))).getAsJsonObject();
                        var current=progress.get("current_case");
                        var active=current!=null && current.isJsonObject()?current.getAsJsonObject():null;
                        if(active!=null && active.has("computing_evaluation") && active.get("computing_evaluation").getAsInt()==1) {
                            assertEquals("optimizing",active.get("computing_phase").getAsString());
                            assertEquals(0,active.get("computing_iteration").getAsInt()); assertEquals("INITIAL",active.get("computing_stage").getAsString());
                            assertEquals(0,active.get("evaluation").getAsInt()); assertEquals(0,progress.get("completed_evaluations").getAsInt());
                            assertEquals(0,progress.get("successful_cases").getAsInt());
                            seen=true; break;
                        }
                        Thread.sleep(20);
                    }
                    assertTrue(seen,"Live optimizer progress was not published before case completion");
                    assertTrue(owner.isAlive()); assertEquals(0,scientific(output.directory().resolve("raw/evaluations.csv")).size());
                    assertEquals(0,JsonParser.parseString(Files.readString(output.directory().resolve("run.json"))).getAsJsonObject().get("completed_evaluations").getAsInt());
                } finally { release.countDown(); owner.join(5000); if(owner.isAlive()) { owner.interrupt(); owner.join(3000); } }
                assertFalse(owner.isAlive()); assertNotNull(failure.get());
            }
        });
    }
    @Test void stressLookaheadStartsNextReplicationHoWhileFirstHoIsBlocked() throws Exception {
        workers("2",()->{
            var config=new StressConfig(10,3,4,2,3,123456,"stress",temp);
            var nextHoStarted=new CountDownLatch(1); var active=new AtomicInteger(); var activeHo=new AtomicInteger();
            var maximum=new AtomicInteger(); var maximumHo=new AtomicInteger();
            try(var output=StressOutput.create(config)) {
                assertTimeoutPreemptively(Duration.ofSeconds(5),()->assertThrows(Exception.class,()->StaticCampaignRunner.executeParallel(config,output,(c,spec,key,sink,phase)->{
                    maximum.accumulateAndGet(active.incrementAndGet(),Math::max);
                    boolean ho=key.algorithm().equals("HO");
                    if(ho) maximumHo.accumulateAndGet(activeHo.incrementAndGet(),Math::max);
                    try {
                        if(ho && key.replication()==0) {
                            if(!nextHoStarted.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("Next HO was never scheduled");
                            throw new IllegalStateException("end controlled lookahead test");
                        }
                        if(ho && key.replication()==1) {
                            nextHoStarted.countDown(); new CountDownLatch(1).await();
                        }
                        // Fast GA/FirstFit/BestFit cases finish while the first HO stays blocked.
                        return null;
                    } finally { if(ho) activeHo.decrementAndGet(); active.decrementAndGet(); }
                })));
                assertEquals(0,nextHoStarted.getCount(),"Lookahead must reach the next replication while the first HO is active");
                assertEquals(2,maximumHo.get()); assertEquals(2,maximum.get()); assertEquals(0,active.get());
                assertEquals(0,scientific(output.directory().resolve("raw/cases.csv")).stream().filter(r->r.get("status").equals("RUN_OK")).count());
                assertFalse(Files.exists(output.directory().resolve("case-spools")));
            }
        });
    }
}
