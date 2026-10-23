package org.puneet.cloudsimplus.hiippo.runtime;

import com.google.gson.*;
import org.apache.commons.csv.*;
import org.puneet.cloudsimplus.hiippo.scenario.ScenarioGenerator;
import org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec;
import org.puneet.cloudsimplus.hiippo.scenario.Seeds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioRunTest {
    @TempDir Path temp;
    private static final String ID="scenario_schema_version,run_id,phase,scenario,replication,algorithm,population,iterations";
    private static final String CASE=ID+",scenario_seed,workload_seed,optimizer_seed,scenario_sha256,status,error_code,error_message,vm_count,host_count,evaluations,objective_w,energy_j,energy_kwh,sla_violations,sla_rate,completed_cloudlets,failed_cloudlets,censored_cloudlets,horizon_s,release_s,allocation_wall_ns";
    private static final String PLACE=ID+",vm_id,host_id";
    private static final String SUMMARY="scenario_schema_version,run_id,phase,scenario,algorithm,n,objective_mean_w,energy_mean_j,sla_mean,allocation_mean_ns";
    static final String FEASIBLE="""
        { "config_version": 1, "name": "known-answer-feasible", "master_seed": 123456, "replications": 1,
          "algorithms": ["HO","GA","FirstFit","BestFit"],
          "optimizer": {"population": 10, "iterations": 4},
          "hosts": [{"count":2,"pes":8,"mips_per_pe":3000,"ram_mib":16384,"bw_mbps":4000,"storage_mib":40000,"idle_w":100,"max_w":200}],
          "vms": {"count":2,"pes":[1],"mips_per_pe":[3000],"ram_mib":[2048],"bw_mbps":[1000],"storage_mib":[10000]},
          "workload": {"length_seconds":{"min":30,"max":30},"utilization":1.0,"release_seconds":0.0} }
        """;
    static final String TIGHT="""
        { "config_version": 1, "name": "known-answer-tight", "master_seed": 123456, "replications": 1,
          "algorithms": ["HO","GA","FirstFit","BestFit"],
          "optimizer": {"population": 10, "iterations": 4},
          "hosts": [{"count":2,"pes":2,"mips_per_pe":3000,"ram_mib":8192,"bw_mbps":2000,"storage_mib":20000,"idle_w":100,"max_w":200}],
          "vms": {"count":2,"pes":[2],"mips_per_pe":[3000],"ram_mib":[8192],"bw_mbps":[2000],"storage_mib":[20000]},
          "workload": {"length_seconds":{"min":60,"max":60},"utilization":1.0,"release_seconds":0.0} }
        """;
    static final String INFEASIBLE="""
        { "config_version": 1, "name": "known-answer-infeasible", "master_seed": 123456, "replications": 1,
          "algorithms": ["HO","GA","FirstFit","BestFit"],
          "optimizer": {"population": 10, "iterations": 4},
          "hosts": [{"count":1,"pes":4,"mips_per_pe":3000,"ram_mib":4096,"bw_mbps":1000,"storage_mib":10000,"idle_w":100,"max_w":200}],
          "vms": {"count":2,"pes":[1],"mips_per_pe":[3000],"ram_mib":[4096],"bw_mbps":[1000],"storage_mib":[10000]},
          "workload": {"length_seconds":{"min":60,"max":60},"utilization":1.0,"release_seconds":0.0} }
        """;
    static final String REPLICATED="""
        { "config_version": 1, "name": "two-replications", "master_seed": 123456, "replications": 2,
          "algorithms": ["HO","GA","FirstFit"],
          "optimizer": {"population": 10, "iterations": 4},
          "hosts": [{"count":1,"pes":32,"mips_per_pe":3000,"ram_mib":65536,"bw_mbps":20000,"storage_mib":2000000,"idle_w":220,"max_w":380},
                    {"count":2,"pes":16,"mips_per_pe":3000,"ram_mib":32768,"bw_mbps":10000,"storage_mib":1000000,"idle_w":175,"max_w":250}],
          "vms": {"count":4,"pes":[1,2],"mips_per_pe":[1000,2000,3000],"ram_mib":[1024,2048],"bw_mbps":[1000],"storage_mib":[10000]},
          "workload": {"length_seconds":[30,60],"utilization":1.0,"release_seconds":0.0} }
        """;
    static final String BASELINES="""
        { "config_version": 1, "name": "baselines-only", "master_seed": 123456, "replications": 1,
          "algorithms": ["FirstFit","BestFit"],
          "hosts": [{"count":2,"pes":8,"mips_per_pe":3000,"ram_mib":16384,"bw_mbps":4000,"storage_mib":40000,"idle_w":100,"max_w":200}],
          "vms": {"count":2,"pes":[1],"mips_per_pe":[3000],"ram_mib":[2048],"bw_mbps":[1000],"storage_mib":[10000]},
          "workload": {"length_seconds":{"min":30,"max":30},"utilization":1.0,"release_seconds":0.0} }
        """;

    private Path config(String name,String json) throws Exception {
        var file=temp.resolve(name+".json"); Files.writeString(file,json); return file;
    }
    private static Path run(Path config,Path output) throws Exception {
        var scenario=ScenarioConfig.load(config);
        try(var evidence=ScenarioOutput.create(scenario,output)) { return StaticCampaignRunner.execute(scenario,evidence); }
    }
    private static JsonObject json(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    private static List<Map<String,String>> rows(Path directory,String file,String header) throws Exception {
        try(var reader=Files.newBufferedReader(directory.resolve(file));
            var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            assertEquals(List.of(header.split(",")),parser.getHeaderNames());
            return parser.getRecords().stream().map(CSVRecord::toMap).toList();
        }
    }
    /** Scientific projection: identity and wall-clock timing are recorded but never compared. */
    private static List<Map<String,String>> scientific(Path directory,String file,String header,String... ignored) throws Exception {
        var result=new ArrayList<Map<String,String>>();
        for(var row:rows(directory,file,header)) {
            var values=new TreeMap<>(row);
            for(String key:ignored) values.remove(key);
            result.add(values);
        }
        return result;
    }
    private static Path singleRun(Path output) throws Exception {
        try(var files=Files.list(output)) { return files.findFirst().orElseThrow(); }
    }
    @Test void knownAnswerFeasibleAndTightRunsReproduceTheirOptima() throws Exception {
        for(var expectation:List.of(Map.entry(FEASIBLE,125.0),Map.entry(TIGHT,400.0))) {
            String json=expectation.getKey(); boolean tight=json.equals(TIGHT);
            String name=tight?"known-answer-tight":"known-answer-feasible";
            double objective=expectation.getValue(), energy=tight?24000.0:3750.0, horizon=tight?60.0:30.0;
            var config=config(name,json);
            var dir=run(config,temp.resolve("out-"+name));
            var manifest=json(dir.resolve("run.json"));
            assertEquals("COMPLETE",manifest.get("state").getAsString());
            assertEquals("static_scenario",manifest.get("experiment_kind").getAsString());
            assertEquals(1,manifest.get("scenario_schema_version").getAsInt());
            assertEquals("static_scenario_v1",manifest.get("protocol").getAsString());
            assertEquals("scenario",manifest.get("profile").getAsString());
            assertEquals(name,manifest.get("config_name").getAsString());
            assertEquals(ScenarioConfig.load(config).configSha256(),manifest.get("config_sha256").getAsString());
            assertEquals("CONSTRUCTIBLE",manifest.getAsJsonArray("feasibility").get(0).getAsJsonObject().get("kind").getAsString());
            assertEquals(4,manifest.get("expected_cases").getAsInt()); assertEquals(4,manifest.get("successful_cases").getAsInt());
            assertEquals(262,manifest.get("expected_evaluations").getAsInt()); assertEquals(262,manifest.get("completed_evaluations").getAsInt());
            assertEquals(0,manifest.get("unattempted_cases").getAsInt()); assertEquals(0,manifest.get("failed_cases").getAsInt());
            assertTrue(manifest.get("model_limits").getAsString().contains("no migrations"));
            assertTrue(manifest.get("evidence_limit").getAsString().contains("discarded candidates"));
            assertEquals(json(dir.resolve("config/resolved.json")),manifest.getAsJsonObject("resolved_config"));
            assertArrayEquals(Files.readAllBytes(config),Files.readAllBytes(dir.resolve("config/requested.json")));
            var cases=rows(dir,"raw/cases.csv",CASE);
            assertEquals(List.of("HO","GA","FirstFit","BestFit"),cases.stream().map(r->r.get("algorithm")).toList());
            for(var row:cases) {
                assertEquals("RUN_OK",row.get("status"));
                assertEquals("",row.get("error_code")); assertEquals("",row.get("error_message"));
                assertEquals(objective,Double.parseDouble(row.get("objective_w")));
                assertEquals(energy,Double.parseDouble(row.get("energy_j")));
                assertEquals(energy/3_600_000,Double.parseDouble(row.get("energy_kwh")));
                assertEquals(horizon,Double.parseDouble(row.get("horizon_s")));
                assertEquals(0.125,Double.parseDouble(row.get("release_s")));
                assertEquals(0.0,Double.parseDouble(row.get("sla_rate")));
                assertEquals("2",row.get("vm_count")); assertEquals("2",row.get("host_count"));
                assertEquals("2",row.get("completed_cloudlets")); assertEquals("0",row.get("censored_cloudlets"));
            }
            assertEquals(1,cases.stream().map(r->List.of(r.get("scenario_seed"),r.get("workload_seed"),r.get("scenario_sha256"))).distinct().count());
            assertEquals(2,cases.stream().map(r->r.get("optimizer_seed")).filter(value->!value.isEmpty()).distinct().count());
            assertEquals("",cases.get(2).get("optimizer_seed")); assertEquals("",cases.get(3).get("optimizer_seed"));
            for(var row:cases) if(!row.get("optimizer_seed").isEmpty())
                assertEquals(Long.toString(Seeds.derive(123456,"scenario",name,0,"optimizer",row.get("algorithm"))),row.get("optimizer_seed"));
            assertEquals(8,rows(dir,"raw/placements.csv",PLACE).size());
            assertEquals(4,rows(dir,"analysis/summary.csv",SUMMARY).size());
            for(var file:manifest.getAsJsonObject("files").entrySet())
                assertEquals(file.getValue().getAsString(),RunOutput.sha256(dir.resolve(file.getKey())));
            assertEquals(new TreeSet<>(Set.of("config/requested.json","config/resolved.json","effective.properties","raw/cases.csv",
                "raw/evaluations.csv","raw/placements.csv","analysis/summary.csv","scenarios/replication-0.json")),
                new TreeSet<>(manifest.getAsJsonObject("files").keySet()));
            assertTrue(json(dir.resolve("progress.json")).get("current_case").isJsonNull());
            assertEquals(4,json(dir.resolve("progress.json")).get("successful_cases").getAsInt());
            assertEquals(2,json(dir.resolve("scenarios/replication-0.json")).getAsJsonObject("inputs").getAsJsonArray("vms").size());
            assertEquals(ScenarioGenerator.generateConfigured(123456,"scenario",name,0,ScenarioConfig.load(config).generationSpec()).fingerprint(),
                json(dir.resolve("scenarios/replication-0.json")).get("scenario_sha256").getAsString());
            assertFalse(Files.exists(dir.resolve("case-spools")));
        }
    }
    @Test void rerunsProduceIdenticalScientificRowsAndScenarioHashes() throws Exception {
        var config=config("rep",TIGHT);
        var first=run(config,temp.resolve("rep-a"));
        var second=run(config,temp.resolve("rep-b"));
        assertEquals(scientific(first,"raw/cases.csv",CASE,"run_id","allocation_wall_ns"),
            scientific(second,"raw/cases.csv",CASE,"run_id","allocation_wall_ns"));
        assertEquals(scientific(first,"analysis/summary.csv",SUMMARY,"run_id","allocation_mean_ns"),
            scientific(second,"analysis/summary.csv",SUMMARY,"run_id","allocation_mean_ns"));
        assertEquals(rows(first,"raw/placements.csv",PLACE).stream().map(r->{var copy=new TreeMap<>(r);copy.remove("run_id");return copy;}).toList(),
            rows(second,"raw/placements.csv",PLACE).stream().map(r->{var copy=new TreeMap<>(r);copy.remove("run_id");return copy;}).toList());
        for(String file:List.of("scenarios/replication-0.json","config/resolved.json","effective.properties","config/requested.json"))
            assertEquals(Files.readString(first.resolve(file)),Files.readString(second.resolve(file)),file);
        assertEquals(new TreeSet<>(json(first.resolve("run.json")).getAsJsonObject("files").keySet()),
            new TreeSet<>(json(second.resolve("run.json")).getAsJsonObject("files").keySet()));
    }
    @Test void replicationsShareNoSeedsAndBaselineOnlyCampaignsComplete() throws Exception {
        var replicated=config("replicated",REPLICATED);
        var dir=run(replicated,temp.resolve("out-replicated"));
        var manifest=json(dir.resolve("run.json"));
        assertEquals("COMPLETE",manifest.get("state").getAsString());
        assertEquals(6,manifest.get("expected_cases").getAsInt()); assertEquals(6,manifest.get("successful_cases").getAsInt());
        assertEquals(522,manifest.get("expected_evaluations").getAsInt()); assertEquals(522,manifest.get("completed_evaluations").getAsInt());
        assertEquals(List.of(0,1),manifest.getAsJsonArray("feasibility").asList().stream()
            .map(element->element.getAsJsonObject().get("replication").getAsInt()).toList());
        assertTrue(manifest.getAsJsonArray("feasibility").asList().stream()
            .allMatch(element->element.getAsJsonObject().get("kind").getAsString().equals("CONSTRUCTIBLE")));
        var cases=rows(dir,"raw/cases.csv",CASE);
        assertEquals(List.of("0","0","0","1","1","1"),cases.stream().map(r->r.get("replication")).toList());
        assertEquals(2,cases.stream().map(r->r.get("scenario_sha256")).distinct().count());
        assertEquals(2,cases.stream().map(r->r.get("scenario_seed")).distinct().count());
        assertNotEquals(cases.get(0).get("scenario_sha256"),cases.get(3).get("scenario_sha256"));
        assertEquals(2,Files.list(dir.resolve("scenarios")).count());
        assertEquals(3,rows(dir,"analysis/summary.csv",SUMMARY).size());
        for(int replication=0;replication<2;replication++) for(int index=0;index<3;index++) {
            var row=cases.get(3*replication+index);
            assertEquals(Long.toString(Seeds.derive(123456,"scenario","two-replications",replication,"scenario")),row.get("scenario_seed"));
            assertEquals(Long.toString(Seeds.derive(123456,"scenario","two-replications",replication,"workload")),row.get("workload_seed"));
        }

        var baselines=config("baselines",BASELINES);
        var plain=run(baselines,temp.resolve("out-baselines"));
        var plainManifest=json(plain.resolve("run.json"));
        assertEquals("COMPLETE",plainManifest.get("state").getAsString());
        assertEquals(2,plainManifest.get("successful_cases").getAsInt());
        assertEquals(2,plainManifest.get("expected_evaluations").getAsInt()); assertEquals(2,plainManifest.get("completed_evaluations").getAsInt());
        assertFalse(plainManifest.getAsJsonObject("effective_config").has("evaluation.budget"));
        assertFalse(plainManifest.getAsJsonObject("resolved_config").has("optimizer"));
        assertFalse(json(plain.resolve("config/resolved.json")).has("optimizer"));
        assertEquals(2,rows(plain,"raw/cases.csv",CASE).stream().filter(r->r.get("optimizer_seed").isEmpty()).count());
        assertEquals(0,rows(plain,"raw/evaluations.csv",ID+",evaluation,iteration,stage,candidate_index,feasible,fitness_w,accepted,best_fitness_w").size());
    }
    @Test void provedInfeasibleConfigurationsFailBeforeAnySimulation() throws Exception {
        var config=config("infeasible",INFEASIBLE);
        var output=temp.resolve("out-infeasible");
        assertThrows(ScenarioInfeasible.class,()->run(config,output));
        var dir=singleRun(output);
        var manifest=json(dir.resolve("run.json"));
        assertEquals("FAILED",manifest.get("state").getAsString());
        assertEquals("SCENARIO_INFEASIBLE",manifest.getAsJsonObject("error").get("code").getAsString());
        var message=manifest.getAsJsonObject("error").get("message").getAsString();
        assertTrue(message.contains("ram_mib") && message.contains("8192") && message.contains("4096"),message);
        assertEquals(1,manifest.get("attempted_cases").getAsInt());
        assertEquals(0,manifest.get("successful_cases").getAsInt());
        assertEquals(1,manifest.get("failed_cases").getAsInt());
        assertEquals(3,manifest.get("unattempted_cases").getAsInt());
        assertEquals(0,manifest.get("completed_evaluations").getAsInt());
        assertEquals("INFEASIBLE_PROVED",manifest.getAsJsonArray("feasibility").get(0).getAsJsonObject().get("kind").getAsString());
        assertFalse(Files.exists(dir.resolve("analysis")));
        assertEquals("SCENARIO_INFEASIBLE",json(dir.resolve("progress.json")).getAsJsonObject("error").get("code").getAsString());
        var cases=rows(dir,"raw/cases.csv",CASE);
        assertEquals(1,cases.size()); assertEquals("SCENARIO_INFEASIBLE",cases.getFirst().get("error_code"));
        assertEquals("HO",cases.getFirst().get("algorithm"));
        assertFalse(Files.exists(dir.resolve("config/requested.json.tmp")));
        assertFalse(Files.exists(dir.resolve("run.json.tmp")));
    }
    @Test void parallelWorkersPublishTheSameScientificRowsAsSerialExecution() throws Exception {
        var previous=System.getProperty("cloudsim.workers");
        try {
            System.setProperty("cloudsim.workers","1");
            var serial=run(config("parallel",REPLICATED),temp.resolve("parallel-1"));
            System.setProperty("cloudsim.workers","3");
            var parallel=run(config("parallel",REPLICATED),temp.resolve("parallel-3"));
            assertEquals(scientific(serial,"raw/cases.csv",CASE,"run_id","allocation_wall_ns"),
                scientific(parallel,"raw/cases.csv",CASE,"run_id","allocation_wall_ns"));
            assertEquals(scientific(serial,"raw/placements.csv",PLACE,"run_id"),
                scientific(parallel,"raw/placements.csv",PLACE,"run_id"));
            assertEquals(scientific(serial,"raw/evaluations.csv",ID+",evaluation,iteration,stage,candidate_index,feasible,fitness_w,accepted,best_fitness_w","run_id"),
                scientific(parallel,"raw/evaluations.csv",ID+",evaluation,iteration,stage,candidate_index,feasible,fitness_w,accepted,best_fitness_w","run_id"));
            for(int replication=0;replication<2;replication++) assertEquals(
                Files.readString(serial.resolve("scenarios/replication-"+replication+".json")),
                Files.readString(parallel.resolve("scenarios/replication-"+replication+".json")));
            assertEquals(3,json(parallel.resolve("run.json")).get("execution_workers").getAsInt());
            assertEquals("COMPLETE",json(parallel.resolve("run.json")).get("state").getAsString());
            try(var files=Files.walk(parallel)) { assertFalse(files.anyMatch(path->path.getFileName().toString().endsWith(".spool"))); }
        } finally {
            if(previous==null) System.clearProperty("cloudsim.workers"); else System.setProperty("cloudsim.workers",previous);
        }
    }
    @Test void searchFreeCampaignsStillGenerateTheirDeclaredReplications() throws Exception {
        var config=ScenarioConfig.load(config("baselines-only",BASELINES));
        assertEquals(List.of("FirstFit","BestFit"),config.algorithms());
        assertNull(config.searchPopulation()); assertNull(config.searchIterations());
        assertEquals(0,config.evaluationBudget());
        var key=new Profile.CaseKey("scenario","baselines-only",0,"BestFit",null,null);
        ScenarioSpec spec=config.specFor(key);
        assertEquals(30.0,spec.referenceSeconds().getFirst());
        assertTrue(config.effective().containsKey("expected.evaluations"));
        assertEquals("2",config.effective().get("expected.evaluations"));
    }
}
