package org.puneet.cloudsimplus.hiippo.runtime;

import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioConfigTest {
    @TempDir Path temp;
    private static final String NAME="[a-z][a-z0-9._-]{0,63}";

    static Map<String,Object> tight() {
        var document=new LinkedHashMap<String,Object>();
        document.put("config_version",1); document.put("name","known-answer-tight"); document.put("master_seed",123456);
        document.put("replications",1); document.put("algorithms",List.of("HO","GA","FirstFit","BestFit"));
        document.put("optimizer",new LinkedHashMap<String,Object>(Map.of("population",10,"iterations",4)));
        var host=new LinkedHashMap<String,Object>();
        host.put("count",2); host.put("pes",2); host.put("mips_per_pe",3000); host.put("ram_mib",8192);
        host.put("bw_mbps",2000); host.put("storage_mib",20000); host.put("idle_w",100); host.put("max_w",200);
        document.put("hosts",new ArrayList<>(List.of(host)));
        var vms=new LinkedHashMap<String,Object>();
        vms.put("count",2); vms.put("pes",new ArrayList<>(List.of(2))); vms.put("mips_per_pe",new ArrayList<>(List.of(3000)));
        vms.put("ram_mib",new ArrayList<>(List.of(8192))); vms.put("bw_mbps",new ArrayList<>(List.of(2000)));
        vms.put("storage_mib",new ArrayList<>(List.of(20000)));
        document.put("vms",vms);
        var workload=new LinkedHashMap<String,Object>();
        workload.put("length_seconds",new LinkedHashMap<String,Object>(Map.of("min",60,"max",60))); workload.put("utilization",1.0); workload.put("release_seconds",0.0);
        document.put("workload",workload);
        return document;
    }
    private static byte[] bytes(Map<String,Object> document) {
        return (new GsonBuilder().setPrettyPrinting().create().toJson(document)+"\n").getBytes(StandardCharsets.UTF_8);
    }
    private static ScenarioConfig resolve(Map<String,Object> document) { return ScenarioConfig.resolve(bytes(document)); }
    private static String message(Map<String,Object> document) {
        return assertThrows(IllegalArgumentException.class,()->resolve(document)).getMessage();
    }
    private void rejects(String expected,java.util.function.Consumer<Map<String,Object>> mutate) {
        var document=tight(); mutate.accept(document); assertEquals(expected,message(document));
    }
    private void rejectsText(String expectedPrefix,String text) {
        var error=assertThrows(IllegalArgumentException.class,()->ScenarioConfig.resolve(text.getBytes(StandardCharsets.UTF_8)));
        assertTrue(error.getMessage().startsWith(expectedPrefix),error.getMessage());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> host(Map<String,Object> document) {
        return (Map<String,Object>)((List<?>)document.get("hosts")).get(0);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Map<String,Object> document,String key) {
        return (Map<String,Object>)document.get(key);
    }
    private static void algorithms(Map<String,Object> document,String... names) {
        document.put("algorithms",new ArrayList<>(List.of(names)));
    }

    @Test void everyDeclarationRuleProducesItsExactMessage() {
        rejects("config_version must be the integer 1",d->d.put("config_version",2));
        rejects("config_version must be the integer 1",d->d.remove("config_version"));
        rejects("config_version must be the integer 1",d->d.put("config_version","1"));
        rejects("name must be a string matching "+NAME,d->d.put("name","Bad Name"));
        rejects("name must be a string matching "+NAME,d->d.put("name","1leading"));
        rejects("name must be a string matching "+NAME,d->d.remove("name"));
        rejects("master_seed must be a signed 64-bit integer",d->d.put("master_seed",1.5));
        rejects("master_seed must be a signed 64-bit integer",d->d.put("master_seed","123456"));
        rejects("master_seed must be a signed 64-bit integer",d->d.put("master_seed",new java.math.BigDecimal("9223372036854775808")));
        rejects("master_seed must be a signed 64-bit integer",d->d.remove("master_seed"));
        rejects("replications must be an integer from 1 to 10000",d->d.put("replications",0));
        rejects("replications must be an integer from 1 to 10000",d->d.put("replications",10001));
        rejects("algorithms must be a non-empty array without duplicates, drawn from HO,GA,FirstFit,BestFit",d->algorithms(d));
        rejects("algorithms must be a non-empty array without duplicates, drawn from HO,GA,FirstFit,BestFit",d->algorithms(d,"HO","HO"));
        rejects("algorithms must be a non-empty array without duplicates, drawn from HO,GA,FirstFit,BestFit",d->algorithms(d,"Random"));
        rejects("optimizer is required when HO or GA is configured",d->d.remove("optimizer"));
        rejects("optimizer is required when HO or GA is configured",d->d.put("optimizer",5));
        rejects("optimizer is unused: algorithms exclude HO and GA",d->algorithms(d,"FirstFit","BestFit"));
        rejects("optimizer.population must be an even integer of at least 2",d->map(d,"optimizer").put("population",3));
        rejects("optimizer.population must be an even integer of at least 2",d->map(d,"optimizer").put("population",0));
        rejects("optimizer.iterations must be an integer of at least 1",d->map(d,"optimizer").put("iterations",0));
        rejects("optimizer.iterations must be an integer of at least 1",d->map(d,"optimizer").remove("iterations"));
        rejects("evaluation budget must not exceed 2147483647",d->{
            map(d,"optimizer").put("population",2147483646); map(d,"optimizer").put("iterations",2147483647); });
        rejects("hosts must be an array of at most 16 classes",d->{
            var classes=(List<Object>)d.get("hosts"); while(classes.size()<17) classes.add(new LinkedHashMap<String,Object>((Map<String,Object>)classes.get(0))); });
        rejects("hosts must be an array of at most 16 classes",d->d.put("hosts",Map.of("count",1)));
        rejects("total host count must be from 1 to 100000",d->d.put("hosts",new ArrayList<>()));
        rejects("hosts[0].count must be an integer from 1 to 100000",d->host(d).put("count",0));
        rejects("hosts[0].pes must be an integer from 1 to 2147483647",d->host(d).put("pes",0));
        rejects("hosts[0].mips_per_pe must be an integer from 1 to 2147483647",d->host(d).remove("mips_per_pe"));
        rejects("hosts[0].idle_w must be an integer from 0 to 2147483647",d->host(d).put("idle_w",-1));
        rejects("hosts[0].max_w must be an integer from 100 to 2147483647",d->host(d).put("max_w",99));
        rejects("vms.count must be an integer from 1 to 100000",d->map(d,"vms").put("count",0));
        rejects("vms.count must be an integer from 1 to 100000",d->d.remove("vms"));
        rejects("vms.ram_mib must be a non-empty non-decreasing integer list or a min/max integer range",d->map(d,"vms").put("ram_mib",new ArrayList<>()));
        rejects("vms.ram_mib must be a non-empty non-decreasing integer list or a min/max integer range",d->map(d,"vms").put("ram_mib",new ArrayList<>(List.of(2048,1024))));
        rejects("vms.ram_mib must be a non-empty non-decreasing integer list or a min/max integer range",d->map(d,"vms").put("ram_mib",new LinkedHashMap<String,Object>(Map.of("min",30,"max",10))));
        rejects("vms.ram_mib must be a non-empty non-decreasing integer list or a min/max integer range",d->map(d,"vms").put("ram_mib",new LinkedHashMap<String,Object>(Map.of("min",1024))));
        rejects("vms.ram_mib must be a non-empty non-decreasing integer list or a min/max integer range",d->map(d,"vms").put("ram_mib","1024"));
        rejects("unknown field: vms.ram_mib.minimum",d->map(d,"vms").put("ram_mib",new LinkedHashMap<String,Object>(Map.of("minimum",1024,"max",2048))));
        rejects("vms.pes must not declare more than 4096 values",d->{
            var values=new ArrayList<Object>(); for(int i=0;i<4097;i++) values.add(1); map(d,"vms").put("pes",values); });
        rejects("vms.mips_per_pe value 1500 must be a multiple of 8: the 0.125 s event interval must stay exact",d->map(d,"vms").put("mips_per_pe",new ArrayList<>(List.of(1500))));
        rejects("vms.pes value 4 exceeds every host class pes (2)",d->map(d,"vms").put("pes",new ArrayList<>(List.of(4))));
        rejects("workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range from 1 to 1440",d->map(d,"workload").put("length_seconds",new ArrayList<>()));
        rejects("workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range from 1 to 1440",d->map(d,"workload").put("length_seconds",new ArrayList<>(List.of(0))));
        rejects("workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range from 1 to 1440",d->map(d,"workload").put("length_seconds",new ArrayList<>(List.of(1441))));
        rejects("workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range from 1 to 1440",d->map(d,"workload").put("length_seconds",new LinkedHashMap<String,Object>(Map.of("min",120,"max",30))));
        rejects("workload.utilization must be 1.0: constant full utilization is the only simulated model",d->map(d,"workload").put("utilization",0.5));
        rejects("workload.utilization must be 1.0: constant full utilization is the only simulated model",d->map(d,"workload").remove("utilization"));
        rejects("workload.release_seconds must be 0.0: a single simultaneous release is the only simulated model",d->map(d,"workload").put("release_seconds",1.0));
        rejects("workload.release_seconds must be 0.0: a single simultaneous release is the only simulated model",d->map(d,"workload").remove("release_seconds"));
        rejects("vms.count multiplied by total host count must not exceed 2147483647",d->{
            map(d,"vms").put("count",100000); host(d).put("count",100000); host(d).put("ram_mib",2147483647); });
    }
    @Test void malformedDocumentsAreRejectedBeforeAnyValueRule() {
        rejects("unknown field: hosts[0].bogus",d->host(d).put("bogus",1));
        rejects("unknown field: vms.pes.bogus",d->map(d,"vms").put("pes",new LinkedHashMap<String,Object>(Map.of("min",2,"max",2,"bogus",1))));
        rejects("unknown field: vms.bogus",d->map(d,"vms").put("bogus",1));
        rejects("unknown field: optimizer.bogus",d->map(d,"optimizer").put("bogus",1));
        rejects("unknown field: workload.bogus",d->map(d,"workload").put("bogus",1));
        rejectsText("unknown field: bogus",new String(bytes(tight()),StandardCharsets.UTF_8).replace("\"name\": \"known-answer-tight\",","\"name\": \"known-answer-tight\",\n  \"bogus\": 3,"));
        rejectsText("unknown field: hosts[0].bogus",new String(bytes(tight()),StandardCharsets.UTF_8).replace("\"count\": 2,","\"count\": 2,\n    \"bogus\": 1,"));
        rejectsText("duplicate field: name",new String(bytes(tight()),StandardCharsets.UTF_8).replace("\"name\": \"known-answer-tight\",","\"name\": \"known-answer-tight\",\n  \"name\": \"again\","));
        rejectsText("invalid JSON: ","{\"config_version\": 1, \"master_seed\": NaN}");
        rejectsText("invalid JSON: ","{\"config_version\": 1}{}");
        rejectsText("invalid JSON: ","[1, 2]");
        rejectsText("invalid JSON: ","{\"config_version\": 1,}");
        var error=assertThrows(IllegalArgumentException.class,()->ScenarioConfig.resolve("{\"name\": \"\u00f8\",}".getBytes(StandardCharsets.ISO_8859_1)));
        assertTrue(error.getMessage().startsWith("config must contain valid UTF-8"),error.getMessage());
    }
    @Test void resolutionCanonicalizesRangesAlgorithmsAndDefaults() {
        var document=tight();
        document.remove("algorithms");
        map(document,"vms").put("ram_mib",new LinkedHashMap<String,Object>(Map.of("min",1024,"max",1027)));
        map(document,"workload").put("length_seconds",new LinkedHashMap<String,Object>(Map.of("min",30,"max",32)));
        var config=resolve(document);
        assertEquals(List.of("HO","GA","FirstFit","BestFit"),config.algorithms());
        assertEquals(List.of(1024L,1025L,1026L,1027L),config.generationSpec().vms().ramMiB());
        assertEquals(List.of(30,31,32),config.generationSpec().workload().lengthSeconds());
        assertEquals(Integer.valueOf(10),config.population()); assertEquals(Integer.valueOf(4),config.iterations());
        assertTrue(config.canonicalJson().replaceAll("\\s+"," ").contains("\"length_seconds\": [ 30, 31, 32 ]"),config.canonicalJson());
        assertEquals(64,config.configSha256().length());
        assertArrayEquals(bytes(document),config.request().bytes());

        var ordered=tight(); algorithms(ordered,"BestFit","HO","FirstFit");
        assertEquals(List.of("HO","FirstFit","BestFit"),resolve(ordered).algorithms());

        var weighted=tight(); map(weighted,"workload").put("length_seconds",new ArrayList<>(List.of(30,30,60)));
        assertEquals(List.of(30,30,60),resolve(weighted).generationSpec().workload().lengthSeconds());

        var baselines=tight(); algorithms(baselines,"BestFit","FirstFit"); baselines.remove("optimizer");
        var only=resolve(baselines);
        assertNull(only.population()); assertEquals(2,only.expectedCases()); assertEquals(2,only.expectedEvaluations());
        assertEquals(0,only.evaluationBudget());
        assertFalse(only.effective().containsKey("evaluation.budget"));
        assertFalse(only.effective().containsKey("optimizer.population"));
        assertFalse(only.canonicalJson().contains("\"optimizer\""));
    }
    @Test void effectiveMapAndCanonicalKeyOrderAreComplete() {
        var config=resolve(tight());
        var expected=new TreeMap<String,String>();
        expected.put("profile","scenario"); expected.put("experiment.kind","static_scenario"); expected.put("scenario.schema.version","1");
        expected.put("experiment.phase","scenario"); expected.put("config.name","known-answer-tight"); expected.put("config.sha256",config.configSha256());
        expected.put("master.seed","123456"); expected.put("replication.count","1"); expected.put("algorithms","HO,GA,FirstFit,BestFit");
        expected.put("vm.count","2"); expected.put("host.count","2"); expected.put("host.classes","1");
        expected.put("expected.cases","4"); expected.put("expected.evaluations","262"); expected.put("evaluation.budget","130");
        expected.put("optimizer.population","10"); expected.put("optimizer.iterations","4");
        expected.put("utilization.model","constant_full"); expected.put("utilization.value","1.0");
        expected.put("workload.length.seconds","60,60");
        assertEquals(expected,config.effective());
        assertEquals(130,config.evaluationBudget());
        assertEquals(2,config.vmCount()); assertEquals(2,config.hostCount()); assertEquals(1,config.hostClassCount());
        assertEquals("CONSTRUCTIBLE",config.feasibility().getFirst().kind());
        assertNull(config.feasibility().getFirst().reason());
        var document=new LinkedHashMap<String,Object>(tight());
        document.put("config_version",1); document.put("name","heterogeneous-hosts");
        var canonical=resolve(document).canonicalJson();
        var order=List.of("config_version","name","master_seed","replications","algorithms","optimizer","population","iterations","hosts","count","pes","mips_per_pe","ram_mib","bw_mbps","storage_mib","idle_w","max_w","vms","workload","length_seconds","utilization","release_seconds");
        int previous=-1;
        for(String key:order) {
            int index=canonical.indexOf("\""+key+"\"");
            assertTrue(index>previous,key+" must follow its predecessor in the canonical document");
            previous=index;
        }
        assertTrue(canonical.contains("\"utilization\": 1.0"),canonical);
        assertTrue(canonical.contains("\"release_seconds\": 0.0"),canonical);
    }
    @Test void knownAnswerPresetsClassifyAsDocumented() {
        assertEquals("CONSTRUCTIBLE",resolve(tight()).feasibility().getFirst().kind());
        var infeasible=tight(); infeasible.put("name","known-answer-infeasible");
        host(infeasible).put("count",1); host(infeasible).put("pes",4); host(infeasible).put("ram_mib",4096);
        host(infeasible).put("bw_mbps",1000); host(infeasible).put("storage_mib",10000);
        map(infeasible,"vms").put("pes",new ArrayList<>(List.of(1)));
        map(infeasible,"vms").put("ram_mib",new ArrayList<>(List.of(4096)));
        map(infeasible,"vms").put("bw_mbps",new ArrayList<>(List.of(1000)));
        map(infeasible,"vms").put("storage_mib",new ArrayList<>(List.of(10000)));
        var result=resolve(infeasible).feasibility().getFirst();
        assertEquals("INFEASIBLE_PROVED",result.kind());
        assertEquals("replication 0 total ram_mib demand 8192 exceeds total host ram_mib capacity 4096",result.reason());
        var proved=resolve(infeasible);
        var thrown=assertThrows(ScenarioInfeasible.class,()->proved.specFor(new Profile.CaseKey("scenario","known-answer-infeasible",0,"HO",10,4)));
        assertEquals("SCENARIO_INFEASIBLE",thrown.code()); assertEquals(result.reason(),thrown.getMessage());
    }
    @Test void unreadableAndMissingFilesAreReportedAsIo() {
        var missing=temp.resolve("absent.json");
        var error=assertThrows(IOException.class,()->ScenarioConfig.load(missing));
        assertEquals("config file is missing or unreadable: "+missing,error.getMessage());
        var directory=assertThrows(IOException.class,()->ScenarioConfig.load(temp));
        assertEquals("config file is missing or unreadable: "+temp,directory.getMessage());
        var file=temp.resolve("config.json");
        assertDoesNotThrow(()->Files.write(file,bytes(tight())));
        assertDoesNotThrow(()->ScenarioConfig.load(file).specFor(new Profile.CaseKey("scenario","known-answer-tight",0,"BestFit",null,null)));
        var truncated=temp.resolve("short.json");
        assertDoesNotThrow(()->Files.writeString(truncated,"{\"config_version\": 1"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->ScenarioConfig.load(truncated)).getMessage().startsWith("invalid JSON: "));
    }
}
