package org.puneet.cloudsimplus.hiippo.runtime;

import com.google.gson.*;
import ch.qos.logback.classic.*;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.rolling.*;
import ch.qos.logback.core.util.FileSize;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import org.apache.commons.csv.*;
import org.puneet.cloudsimplus.hiippo.scenario.*;
import org.puneet.cloudsimplus.hiippo.placement.*;

/** Owns one create-new run directory and atomic terminal manifest publication. */
public final class RunOutput implements AutoCloseable {
    private static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private final Path directory;
    private final Map<String,Object> manifest=new LinkedHashMap<>();
    private static final String RESULT_HEADER="schema_version,run_id,phase,scenario,replication,algorithm,population,iterations,scenario_seed,workload_seed,optimizer_seed,scenario_sha256,status,error_code,error_message,vm_count,host_count,evaluations,objective_w,energy_j,energy_kwh,sla_violations,sla_rate,completed_cloudlets,failed_cloudlets,censored_cloudlets,horizon_s,allocation_wall_ns";
    private static final String PLACEMENT_HEADER="schema_version,run_id,phase,scenario,replication,algorithm,population,iterations,vm_id,host_id";
    private static final String TRACE_HEADER="schema_version,run_id,phase,scenario,replication,algorithm,population,iterations,evaluation,iteration,stage,candidate_index,feasible,fitness_w,best_fitness_w";
    private static final CSVFormat CSV=CSVFormat.DEFAULT.builder().setRecordSeparator('\n').build();
    private final Map<String,ScenarioSpec> specifications=new LinkedHashMap<>();
    private final List<Map<String,Object>> caseRows=new ArrayList<>();
    private RunConfig config;
    private int workers;
    private Map<String,String> analysis;
    private Map<String,String> analyzedInputs;
    private int attempted,successful,failed;
    private LoggerContext logging;
    private RunOutput(Path directory) { this.directory=directory; }
    public Path directory() { return directory; }
    int workers() { return workers; }
    public static String version() {
        String value=RunOutput.class.getPackage().getImplementationVersion();
        return value==null?"development (unpackaged)":value;
    }
    public static RunOutput create(RunConfig config) throws Exception {
        int workers=CaseExecutor.workers();
        var effective=config.effective();
        Files.createDirectories(config.outputRoot());
        var output=new RunOutput(Files.createDirectory(config.outputRoot().resolve("run-"+UUID.randomUUID())));
        output.config=config;
        output.workers=workers;
        try {
            var m=output.manifest;
            m.put("schema_version",2); m.put("protocol_version",2); m.put("run_id",output.directory.getFileName().toString());
            m.put("state","RUNNING"); m.put("profile",config.profile().name()); m.put("started_at",Instant.now().toString()); m.put("finished_at",null);
            m.put("master_seed",config.masterSeed()); m.put("source_version",version());
            var artifact=Path.of(RunOutput.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            m.put("artifact_sha256",Files.isRegularFile(artifact)?sha256(artifact):null);
            String revision="unknown"; boolean dirty=true;
            if(Files.isRegularFile(artifact)) try(var jar=new JarFile(artifact.toFile())) {
                var attributes=jar.getManifest().getMainAttributes();
                revision=attributes.getValue("Git-Revision"); dirty=!"false".equals(attributes.getValue("Git-Dirty"));
            }
            m.put("git_revision",revision); m.put("git_dirty",dirty); m.put("java_version",System.getProperty("java.runtime.version"));
            m.put("cloudsim_version","8.5.7"); m.put("library_versions",Map.of("gson","2.12.1","commons_math","3.6.1","commons_csv","1.10.0","commons_lang","3.18.0","logback","1.6.4","slf4j","2.0.20"));
            m.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version")); m.put("arch",System.getProperty("os.arch"));
            m.put("cpu",Map.of("available_processors",Runtime.getRuntime().availableProcessors(),"model",cpuModel()));
            m.put("max_heap_bytes",Runtime.getRuntime().maxMemory()); m.put("effective_config",effective);
            m.put("execution_workers",workers);
            var cases=output.caseRows;
            for(var key:config.profile().cases()) {
                var row=new LinkedHashMap<String,Object>(); row.put("phase",key.phase()); row.put("scenario",key.scenario());
                row.put("replication",key.replication()); row.put("algorithm",key.algorithm()); row.put("population",key.population()); row.put("iterations",key.iterations()); row.put("status",null); cases.add(row);
            }
            m.put("expected_cases",cases.size()); m.put("attempted_cases",0); m.put("successful_cases",0); m.put("failed_cases",0); m.put("unattempted_cases",cases.size());
            m.put("cases",cases); m.put("specifications",List.of()); m.put("files",new TreeMap<String,String>()); m.put("error",null);
            output.writeManifest();
            Files.write(output.directory.resolve("effective.properties"),effectiveBytes(effective),StandardOpenOption.CREATE_NEW);
            m.put("files",Map.of("effective.properties",sha256(output.directory.resolve("effective.properties"))));
            output.configureLogging(config.logLevel());
            Files.createDirectory(output.directory.resolve("raw"));
            Files.writeString(output.directory.resolve("raw/main_results.csv"),RESULT_HEADER+"\n",StandardOpenOption.CREATE_NEW);
            if(config.profile()==Profile.research) Files.writeString(output.directory.resolve("raw/sensitivity_results.csv"),RESULT_HEADER+"\n",StandardOpenOption.CREATE_NEW);
            Files.writeString(output.directory.resolve("raw/placements.csv"),PLACEMENT_HEADER+"\n",StandardOpenOption.CREATE_NEW);
            Files.writeString(output.directory.resolve("raw/optimizer_trace.csv"),TRACE_HEADER+"\n",StandardOpenOption.CREATE_NEW);
            output.writeManifest();
            return output;
        } catch(Exception e) {
            try { output.fail("OUTPUT_ERROR",e.toString()); } catch(Exception secondary) { e.addSuppressed(secondary); }
            output.close(); throw e;
        }
    }
    private static String cpuModel() {
        try(var lines=Files.lines(Path.of("/proc/cpuinfo"))) {
            return lines.filter(l->l.startsWith("model name")).map(l->l.substring(l.indexOf(':')+1).trim()).findFirst().orElse("unknown");
        } catch(IOException e) { return System.getenv().getOrDefault("PROCESSOR_IDENTIFIER","unknown"); }
    }
    private static byte[] effectiveBytes(SortedMap<String,String> effective) {
        var text=new StringBuilder(); effective.forEach((k,v)->text.append(k).append('=').append(v).append('\n'));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
    public void fail(String code,String message) throws IOException {
        manifest.put("state","FAILED"); manifest.put("finished_at",Instant.now().toString());
        manifest.put("error",Map.of("code",code,"message",message));
        if(logging!=null) logging.getLogger(RunOutput.class).error("{}: {}",code,message);
        try { hashFiles(); } finally { writeManifest(); }
    }
    private void hashFiles() throws IOException {
        var hashes=new TreeMap<String,String>();
        var paths=new ArrayList<>(List.of("effective.properties","raw/main_results.csv","raw/sensitivity_results.csv","raw/placements.csv","raw/optimizer_trace.csv"));
        paths.addAll(RunAnalysis.required(config.profile()));
        for(var path:paths)
            if(Files.isRegularFile(directory.resolve(path))) hashes.put(path,sha256(directory.resolve(path)));
        manifest.put("files",hashes);
    }
    private static String workload(String phase,String scenario,int replication) { return phase+":"+scenario+":"+replication; }
    private static String workload(Profile.CaseKey key) { return workload(key.phase(),key.scenario(),key.replication()); }
    private void counts() {
        manifest.put("attempted_cases",attempted); manifest.put("successful_cases",successful); manifest.put("failed_cases",failed);
        manifest.put("unattempted_cases",caseRows.size()-attempted);
    }
    void begin(Profile.CaseKey key) throws IOException {
        if(!"RUNNING".equals(manifest.get("state")) || attempted!=successful+failed || failed!=0
            || !config.profile().cases().get(attempted).equals(key)) throw new IllegalStateException("Invalid case lifecycle");
        attempted++; counts(); writeManifest();
    }
    void addSpecification(ScenarioSpec spec) throws IOException {
        var seed=spec.inputs().seeds(); String key=workload(seed.phase(),seed.scenario(),seed.replication());
        var old=specifications.putIfAbsent(key,spec);
        if(old!=null && !old.equals(spec)) throw new IllegalArgumentException("Paired specification mismatch");
        var rows=new ArrayList<Map<String,Object>>();
        for(var value:specifications.values()) {
            var row=new LinkedHashMap<String,Object>(); row.put("inputs",value.inputs()); row.put("reference_seconds",value.referenceSeconds());
            row.put("censor_s",value.censorSeconds()); row.put("canonical_text",value.canonicalText()); row.put("scenario_sha256",value.fingerprint()); rows.add(row);
        }
        manifest.put("specifications",rows); writeManifest();
    }
    private List<Object> identity(Profile.CaseKey key) {
        return new ArrayList<>(Arrays.asList(2,manifest.get("run_id"),key.phase(),key.scenario(),key.replication(),key.algorithm(),key.population(),key.iterations()));
    }
    private List<Object> resultIdentity(Profile.CaseKey key,ScenarioSpec spec,String status,String code,String message) {
        var row=identity(key);
        row.add(Seeds.derive(config.masterSeed(),key.phase(),key.scenario(),key.replication(),"scenario"));
        row.add(Seeds.derive(config.masterSeed(),key.phase(),key.scenario(),key.replication(),"workload"));
        row.add(key.population()==null?null:Seeds.derive(config.masterSeed(),key.phase(),key.scenario(),key.replication(),"optimizer",key.algorithm()));
        row.add(spec==null?null:spec.fingerprint()); row.add(status); row.add(code); row.add(message);
        int count=switch(key.scenario()) { case "Micro"->10; case "Small"->50; case "Medium"->100; default->throw new IllegalArgumentException("Scenario"); };
        row.add(count); row.add(count==10?3:count/5); return row;
    }
    private static String csv(List<? extends List<?>> rows) throws IOException {
        var text=new StringWriter();
        try(var printer=new CSVPrinter(text,CSV)) { for(var row:rows) printer.printRecord(row); }
        return text.toString();
    }
    private List<List<Object>> traceRows(Profile.CaseKey key,List<Search.Trace> trace) {
        var rows=new ArrayList<List<Object>>(); Double best=null;
        for(var entry:trace) {
            if(entry.accepted() && entry.watts().isPresent() && (best==null || entry.watts().get()<best)) best=entry.watts().get();
            var row=identity(key); row.add(entry.evaluation()); row.add(entry.iteration());
            row.add(entry.phase().equals("CHILD")?"ga_child":entry.phase().toLowerCase(Locale.ROOT));
            row.add(entry.phase().equals("CHILD")?entry.evaluation()-1:entry.member());
            row.add(entry.watts().isPresent()); row.add(entry.watts().orElse(null)); row.add(best); rows.add(row);
        }
        return rows;
    }
    void success(Profile.CaseKey key,ScenarioSpec spec,RunCoordinator.CaseResult result) throws IOException {
        checkRequiredFiles();
        if(!spec.equals(specifications.get(workload(key)))) throw new IllegalArgumentException("Missing paired specification");
        validateResult(spec,key,result);
        var row=resultIdentity(key,spec,"RUN_OK",null,null); var metric=result.metrics();
        row.addAll(Arrays.asList(result.evaluations(),result.objectiveWatts(),metric.energyJ(),metric.energyKwh(),metric.slaViolations(),metric.slaRate(),
            metric.completed(),metric.failed(),metric.censored(),metric.horizonSeconds(),result.allocationNanos()));
        var placements=new ArrayList<List<Object>>();
        for(int vm=0;vm<result.plan().hostIds().size();vm++) { var p=identity(key); p.add(vm); p.add(result.plan().hostIds().get(vm)); placements.add(p); }
        var data=new LinkedHashMap<Path,String>();
        data.put(directory.resolve("raw/placements.csv"),csv(placements));
        data.put(directory.resolve("raw/optimizer_trace.csv"),csv(traceRows(key,result.trace())));
        data.put(directory.resolve("raw/"+key.phase()+"_results.csv"),csv(List.of(row)));
        // Restore all append offsets on any write failure, so a failed case has no placement rows.
        var offsets=new LinkedHashMap<Path,Long>();
        var caseRow=caseRows.get(attempted-1); boolean marked=false;
        try {
            for(var file:data.keySet()) {
                if(!Files.isRegularFile(file)) throw new IOException("Required raw file missing: "+file.getFileName());
                offsets.put(file,Files.size(file));
            }
            for(var file:data.entrySet()) Files.writeString(file.getKey(),file.getValue(),StandardCharsets.UTF_8,StandardOpenOption.APPEND);
            caseRow.put("status","RUN_OK"); caseRow.put("scenario_sha256",spec.fingerprint());
            caseRow.put("release_s",metric.releaseSeconds()); successful++; marked=true; counts(); writeManifest();
        } catch(IOException error) {
            if(marked) { caseRow.put("status",null); caseRow.remove("scenario_sha256"); caseRow.remove("release_s"); successful--; counts(); }
            for(var file:offsets.entrySet()) try(var channel=FileChannel.open(file.getKey(),StandardOpenOption.WRITE)) { channel.truncate(file.getValue()); }
            catch(IOException rollback) { error.addSuppressed(rollback); }
            throw error;
        }
    }
    void caseFailed(Profile.CaseKey key,ScenarioSpec spec,String code,String message,List<Search.Trace> trace) throws IOException {
        var caseRow=caseRows.get(attempted-1);
        if(caseRow.get("status")!=null) throw new IllegalStateException("Case already finalized");
        String status=code.equals("ALGORITHM_NO_FEASIBLE_PLACEMENT")?code:"RUN_ERROR";
        caseRow.put("status",status); caseRow.put("error_code",code); caseRow.put("error_message",message); failed++; counts(); writeManifest();
        var row=resultIdentity(key,spec,status,code,message); for(int i=0;i<11;i++) row.add(null);
        Files.writeString(directory.resolve("raw/"+key.phase()+"_results.csv"),csv(List.of(row)),StandardOpenOption.APPEND);
        if(!trace.isEmpty()) Files.writeString(directory.resolve("raw/optimizer_trace.csv"),csv(traceRows(key,trace)),StandardOpenOption.APPEND);
    }
    static void validateResult(ScenarioSpec spec,Profile.CaseKey key,RunCoordinator.CaseResult result) {
        double watts=PlacementObjective.watts(spec.inputs(),result.plan());
        if(!Double.isFinite(result.objectiveWatts()) || watts!=result.objectiveWatts()) throw new IllegalArgumentException("Recorded objective differs from placement");
        validateMetrics(spec,result.metrics(),result.allocationNanos());
        int budget=key.population()==null?1:Search.budget(key.population(),key.iterations());
        if(result.evaluations()!=budget || result.trace().size()!=(key.population()==null?0:budget)) throw new IllegalArgumentException("Evaluation budget mismatch");
        Double best=null;
        for(var entry:result.trace()) {
            if(entry.plan().isPresent()!=entry.watts().isPresent()) throw new IllegalArgumentException("Invalid trace feasibility");
            if(entry.watts().isPresent() && PlacementObjective.watts(spec.inputs(),entry.plan().orElseThrow())!=entry.watts().get())
                throw new IllegalArgumentException("Invalid trace objective");
            if(entry.phase().equals("PREDATOR") && entry.accepted()) throw new IllegalArgumentException("Predator cannot be eligible");
            if(entry.accepted() && entry.watts().isPresent() && (best==null || entry.watts().get()<best)) best=entry.watts().get();
        }
        if(key.population()!=null && (best==null || best!=watts)) throw new IllegalArgumentException("Final eligible trace best differs from objective");
    }
    private static void validateMetrics(ScenarioSpec spec,NativeMetrics.Result m,long nanos) {
        for(double value:new double[]{m.energyJ(),m.energyKwh(),m.slaRate(),m.horizonSeconds(),m.releaseSeconds()})
            if(!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite metric");
        int count=spec.inputs().cloudlets().size();
        if(m.energyJ()<=0 || m.energyKwh()!=m.energyJ()/3_600_000 || m.slaViolations()<m.failed()+m.censored()
            || m.slaViolations()>count || m.slaRate()!=(double)m.slaViolations()/count || m.completed()<0 || m.failed()<0 || m.censored()<0
            || m.completed()+m.failed()+m.censored()!=count || m.horizonSeconds()<=0 || m.horizonSeconds()>spec.censorSeconds()
            || m.censored()>0 && m.horizonSeconds()!=spec.censorSeconds() || m.releaseSeconds()<0 || nanos<0)
            throw new IllegalArgumentException("Inconsistent metric/counts");
    }
    private CSVParser read(String file,String header) throws IOException {
        var reader=Files.newBufferedReader(directory.resolve(file));
        try {
            var parser=CSV.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader);
            if(!parser.getHeaderNames().equals(List.of(header.split(",")))) { parser.close(); throw new IOException("Invalid CSV header: "+file); }
            return parser;
        } catch(Exception e) { reader.close(); throw e; }
    }
    private void checkIdentity(CSVRecord row,Profile.CaseKey key) {
        var actual=new ArrayList<String>(); for(String field:List.of("schema_version","run_id","phase","scenario","replication","algorithm","population","iterations")) actual.add(row.get(field));
        if(!row.isConsistent() || !actual.equals(identity(key).stream().map(v->v==null?"":v.toString()).toList())) throw new IllegalArgumentException("Wrong or duplicate CSV case identity");
    }
    private static double number(CSVRecord row,String column) {
        double value=Double.parseDouble(row.get(column)); if(!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite "+column); return value;
    }
    /** Re-read durable raw files. Analysis consumes these validated rows, never a successful fragment. */
    public List<Map<String,String>> validateRaw() throws IOException {
        if(attempted!=caseRows.size() || successful!=attempted || failed!=0) throw new IllegalStateException("Incomplete matrix");
        checkRequiredFiles();
        if(!Files.readString(directory.resolve("run.json")).equals(JSON.toJson(manifest)+"\n"))
            throw new IllegalArgumentException("Persisted manifest differs from canonical specifications/cases");
        var expectedWorkloads=new LinkedHashSet<String>(); config.profile().cases().forEach(k->expectedWorkloads.add(workload(k)));
        if(!expectedWorkloads.equals(specifications.keySet())) throw new IllegalArgumentException("Wrong specification matrix");
        var rows=new ArrayList<CSVRecord>();
        try(var main=read("raw/main_results.csv",RESULT_HEADER)) { rows.addAll(main.getRecords()); }
        if(config.profile()==Profile.research) try(var sensitivity=read("raw/sensitivity_results.csv",RESULT_HEADER)) { rows.addAll(sensitivity.getRecords()); }
        if(rows.size()!=caseRows.size()) throw new IllegalArgumentException("Wrong result count");
        try(var placements=read("raw/placements.csv",PLACEMENT_HEADER); var traces=read("raw/optimizer_trace.csv",TRACE_HEADER)) {
            var p=placements.iterator(); var t=traces.iterator(); var expected=config.profile().cases();
            for(int index=0;index<expected.size();index++) {
                var key=expected.get(index); var row=rows.get(index); checkIdentity(row,key);
                var spec=specifications.get(workload(key));
                if(spec==null || !"RUN_OK".equals(row.get("status")) || !"RUN_OK".equals(caseRows.get(index).get("status"))
                    || !row.get("error_code").isEmpty() || !row.get("error_message").isEmpty()) throw new IllegalArgumentException("Invalid successful case");
                var seeds=spec.inputs().seeds();
                if(!spec.inputs().equals(ScenarioGenerator.inputs(config.masterSeed(),key.phase(),key.scenario(),key.replication()))
                    || !row.get("scenario_sha256").equals(spec.fingerprint()) || Long.parseLong(row.get("scenario_seed"))!=seeds.scenarioSeed()
                    || Long.parseLong(row.get("workload_seed"))!=seeds.workloadSeed()
                    || !row.get("optimizer_seed").equals(Objects.toString(RunCoordinator.optimizerSeed(spec,key),""))
                    || Integer.parseInt(row.get("vm_count"))!=spec.inputs().vms().size() || Integer.parseInt(row.get("host_count"))!=spec.inputs().hosts().size())
                    throw new IllegalArgumentException("Seed/fingerprint/specification mismatch");
                var ids=new ArrayList<Integer>();
                for(int vm=0;vm<spec.inputs().vms().size();vm++) {
                    if(!p.hasNext()) throw new IllegalArgumentException("Missing placement"); var placement=p.next(); checkIdentity(placement,key);
                    if(Integer.parseInt(placement.get("vm_id"))!=vm) throw new IllegalArgumentException("Wrong VM order");
                    ids.add(Integer.parseInt(placement.get("host_id")));
                }
                if(PlacementObjective.watts(spec.inputs(),new PlacementPlan(ids))!=number(row,"objective_w")) throw new IllegalArgumentException("Stored objective does not match stored placement");
                var metrics=new NativeMetrics.Result(number(row,"energy_j"),number(row,"energy_kwh"),Integer.parseInt(row.get("sla_violations")),number(row,"sla_rate"),
                    Integer.parseInt(row.get("completed_cloudlets")),Integer.parseInt(row.get("failed_cloudlets")),Integer.parseInt(row.get("censored_cloudlets")),number(row,"horizon_s"),
                    ((Number)caseRows.get(index).get("release_s")).doubleValue(),0.0);
                validateMetrics(spec,metrics,Long.parseLong(row.get("allocation_wall_ns")));
                int budget=key.population()==null?1:Search.budget(key.population(),key.iterations());
                if(Integer.parseInt(row.get("evaluations"))!=budget) throw new IllegalArgumentException("Wrong evaluation count");
                Double best=null;
                for(int e=1;key.population()!=null && e<=budget;e++) {
                    if(!t.hasNext()) throw new IllegalArgumentException("Missing trace"); var trace=t.next(); checkIdentity(trace,key);
                    checkTracePosition(trace,key,e);
                    boolean feasible=switch(trace.get("feasible")) { case "true"->true; case "false"->false; default->throw new IllegalArgumentException("Invalid feasibility"); };
                    if(feasible) { double value=number(trace,"fitness_w"); if(value<=0) throw new IllegalArgumentException("Invalid fitness");
                        // HO rejects only non-improvements; GA keeps an elite. Historical eligible minimum remains population best.
                        if(!trace.get("stage").equals("predator") && (best==null || value<best)) best=value;
                    } else if(!trace.get("fitness_w").isEmpty()) throw new IllegalArgumentException("Infeasible numeric fitness");
                    if(best==null?!trace.get("best_fitness_w").isEmpty():number(trace,"best_fitness_w")!=best) throw new IllegalArgumentException("Invalid eligible trace best");
                }
                if(key.population()!=null && (best==null || best!=number(row,"objective_w"))) throw new IllegalArgumentException("Trace final objective mismatch");
            }
            if(p.hasNext() || t.hasNext()) throw new IllegalArgumentException("Extra placement/trace rows");
        }
        return rows.stream().map(r->Collections.unmodifiableMap(r.toMap())).toList();
    }
    private static void checkTracePosition(CSVRecord trace,Profile.CaseKey key,int e) {
        int n=key.population(),iteration,member; String stage;
        if(e<=n) { iteration=0; member=e-1; stage="initial"; }
        else if(key.algorithm().equals("GA")) { iteration=(e-n-1)/(n-1)+1; member=e-1; stage="ga_child"; }
        else {
            iteration=(e-n-1)/(3*n)+1; int position=(e-n-1)%(3*n);
            if(position<n) { stage=position%2==0?"river_male":"river_female"; member=position/2; }
            else if(position<2*n) { stage=position%2==0?"predator":"defense"; member=n/2+(position-n)/2; }
            else { stage="escape"; member=position-2*n; }
        }
        if(Integer.parseInt(trace.get("evaluation"))!=e || Integer.parseInt(trace.get("iteration"))!=iteration
            || Integer.parseInt(trace.get("candidate_index"))!=member || !trace.get("stage").equals(stage)) throw new IllegalArgumentException("Trace position/budget mismatch");
    }
    void analyze() throws IOException {
        if(!"RUNNING".equals(manifest.get("state")) || analysis!=null) throw new IllegalStateException("Invalid analysis lifecycle");
        analyzedInputs=new TreeMap<>();
        for(String name:List.of("main_results.csv","placements.csv","optimizer_trace.csv")) analyzedInputs.put("raw/"+name,sha256(directory.resolve("raw/"+name)));
        if(config.profile()==Profile.research) analyzedInputs.put("raw/sensitivity_results.csv",sha256(directory.resolve("raw/sensitivity_results.csv")));
        var raw=validateRaw();
        analysis=RunAnalysis.render(config.profile(),config.masterSeed(),raw);
        if(!analysis.isEmpty()) Files.createDirectory(directory.resolve("analysis"));
        for(var file:analysis.entrySet()) Files.writeString(directory.resolve(file.getKey()),file.getValue(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    void complete() throws IOException {
        if(!"RUNNING".equals(manifest.get("state"))) throw new IllegalStateException("Invalid terminal lifecycle");
        try {
            validateRaw();
            if(analysis==null || !analysis.keySet().equals(RunAnalysis.required(config.profile()))) throw new IOException("Required profile analysis is not complete");
            for(var file:analyzedInputs.entrySet()) if(!sha256(directory.resolve(file.getKey())).equals(file.getValue())) throw new IOException("Raw inputs changed after analysis");
            for(var file:analysis.entrySet()) if(!Files.isRegularFile(directory.resolve(file.getKey())) || !Files.readString(directory.resolve(file.getKey())).equals(file.getValue()))
                throw new IOException("Missing or changed required analysis: "+file.getKey());
            hashFiles(); manifest.put("state","COMPLETE"); manifest.put("finished_at",Instant.now().toString()); writeManifest();
        } catch(IOException | RuntimeException e) {
            fail("OUTPUT_ERROR",e.toString()); throw e;
        }
    }
    private void checkRequiredFiles() throws IOException {
        if(!Files.isRegularFile(directory.resolve("logs/run.log")) || logging.getStatusManager().getCopyOfStatusList().stream()
            .anyMatch(s->s.getLevel()==ch.qos.logback.core.status.Status.ERROR)) throw new IOException("Required run log failed");
        var effective=config.effective(); var expected=effectiveBytes(effective); var file=directory.resolve("effective.properties");
        if(!effective.equals(manifest.get("effective_config")) || !Files.isRegularFile(file) || Files.size(file)!=expected.length
            || !Arrays.equals(Files.readAllBytes(file),expected)) throw new IOException("Required effective configuration differs from executed configuration");
    }
    private void writeManifest() throws IOException {
        var temporary=Files.createTempFile(directory,"manifest-",".tmp");
        try {
            Files.writeString(temporary,JSON.toJson(manifest)+"\n");
            Files.move(temporary,directory.resolve("run.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public static String sha256(Path path) throws IOException {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(path)) { byte[] buffer=new byte[65536]; int count; while((count=input.read(buffer))!=-1) digest.update(buffer,0,count); }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private void configureLogging(String level) throws IOException {
        Files.createDirectory(directory.resolve("logs"));
        // Probe I/O explicitly: Logback reports file-open failures internally instead of throwing.
        Files.createFile(directory.resolve("logs/run.log"));
        logging=(LoggerContext)LoggerFactory.getILoggerFactory(); logging.reset();
        var encoder=new PatternLayoutEncoder(); encoder.setContext(logging); encoder.setPattern("%d{ISO8601,UTC} %-5level %logger - %msg%n"); encoder.start();
        var appender=new RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); appender.setContext(logging); appender.setName("RUN"); appender.setFile(directory.resolve("logs/run.log").toString()); appender.setEncoder(encoder);
        var policy=new FixedWindowRollingPolicy(); policy.setContext(logging); policy.setParent(appender); policy.setMinIndex(1); policy.setMaxIndex(3); policy.setFileNamePattern(directory.resolve("logs/run.%i.log").toString()); policy.start();
        var trigger=new SizeBasedTriggeringPolicy<ch.qos.logback.classic.spi.ILoggingEvent>(); trigger.setMaxFileSize(FileSize.valueOf("10MB"));
        trigger.setCheckIncrement(new ch.qos.logback.core.util.Duration(0)); // Default 60s gate misses short log bursts.
        trigger.start();
        appender.setRollingPolicy(policy); appender.setTriggeringPolicy(trigger); appender.start();
        if(!appender.isStarted()) throw new IOException("Cannot initialize run log");
        var root=logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME); root.setLevel(Level.valueOf(level)); root.addAppender(appender);
    }
    @Override public void close() { if(logging!=null) logging.stop(); }
}
