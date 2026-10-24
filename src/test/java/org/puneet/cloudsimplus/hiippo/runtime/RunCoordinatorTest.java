package org.puneet.cloudsimplus.hiippo.runtime;

import com.google.gson.*;
import org.apache.commons.csv.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunCoordinatorTest {
    @TempDir Path temp;
    @Test void exploreCompletesOnlyWithRequiredAnalysis() throws Exception {
        var config=new RunConfig(Profile.explore,123456,"INFO",temp);
        try(var output=RunOutput.create(config)) {
            assertDoesNotThrow(()->RunCoordinator.execute(config,output));
            assertEquals("COMPLETE",manifest(output.directory()).get("state").getAsString());
            assertEquals(8,csv(output.directory().resolve("analysis/scenario_summary.csv")).size());
            assertTrue(manifest(output.directory()).getAsJsonObject("files").has("analysis/report.md"));
            var process=new ProcessBuilder("python3","scripts/statistics_validator.py",output.directory().toString()).redirectErrorStream(true).start();
            String log=new String(process.getInputStream().readAllBytes()); assertEquals(0,process.waitFor(),log);
            process=new ProcessBuilder("python3","scripts/test_statistics_validator.py","--dataset",output.directory().toString()).redirectErrorStream(true).start();
            log=new String(process.getInputStream().readAllBytes()); assertEquals(0,process.waitFor(),log);
        }
    }
    @Test void missingOrCorruptAnalysisCannotPublishComplete() throws Exception {
        for(String variant:List.of("missing","corrupt","not_invoked","raw_changed")) {
            var config=new RunConfig(Profile.explore,123456,"INFO",temp);
            try(var output=RunOutput.create(config)) {
                org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec spec=null;
                for(var key:config.profile().cases()) {
                    output.begin(key);
                    if(spec==null || !spec.inputs().seeds().scenario().equals(key.scenario()) || spec.inputs().seeds().replication()!=key.replication()) {
                        spec=org.puneet.cloudsimplus.hiippo.scenario.ScenarioGenerator.generate(123456,key.phase(),key.scenario(),key.replication()); output.addSpecification(spec);
                    }
                    output.success(key,spec,RunCoordinator.evaluate(spec,key));
                }
                if(!variant.equals("not_invoked")) {
                    output.analyze(); var file=output.directory().resolve("analysis/scenario_summary.csv");
                    if(variant.equals("missing")) Files.delete(file);
                    else if(variant.equals("corrupt")) Files.writeString(file,Files.readString(file).replace(",HO,5,",",HO,4,"));
                    else {
                        file=output.directory().resolve("raw/main_results.csv"); var rows=Files.readAllLines(file);
                        var cells=rows.get(1).split(",",-1); cells[cells.length-1]="17"; rows.set(1,String.join(",",cells)); Files.write(file,rows);
                    }
                }
                assertThrows(Exception.class,output::complete,variant);
                assertEquals("FAILED",manifest(output.directory()).get("state").getAsString(),variant);
            }
        }
    }
    static List<CSVRecord> csv(Path path) throws Exception {
        try(var reader=Files.newBufferedReader(path); var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            return parser.getRecords();
        }
    }
    static JsonObject manifest(Path directory) throws Exception { return JsonParser.parseString(Files.readString(directory.resolve("run.json"))).getAsJsonObject(); }
    @Test void smokeIsCompletePairedReproducibleAndFullyRecorded() throws Exception {
        var snapshots=new ArrayList<List<Map<String,String>>>();
        for(int repetition=0;repetition<2;repetition++) {
            var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
            try(var output=RunOutput.create(config)) {
                RunCoordinator.execute(config,output);
                var dir=output.directory(); var m=manifest(dir);
                assertEquals("COMPLETE",m.get("state").getAsString()); assertEquals(4,m.get("successful_cases").getAsInt());
                assertEquals(0,m.get("unattempted_cases").getAsInt()); assertTrue(m.get("error").isJsonNull());
                assertEquals(1,m.getAsJsonArray("specifications").size());
                var spec=m.getAsJsonArray("specifications").get(0).getAsJsonObject();
                assertEquals(org.puneet.cloudsimplus.hiippo.scenario.Seeds.hash(spec.get("canonical_text").getAsString()),spec.get("scenario_sha256").getAsString());
                var rows=csv(dir.resolve("raw/main_results.csv")); assertEquals(4,rows.size());
                assertEquals(List.of("HO","GA","FirstFit","BestFit"),rows.stream().map(r->r.get("algorithm")).toList());
                assertEquals(1,rows.stream().map(r->r.get("scenario_sha256")).distinct().count());
                for(var row:rows) {
                    assertEquals("RUN_OK",row.get("status")); assertEquals("10",row.get("completed_cloudlets"));
                    assertEquals("0",row.get("sla_violations")); assertEquals("0",row.get("censored_cloudlets"));
                    assertEquals(Double.parseDouble(row.get("energy_j"))/3_600_000,Double.parseDouble(row.get("energy_kwh")));
                }
                assertEquals("",rows.get(2).get("optimizer_seed")); assertEquals("1",rows.get(2).get("evaluations"));
                assertEquals(40,csv(dir.resolve("raw/placements.csv")).size());
                var trace=csv(dir.resolve("raw/optimizer_trace.csv")); assertEquals(260,trace.size());
                assertEquals(20,trace.stream().filter(r->r.get("stage").equals("predator")).count());
                assertEquals(rows.get(0).get("objective_w"),trace.get(129).get("best_fitness_w"));
                assertEquals(rows.get(1).get("objective_w"),trace.get(259).get("best_fitness_w"));
                for(var entry:m.getAsJsonObject("files").entrySet()) assertEquals(RunOutput.sha256(dir.resolve(entry.getKey())),entry.getValue().getAsString());
                assertEquals(Set.of("effective.properties","raw/main_results.csv","raw/placements.csv","raw/optimizer_trace.csv"),m.getAsJsonObject("files").keySet());
                assertFalse(Files.exists(dir.resolve("analysis")));
                var normalized=new ArrayList<Map<String,String>>();
                for(var file:List.of("main_results.csv","placements.csv","optimizer_trace.csv")) for(var row:csv(dir.resolve("raw/"+file))) {
                    var values=new TreeMap<>(row.toMap()); values.remove("run_id"); values.remove("allocation_wall_ns"); normalized.add(values);
                }
                snapshots.add(normalized);
            }
        }
        assertEquals(snapshots.get(0),snapshots.get(1));
    }
    @Test void firstFailureStopsMatrixAndLeavesMissingOutcomesEmpty() throws Exception {
        var config=new RunConfig(Profile.smoke,123456,"INFO",temp); int[] calls={0};
        try(var output=RunOutput.create(config)) {
            assertThrows(Exception.class,()->RunCoordinator.execute(config,output,(spec,key)->{
                if(++calls[0]==2) throw new IllegalStateException("native failure, retained\nsecond line");
                return RunCoordinator.evaluate(spec,key);
            }));
            assertEquals(2,calls[0]); var m=manifest(output.directory());
            assertEquals("FAILED",m.get("state").getAsString()); assertEquals(2,m.get("attempted_cases").getAsInt());
            assertEquals(1,m.get("failed_cases").getAsInt()); assertEquals(2,m.get("unattempted_cases").getAsInt());
            assertTrue(m.getAsJsonArray("cases").get(2).getAsJsonObject().get("status").isJsonNull());
            var rows=csv(output.directory().resolve("raw/main_results.csv")); assertEquals(2,rows.size());
            assertEquals("RUN_ERROR",rows.get(1).get("status")); assertEquals("",rows.get(1).get("energy_j"));
            assertEquals("",rows.get(1).get("evaluations")); assertTrue(rows.get(1).get("error_message").contains("\nsecond line"));
            assertEquals(10,csv(output.directory().resolve("raw/placements.csv")).size());
            assertEquals(130,csv(output.directory().resolve("raw/optimizer_trace.csv")).size());
            assertFalse(Files.exists(output.directory().resolve("analysis")));
            assertThrows(IllegalStateException.class,output::validateRaw);
        }
    }
    @Test void genuineNoFeasibleSearchRetainsAllInfeasibleCallsAndDistinctStatus() throws Exception {
        var source=NativeMetricsTest.tiny(); var in=source.inputs();
        var impossible=new org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec(
            new org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec.Inputs(in.seeds(),
                List.of(new org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec.HostSpec(0,1,1000,1,1,1,100,200)),in.vms(),in.cloudlets()),source.referenceSeconds());
        var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
        try(var output=RunOutput.create(config)) {
            assertThrows(RunCoordinator.NoFeasiblePlacement.class,()->RunCoordinator.execute(config,output,(spec,key)->RunCoordinator.evaluate(impossible,key)));
            var row=csv(output.directory().resolve("raw/main_results.csv")).getFirst();
            assertEquals("ALGORITHM_NO_FEASIBLE_PLACEMENT",row.get("status")); assertEquals("",row.get("energy_j"));
            var trace=csv(output.directory().resolve("raw/optimizer_trace.csv")); assertEquals(130,trace.size());
            assertTrue(trace.stream().allMatch(r->r.get("feasible").equals("false") && r.get("fitness_w").isEmpty() && r.get("best_fitness_w").isEmpty()));
            assertEquals(0,csv(output.directory().resolve("raw/placements.csv")).size());
        }
    }
    @Test void aPostPlanningFailureRetainsOptimizerDiagnosticsButNoOutcomes() throws Exception {
        var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
        try(var output=RunOutput.create(config)) {
            assertThrows(Exception.class,()->RunCoordinator.execute(config,output,(spec,key)->{
                var result=RunCoordinator.evaluate(spec,key);
                throw new RunCoordinator.SimulationFailure(new IllegalStateException("native execution failed"),result.trace());
            }));
            assertEquals(130,csv(output.directory().resolve("raw/optimizer_trace.csv")).size());
            assertEquals("RUN_ERROR",csv(output.directory().resolve("raw/main_results.csv")).getFirst().get("status"));
            assertEquals(0,csv(output.directory().resolve("raw/placements.csv")).size());
        }
    }
    @Test void terminalValidationChecksThePersistedCanonicalSpecification() throws Exception {
        var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
        try(var output=RunOutput.create(config)) {
            RunCoordinator.execute(config,output);
            var m=manifest(output.directory());
            m.getAsJsonArray("specifications").get(0).getAsJsonObject().addProperty("canonical_text","corrupted but valid JSON");
            Files.writeString(output.directory().resolve("run.json"),m.toString());
            assertThrows(Exception.class,output::validateRaw);
        }
    }
    @Test void invalidMetricOrFiniteWrongObjectiveCannotBecomeSuccessful() throws Exception {
        for(boolean objective:List.of(false,true)) {
            var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
            try(var output=RunOutput.create(config)) {
                assertThrows(Exception.class,()->RunCoordinator.execute(config,output,(spec,key)->{
                    var r=RunCoordinator.evaluate(spec,key); var m=r.metrics();
                    return new RunCoordinator.CaseResult(r.plan(),objective?r.objectiveWatts()+1:r.objectiveWatts(),r.evaluations(),r.trace(),
                        objective?m:new NativeMetrics.Result(Double.NaN,m.energyKwh(),m.slaViolations(),m.slaRate(),m.completed(),m.failed(),m.censored(),m.horizonSeconds(),m.releaseSeconds(),0.0),r.allocationNanos());
                }));
                assertEquals("FAILED",manifest(output.directory()).get("state").getAsString());
                var row=csv(output.directory().resolve("raw/main_results.csv")).getFirst();
                assertEquals("RUN_ERROR",row.get("status")); assertEquals("",row.get("objective_w"));
                assertEquals(0,csv(output.directory().resolve("raw/placements.csv")).size());
            }
        }
    }
    @Test void outputFailureAfterRealSimulationStillFailsAndDoesNotClaimComplete() throws Exception {
        var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
        try(var output=RunOutput.create(config)) {
            assertThrows(Exception.class,()->RunCoordinator.execute(config,output,(spec,key)->{
                var result=RunCoordinator.evaluate(spec,key);
                var path=output.directory().resolve("raw/placements.csv"); Files.delete(path); Files.createDirectory(path);
                return result;
            }));
            var m=manifest(output.directory()); assertEquals("FAILED",m.get("state").getAsString());
            assertEquals(0,m.get("successful_cases").getAsInt()); assertEquals(1,m.get("failed_cases").getAsInt());
        }
    }
    @Test void lostRequiredLogCannotProduceACompleteRun() throws Exception {
        var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
        try(var output=RunOutput.create(config)) {
            assertThrows(Exception.class,()->RunCoordinator.execute(config,output,(spec,key)->{
                var result=RunCoordinator.evaluate(spec,key);
                Files.deleteIfExists(output.directory().resolve("logs/run.log")); return result;
            }));
            assertEquals("FAILED",manifest(output.directory()).get("state").getAsString());
        }
    }
    @Test void missingEffectiveConfigurationFailsTheCase() throws Exception { rejectedEffectiveConfiguration("missing"); }
    @Test void alteredEffectiveConfigurationFailsTheCase() throws Exception { rejectedEffectiveConfiguration("altered"); }
    @Test void truncatedEffectiveConfigurationFailsTheCase() throws Exception { rejectedEffectiveConfiguration("truncated"); }
    private void rejectedEffectiveConfiguration(String variant) throws Exception {
        var config=new RunConfig(Profile.smoke,123456,"INFO",temp); int[] calls={0};
        try(var output=RunOutput.create(config)) {
            assertThrows(java.io.IOException.class,()->RunCoordinator.execute(config,output,(spec,key)->{
                calls[0]++; var result=RunCoordinator.evaluate(spec,key);
                if(calls[0]==1) damageEffectiveConfiguration(output.directory().resolve("effective.properties"),variant);
                return result;
            }));
            assertEquals(1,calls[0]); var m=manifest(output.directory());
            assertEquals("FAILED",m.get("state").getAsString()); assertEquals(0,m.get("successful_cases").getAsInt());
            assertEquals(1,m.get("failed_cases").getAsInt()); assertEquals(3,m.get("unattempted_cases").getAsInt());
            assertEquals("123456",m.getAsJsonObject("effective_config").get("master.seed").getAsString());
            var rows=csv(output.directory().resolve("raw/main_results.csv")); assertEquals(1,rows.size());
            assertEquals("RUN_ERROR",rows.getFirst().get("status")); assertEquals("",rows.getFirst().get("energy_j"));
            assertEquals(0,csv(output.directory().resolve("raw/placements.csv")).size());
            assertFalse(Files.exists(output.directory().resolve("analysis")));
        }
    }
    @Test void rawValidationAlsoRejectsMissingOrChangedEffectiveConfiguration() throws Exception {
        for(var variant:List.of("missing","altered","truncated")) {
            var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
            try(var output=RunOutput.create(config)) {
                RunCoordinator.execute(config,output);
                damageEffectiveConfiguration(output.directory().resolve("effective.properties"),variant);
                assertThrows(java.io.IOException.class,output::validateRaw);
                assertFalse(Files.exists(output.directory().resolve("analysis")));
            }
        }
    }
    private static void damageEffectiveConfiguration(Path path,String variant) throws Exception {
        if(variant.equals("missing")) Files.delete(path);
        else if(variant.equals("altered")) Files.writeString(path,Files.readString(path).replace("master.seed=123456\n","master.seed=654321\n"));
        else Files.writeString(path,"master.seed=123456\n");
    }
    @Test void terminalValidationReadsRecordedMappingsAndObjective() throws Exception {
        var config=new RunConfig(Profile.smoke,123456,"INFO",temp);
        try(var output=RunOutput.create(config)) {
            RunCoordinator.execute(config,output);
            var file=output.directory().resolve("raw/main_results.csv");
            var text=Files.readString(file); var rows=csv(file);
            String wrong=Double.toString(Double.parseDouble(rows.getFirst().get("objective_w"))+1);
            Files.writeString(file,text.replaceFirst(","+java.util.regex.Pattern.quote(rows.getFirst().get("objective_w"))+",",","+wrong+","));
            assertThrows(Exception.class,output::validateRaw);
        }
    }
}
