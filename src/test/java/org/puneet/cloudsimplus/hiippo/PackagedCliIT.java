package org.puneet.cloudsimplus.hiippo;

import com.google.gson.JsonParser;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

class PackagedCliIT {
    @TempDir Path temp;
    private Path jar() throws Exception {
        return Path.of(System.getProperty("artifact.path", "target/lattora-2.1.0.jar")).toAbsolutePath();
    }
    private int run(String... args) throws Exception {
        return runWithJvm(List.of(), args);
    }
    private int runWithJvm(List<String> jvmOptions, String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m"));
        command.addAll(jvmOptions);
        command.addAll(List.of("-jar", jar().toString()));
        command.addAll(List.of(args));
        var p = new ProcessBuilder(command).directory(temp.toFile()).redirectErrorStream(true)
            .redirectOutput(temp.resolve("console.txt").toFile()).start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly().waitFor(); fail("CLI did not terminate promptly"); }
        return p.exitValue();
    }
    private void validate(Path directory, String validator) throws Exception {
        var log = temp.resolve("validator-console.txt");
        var process = new ProcessBuilder("python3", Path.of("scripts", validator).toAbsolutePath().toString(), directory.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly().waitFor(); fail("Output validator did not terminate promptly"); }
        assertEquals(0, process.exitValue(), Files.readString(log));
        assertTrue(Files.readString(log).startsWith("VALID"), Files.readString(log));
        Files.delete(log);
    }
    private List<Map<String,String>> scientificRows(Path directory, String relative) throws Exception {
        try(var reader=Files.newBufferedReader(directory.resolve(relative));
            var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            var rows = new ArrayList<Map<String,String>>();
            for(var record:parser) {
                var row=new LinkedHashMap<>(record.toMap());
                row.remove("run_id"); row.remove("allocation_wall_ns");
                rows.add(row);
            }
            return rows;
        }
    }
    @Test void informationalCommandsHaveNoSideEffects() throws Exception {
        for (var args : List.of(new String[]{}, new String[]{"--help"}, new String[]{"--version"})) {
            assertEquals(0, run(args));
            if (args.length == 0 || args[0].equals("--help")) {
                var help=Files.readString(temp.resolve("console.txt"));
                assertTrue(help.contains("--profile smoke|explore|research"));
                assertTrue(help.contains("--profile stress"));
                assertTrue(help.contains("./run-stress.sh --help"));
                assertTrue(help.contains("--profile scenario"));
            }
            try(var paths = Files.list(temp)) { assertEquals(List.of("console.txt"), paths.map(p -> p.getFileName().toString()).toList()); }
        }
    }
    @Test void artifactIncludesRuntimeAndProvenance() throws Exception {
        try (var jar = new JarFile(jar().toFile())) {
            assertNotNull(jar.getEntry("org/apache/commons/math3/stat/inference/TTest.class"));
            assertNotNull(jar.getEntry("META-INF/LICENSE.txt"));
            assertNotNull(jar.getEntry("META-INF/NOTICE"));
            for(var dependency:Map.of("ch.qos.logback/logback-core","1.6.4", "ch.qos.logback/logback-classic","1.6.4",
                    "org.slf4j/slf4j-api","2.0.20", "org.apache.commons/commons-lang3","3.18.0").entrySet()) {
                var properties=new Properties();
                var entry=jar.getEntry("META-INF/maven/"+dependency.getKey()+"/pom.properties");
                assertNotNull(entry,dependency.getKey());
                try(var input=jar.getInputStream(entry)) { properties.load(input); }
                assertEquals(dependency.getValue(),properties.getProperty("version"),dependency.getKey());
            }
            assertEquals(Files.readString(Path.of("src/main/resources/logback.xml")), new String(jar.getInputStream(jar.getEntry("logback.xml")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            var a = jar.getManifest().getMainAttributes();
            assertEquals("org.puneet.cloudsimplus.hiippo.App", a.getValue("Main-Class"));
            assertEquals(javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(Path.of("pom.xml").toFile()).getDocumentElement().getElementsByTagName("version").item(0).getTextContent(),
                a.getValue("Implementation-Version"));
            assertTrue(a.getValue("Git-Revision").matches("[a-f0-9]{40}"));
            assertTrue(Set.of("true", "false").contains(a.getValue("Git-Dirty")));
        }
    }
    @Test void artifactRetainsDistinctLicenseTextsAndInventory() throws Exception {
        try(var jar=new JarFile(jar().toFile())) {
            var inventoryEntry=jar.getEntry("META-INF/third-party/DEPENDENCIES.txt");
            assertNotNull(inventoryEntry);
            var inventory = new String(jar.getInputStream(inventoryEntry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(Files.readString(Path.of("src/main/resources/META-INF/third-party/DEPENDENCIES.txt")),inventory);
            assertEquals(12,inventory.lines().filter(l->l.matches("[a-z].*:[^ ]+ \\|.*")).count());
            assertTrue(inventory.contains("org.cloudsimplus:cloudsimplus:8.5.7 | GPL-3.0"));
            assertTrue(inventory.contains("ch.qos.logback:logback-core:1.6.4 | EPL-2.0 OR LGPL-2.1-only"));
            for (var license : Map.of("GPL-3.0.txt", "GNU GENERAL PUBLIC LICENSE", "EPL-2.0.txt", "Eclipse Public License", "LGPL-2.1.txt", "GNU LESSER GENERAL PUBLIC LICENSE", "Apache-2.0.txt", "Apache License", "SLF4J-MIT.txt", "Permission is hereby granted", "Lombok-MIT.txt", "Permission is hereby granted").entrySet()) {
                var entry = jar.getEntry("META-INF/third-party/" + license.getKey());
                assertNotNull(entry);
                assertTrue(new String(jar.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains(license.getValue()));
            }
            try(var files=Files.list(Path.of("src/main/resources/META-INF/third-party"))) {
                for(var file:files.toList()) {
                    var entry=jar.getEntry("META-INF/third-party/"+file.getFileName());
                    assertNotNull(entry);
                    assertArrayEquals(Files.readAllBytes(file),jar.getInputStream(entry).readAllBytes());
                }
            }
        }
    }
    @Test void artifactExcludesRetiredExecutionPathsAndConfiguration() throws Exception {
        try(var archive=new JarFile(jar().toFile())) {
            for(var retired:List.of("algorithm", "baseline", "exceptions", "policy", "simulation", "statistical", "util")) {
                String prefix="org/puneet/cloudsimplus/hiippo/"+retired+"/";
                assertFalse(archive.stream().anyMatch(entry->entry.getName().startsWith(prefix)),prefix);
            }
            assertNull(archive.getEntry("config.properties"));
            assertNull(archive.getEntry("algorithm_parameters.properties"));
        }
    }
    @Test void invalidArgumentsNeverCreateOutput() throws Exception {
        for(var args : List.of(new String[]{"--unknown"}, new String[]{"--profile"},
                new String[]{"--profile","smoke","--profile","smoke"}, new String[]{"--debug"},
                new String[]{"--help","--profile","smoke"}, new String[]{"--profile","bad"},
                new String[]{"--profile","smoke","--debug","--debug"})) assertEquals(2, run(args));
        assertFalse(Files.exists(temp.resolve("results")));
    }
    @Test void realSmokeCompletesWithUniqueDurableManifest() throws Exception {
        for (int i=0;i<2;i++) assertEquals(0, run("--profile","smoke"));
        try(var paths=Files.list(temp)) { assertEquals(Set.of("console.txt","results"),new HashSet<>(paths.map(p->p.getFileName().toString()).toList())); }
        try(var dirs=Files.list(temp.resolve("results"))) {
            var runs=dirs.toList(); assertEquals(2,runs.size());
            for(var dir:runs) {
                validate(dir, "statistics_validator.py");
                var m=JsonParser.parseString(Files.readString(dir.resolve("run.json"))).getAsJsonObject();
                assertEquals("COMPLETE",m.get("state").getAsString());
                var libraries=m.getAsJsonObject("library_versions");
                assertEquals("1.6.4",libraries.get("logback").getAsString());
                assertEquals("2.0.20",libraries.get("slf4j").getAsString());
                assertEquals("3.18.0",libraries.get("commons_lang").getAsString());
                assertTrue(m.get("error").isJsonNull());
                assertEquals(4,m.get("expected_cases").getAsInt());
                assertEquals(4,m.get("attempted_cases").getAsInt());
                assertEquals(4,m.get("successful_cases").getAsInt());
                assertEquals(0,m.get("unattempted_cases").getAsInt());
                assertEquals(4,m.getAsJsonArray("cases").size());
                assertTrue(m.get("artifact_sha256").getAsString().matches("[a-f0-9]{64}"));
                assertEquals(org.puneet.cloudsimplus.hiippo.runtime.RunOutput.sha256(jar()),m.get("artifact_sha256").getAsString());
                try(var packaged=new JarFile(jar().toFile())) {
                    var attributes=packaged.getManifest().getMainAttributes();
                    assertEquals(attributes.getValue("Git-Revision"),m.get("git_revision").getAsString());
                    assertEquals(attributes.getValue("Git-Dirty"),m.get("git_dirty").getAsString());
                    assertEquals(attributes.getValue("Implementation-Version"),m.get("source_version").getAsString());
                }
                assertEquals(System.getProperty("java.runtime.version"),m.get("java_version").getAsString());
                assertTrue(Files.isRegularFile(dir.resolve("effective.properties")));
                assertTrue(Files.isRegularFile(dir.resolve("logs/run.log")));
                assertEquals(5,Files.readAllLines(dir.resolve("raw/main_results.csv")).size());
                assertEquals(41,Files.readAllLines(dir.resolve("raw/placements.csv")).size());
                assertEquals(261,Files.readAllLines(dir.resolve("raw/optimizer_trace.csv")).size());
                assertFalse(Files.exists(dir.resolve("analysis")));
            }
        }
    }
    @Test void exploreRunsAllFortyCasesAndCompletesWithDescriptiveAnalysis() throws Exception {
        assertEquals(0,run("--profile","explore"));
        try(var paths=Files.list(temp.resolve("results"))) {
            var dir=paths.findFirst().orElseThrow(); var m=JsonParser.parseString(Files.readString(dir.resolve("run.json"))).getAsJsonObject();
            assertEquals("COMPLETE",m.get("state").getAsString());
            assertTrue(m.get("error").isJsonNull());
            assertEquals(40,m.get("successful_cases").getAsInt()); assertEquals(0,m.get("unattempted_cases").getAsInt());
            assertEquals(10,m.getAsJsonArray("specifications").size());
            assertEquals(41,Files.readAllLines(dir.resolve("raw/main_results.csv")).size());
            assertEquals(1201,Files.readAllLines(dir.resolve("raw/placements.csv")).size());
            assertEquals(24401,Files.readAllLines(dir.resolve("raw/optimizer_trace.csv")).size());
            assertEquals(9,Files.readAllLines(dir.resolve("analysis/scenario_summary.csv")).size());
            assertTrue(Files.isRegularFile(dir.resolve("analysis/report.md")));
            validate(dir, "statistics_validator.py");
        }
    }
    @Test void invalidOutputIsRuntimeFailure() throws Exception {
        Files.writeString(temp.resolve("file"),"occupied");
        assertEquals(1,run("--profile","smoke","--output-dir","file"));
    }
    @Test void stressDispatchPreservesValidationAndIsolatesFrozenProfile() throws Exception {
        var args=new String[]{"--profile","stress","--vms","10","--hosts","3","--population","4","--iterations","2","--replications","2","--seed","123456","--experiment-phase","stress","--output-dir","stress results"};
        assertEquals(0,run(args));
        try(var dirs=Files.list(temp.resolve("stress results"))) {
            var directory=dirs.findFirst().orElseThrow();
            var manifest=JsonParser.parseString(Files.readString(directory.resolve("run.json"))).getAsJsonObject();
            assertEquals("COMPLETE",manifest.get("state").getAsString()); assertEquals(8,manifest.get("successful_cases").getAsInt());
            assertTrue(manifest.get("artifact_sha256").getAsString().matches("[a-f0-9]{64}"));
            validate(directory, "stress_validator.py");
        }
        var duplicate=new ArrayList<>(List.of(args)); duplicate.addAll(List.of("--profile","stress"));
        assertEquals(2,run(duplicate.toArray(String[]::new)));
        assertEquals(2,run("--profile","research","--vms","10"));
        args[11]="2147483647"; assertEquals(2,run(args));
    }
    @Test void scenarioDispatchRunsTheKnownAnswerAndRejectsInvalidConfiguration() throws Exception {
        Files.writeString(temp.resolve("tight.json"),"{\"config_version\":1,\"name\":\"known-answer-tight\",\"master_seed\":123456,\"replications\":1,"
            +"\"algorithms\":[\"HO\",\"GA\",\"FirstFit\",\"BestFit\"],\"optimizer\":{\"population\":10,\"iterations\":4},"
            +"\"hosts\":[{\"count\":2,\"pes\":2,\"mips_per_pe\":3000,\"ram_mib\":8192,\"bw_mbps\":2000,\"storage_mib\":20000,\"idle_w\":100,\"max_w\":200}],"
            +"\"vms\":{\"count\":2,\"pes\":[2],\"mips_per_pe\":[3000],\"ram_mib\":[8192],\"bw_mbps\":[2000],\"storage_mib\":[20000]},"
            +"\"workload\":{\"length_seconds\":{\"min\":60,\"max\":60},\"utilization\":1.0,\"release_seconds\":0.0}}");
        assertEquals(0,run("--profile","scenario","--config","tight.json","--output-dir","scenario results"));
        try(var dirs=Files.list(temp.resolve("scenario results"))) {
            var directory=dirs.findFirst().orElseThrow();
            var manifest=JsonParser.parseString(Files.readString(directory.resolve("run.json"))).getAsJsonObject();
            assertEquals("COMPLETE",manifest.get("state").getAsString());
            assertEquals("static_scenario_v1",manifest.get("protocol").getAsString());
            assertEquals(1,manifest.get("scenario_schema_version").getAsInt());
            assertEquals(4,manifest.get("successful_cases").getAsInt());
            assertTrue(manifest.get("artifact_sha256").getAsString().matches("[a-f0-9]{64}"));
            for(var row:scientificRows(directory,"raw/cases.csv")) assertEquals("400.0",row.get("objective_w"));
            validate(directory,"scenario_validator.py");
        }
        Files.writeString(temp.resolve("unknown.json"),Files.readString(temp.resolve("tight.json")).replace("\"replications\":1,","\"replications\":1,\"bogus\":2,"));
        Files.writeString(temp.resolve("utilization.json"),Files.readString(temp.resolve("tight.json")).replace("\"utilization\":1.0","\"utilization\":0.5"));
        Files.writeString(temp.resolve("version.json"),Files.readString(temp.resolve("tight.json")).replace("\"config_version\":1","\"config_version\":2"));
        Files.writeString(temp.resolve("optimizer.json"),Files.readString(temp.resolve("tight.json")).replace("\"optimizer\":{\"population\":10,\"iterations\":4}","\"optimizer\":{\"population\":10,\"iterations\":4}").replace("\"algorithms\":[\"HO\",\"GA\",\"FirstFit\",\"BestFit\"]","\"algorithms\":[\"FirstFit\",\"BestFit\"]"));
        for(var invalid:List.of("unknown.json","utilization.json","version.json","optimizer.json"))
            assertEquals(2,run("--profile","scenario","--config",invalid,"--output-dir","invalid results"),invalid);
        assertEquals(1,run("--profile","scenario","--config","missing.json","--output-dir","invalid results"));
        assertEquals(2,run("--profile","scenario","--config","tight.json","--bogus","1","--output-dir","invalid results"));
        assertEquals(2,run("--profile","scenario","--output-dir","invalid results"));
        assertFalse(Files.exists(temp.resolve("invalid results")));
    }
    @Test void invalidPropertyOverlayIsConfigFailureWithoutOutput() throws Exception {
        Files.writeString(temp.resolve("config.properties"),"master.seed=1\nmaster\\u002eseed=2\n");
        assertEquals(2,run("--profile","smoke","--config","config.properties"));
        assertFalse(Files.exists(temp.resolve("results")));
    }
    @Test void malformedUtf8IsConfigurationErrorButMissingFileIsIoError() throws Exception {
        Files.write(temp.resolve("bad.properties"),new byte[]{(byte)0xff});
        assertEquals(2,run("--profile","smoke","--config","bad.properties"));
        assertEquals(1,run("--profile","smoke","--config","missing.properties"));
        assertFalse(Files.exists(temp.resolve("results")));
    }
    @Test void packagedSerialAndParallelRunsPreserveEveryScientificRow() throws Exception {
        var directories = new ArrayList<Path>();
        for(int workers:List.of(1,3)) {
            String output="workers " + workers;
            assertEquals(0,runWithJvm(List.of("-Dcloudsim.workers="+workers),"--profile","smoke","--output-dir",output));
            try(var paths=Files.list(temp.resolve(output))) { directories.add(paths.findFirst().orElseThrow()); }
            validate(directories.getLast(), "statistics_validator.py");
            var manifest=JsonParser.parseString(Files.readString(directories.getLast().resolve("run.json"))).getAsJsonObject();
            assertEquals(workers, manifest.get("execution_workers").getAsInt());
        }
        for(String name:List.of("raw/main_results.csv","raw/placements.csv","raw/optimizer_trace.csv")) {
            var serial=scientificRows(directories.getFirst(),name);
            assertFalse(serial.isEmpty(), name);
            assertEquals(serial,scientificRows(directories.getLast(),name),name);
        }
        assertArrayEquals(Files.readAllBytes(directories.getFirst().resolve("effective.properties")),
                          Files.readAllBytes(directories.getLast().resolve("effective.properties")));
    }
    @Test void packagedInvalidWorkerSettingsFailBeforeOutputCreation() throws Exception {
        for(String workers:List.of("0","33","-1","1.5","bad")) {
            assertEquals(2,runWithJvm(List.of("-Dcloudsim.workers="+workers),"--profile","smoke"),workers);
            assertFalse(Files.exists(temp.resolve("results")),workers);
        }
    }
}
