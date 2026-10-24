package org.puneet.cloudsimplus.hiippo.runtime;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.csv.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StressRunTest {
    @TempDir Path temp;
    private static Path stress(StressConfig config) throws Exception {
        try(var output=StressOutput.create(config)) { return StaticCampaignRunner.execute(config,output); }
    }
    private static final String ID="stress_schema_version,run_id,phase,scenario,replication,algorithm,population,iterations";
    private static final String CASE=ID+",scenario_seed,workload_seed,optimizer_seed,scenario_sha256,status,error_code,error_message,vm_count,host_count,evaluations,objective_w,energy_j,energy_kwh,sla_violations,sla_rate,completed_cloudlets,failed_cloudlets,censored_cloudlets,horizon_s,release_s,allocation_wall_ns";
    private static final String PLACE=ID+",vm_id,host_id";
    private static final String TRACE=ID+",evaluation,iteration,stage,candidate_index,feasible,fitness_w,accepted,best_fitness_w";
    private static final String SUMMARY="stress_schema_version,run_id,phase,scenario,algorithm,n,objective_mean_w,energy_mean_j,sla_mean,allocation_mean_ns";
    private List<Map<String,String>> rows(Path dir,String file,String header) throws Exception {
        try(var reader=Files.newBufferedReader(dir.resolve(file)); var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            assertEquals(List.of(header.split(",")),parser.getHeaderNames());
            return parser.getRecords().stream().map(CSVRecord::toMap).toList();
        }
    }
    private JsonObject json(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    @Test void pairedMatrixStreamsCanonicalEvidenceAndDeterministicScientificRows() throws Exception {
        var config=new StressConfig(10,3,4,2,2,123456,"stress",temp);
        var dirs=List.of(stress(config),stress(config));
        var scientific=new ArrayList<List<Map<String,String>>>();
        for(var dir:dirs) {
            var m=json(dir.resolve("run.json"));
            assertEquals("COMPLETE",m.get("state").getAsString()); assertFalse(m.has("schema_version"));
            assertEquals("static_stress",m.get("experiment_kind").getAsString()); assertEquals(1,m.get("stress_schema_version").getAsInt());
            assertEquals(8,m.get("expected_cases").getAsInt()); assertEquals(8,m.get("successful_cases").getAsInt());
            assertEquals(116,m.get("completed_evaluations").getAsInt()); assertEquals(116,m.get("expected_evaluations").getAsInt());
            assertFalse(m.has("specifications")); assertFalse(m.has("cases"));
            assertTrue(m.get("evidence_limit").getAsString().contains("discarded"));
            var progress=json(dir.resolve("progress.json")); assertTrue(progress.get("current_case").isJsonNull());
            assertEquals(8,progress.get("successful_cases").getAsInt());
            var cases=rows(dir,"raw/cases.csv",CASE); assertEquals(8,cases.size());
            assertEquals(List.of("HO","GA","FirstFit","BestFit","HO","GA","FirstFit","BestFit"),cases.stream().map(r->r.get("algorithm")).toList());
            assertEquals(80,rows(dir,"raw/placements.csv",PLACE).size()); assertEquals(112,rows(dir,"raw/evaluations.csv",TRACE).size());
            assertEquals(4,rows(dir,"analysis/summary.csv",SUMMARY).size());
            for(int r=0;r<2;r++) {
                var s=json(dir.resolve("scenarios/replication-"+r+".json"));
                assertEquals(10,s.getAsJsonObject("inputs").getAsJsonArray("vms").size());
                assertEquals(3,s.getAsJsonObject("inputs").getAsJsonArray("hosts").size());
                for(int a=0;a<4;a++) assertEquals(s.get("scenario_sha256").getAsString(),cases.get(4*r+a).get("scenario_sha256"));
            }
            for(var file:m.getAsJsonObject("files").entrySet()) assertEquals(file.getValue().getAsString(),RunOutput.sha256(dir.resolve(file.getKey())));
            var stable=new ArrayList<Map<String,String>>();
            for(var row:cases) { var copy=new TreeMap<>(row); copy.remove("run_id");copy.remove("allocation_wall_ns");stable.add(copy); }
            scientific.add(stable);
        }
        assertEquals(scientific.get(0),scientific.get(1));
    }
    @Test void rejectedWitnessRetainsFailedRunAndCurrentCase() throws Exception {
        assertThrows(IllegalArgumentException.class,()->stress(new StressConfig(100,1,4,2,2,123456,"stress",temp)));
        try(var dirs=Files.list(temp)) {
            var dir=dirs.findFirst().orElseThrow(); var m=json(dir.resolve("run.json"));
            assertEquals("FAILED",m.get("state").getAsString()); assertEquals(1,m.get("attempted_cases").getAsInt());
            assertEquals(1,m.get("failed_cases").getAsInt()); assertEquals(7,m.get("unattempted_cases").getAsInt());
            assertEquals("SCENARIO_REJECTED",m.getAsJsonObject("error").get("code").getAsString());
            assertEquals("HO",json(dir.resolve("progress.json")).getAsJsonObject("current_case").get("algorithm").getAsString());
            assertFalse(Files.exists(dir.resolve("analysis/summary.csv")));
            assertEquals("SCENARIO_REJECTED",rows(dir,"raw/cases.csv",CASE).get(0).get("error_code"));
        }
    }
}
