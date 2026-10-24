package org.puneet.cloudsimplus.hiippo.runtime;

import com.google.gson.*;
import org.apache.commons.csv.*;
import org.puneet.cloudsimplus.hiippo.scenario.*;
import org.puneet.cloudsimplus.hiippo.placement.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarFile;

/** Buffered case evidence with small atomic progress; no historical populations or specifications. */
public final class StressOutput implements CampaignEvidence {
    private static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final String ID="stress_schema_version,run_id,phase,scenario,replication,algorithm,population,iterations";
    private static final String CASE=ID+",scenario_seed,workload_seed,optimizer_seed,scenario_sha256,status,error_code,error_message,vm_count,host_count,evaluations,objective_w,energy_j,energy_kwh,sla_violations,sla_rate,completed_cloudlets,failed_cloudlets,censored_cloudlets,horizon_s,release_s,allocation_wall_ns";
    private static final String PLACE=ID+",vm_id,host_id";
    private static final String TRACE=ID+",evaluation,iteration,stage,candidate_index,feasible,fitness_w,accepted,best_fitness_w";
    private static final String SUMMARY="stress_schema_version,run_id,phase,scenario,algorithm,n,objective_mean_w,energy_mean_j,sla_mean,allocation_mean_ns";
    public static final String EVIDENCE_LIMIT="Scalar traces check order, budgets and eligible-best consistency; they cannot independently replay discarded candidates or guarantee detection of coherent non-best scalar tampering.";
    private final StressConfig config;
    private final Path directory;
    private final Map<String,Object> manifest=new LinkedHashMap<>();
    private final Map<String,String> hashes=new TreeMap<>();
    private final Map<String,double[]> summaries=new LinkedHashMap<>();
    private CSVPrinter cases,placements,evaluations;
    private Profile.CaseKey current;
    private int attempted,successful,failed,caseEvaluations;
    private long completedEvaluations,lastProgress;
    private Double best;
    private String computingPhase;
    private Search.Evaluation computingEvaluation;
    private int workers;
    private StressOutput(StressConfig config,Path directory) { this.config=config; this.directory=directory; }
    public Path directory() { return directory; }
    public int workers() { return workers; }
    public static StressOutput create(StressConfig config) throws Exception {
        int workers=CaseExecutor.workers();
        Files.createDirectories(config.outputRoot());
        var output=new StressOutput(config,Files.createDirectory(config.outputRoot().resolve("stress-"+UUID.randomUUID())));
        output.workers=workers;
        try {
            var m=output.manifest;
            m.put("experiment_kind","static_stress"); m.put("stress_schema_version",1); m.put("profile","stress");
            m.put("experiment_phase",config.experimentPhase()); m.put("run_id",output.directory.getFileName().toString());
            m.put("state","RUNNING"); m.put("started_at",Instant.now().toString()); m.put("finished_at",null); m.put("master_seed",config.masterSeed());
            m.put("source_version",RunOutput.version());
            var artifact=Path.of(StressOutput.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            m.put("artifact_sha256",Files.isRegularFile(artifact)?RunOutput.sha256(artifact):null);
            String revision="unknown"; boolean dirty=true;
            if(Files.isRegularFile(artifact)) try(var jar=new JarFile(artifact.toFile())) {
                var attributes=jar.getManifest().getMainAttributes();
                revision=attributes.getValue("Git-Revision"); dirty=!"false".equals(attributes.getValue("Git-Dirty"));
            }
            m.put("git_revision",revision); m.put("git_dirty",dirty); m.put("java_version",System.getProperty("java.runtime.version"));
            m.put("cloudsim_version","8.5.7"); m.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
            m.put("arch",System.getProperty("os.arch")); m.put("max_heap_bytes",Runtime.getRuntime().maxMemory());
            m.put("execution_workers",workers);
            m.put("effective_config",config.effective()); m.put("expected_cases",config.expectedCases());
            m.put("expected_evaluations",config.replications()*(2L*config.evaluationBudget()+2)); m.put("files",output.hashes); m.put("error",null);
            m.put("evidence_limit",EVIDENCE_LIMIT);
            m.put("allocation_timing","End-to-end search including synchronous buffered scalar trace writing; not pure CPU or directly comparable to frozen allocation timing.");
            StringBuilder effective=new StringBuilder(); config.effective().forEach((k,v)->effective.append(k).append('=').append(v).append('\n'));
            Files.writeString(output.directory.resolve("effective.properties"),effective,StandardOpenOption.CREATE_NEW);
            output.hash("effective.properties");
            Files.createDirectory(output.directory.resolve("raw")); Files.createDirectory(output.directory.resolve("scenarios"));
            output.cases=output.writer("raw/cases.csv",CASE); output.placements=output.writer("raw/placements.csv",PLACE); output.evaluations=output.writer("raw/evaluations.csv",TRACE);
            output.flush(); output.publish(); return output;
        } catch(Exception e) {
            try { output.fail("OUTPUT_ERROR",e.toString()); } catch(Exception diagnostic) { e.addSuppressed(diagnostic); }
            try { output.close(); } catch(Exception diagnostic) { e.addSuppressed(diagnostic); }
            throw e;
        }
    }
    private CSVPrinter writer(String path,String header) throws IOException {
        return new CSVPrinter(Files.newBufferedWriter(directory.resolve(path),StandardOpenOption.CREATE_NEW),
            CSVFormat.DEFAULT.builder().setRecordSeparator('\n').setHeader(header.split(",")).build());
    }
    private void hash(String path) throws IOException { hashes.put(path,RunOutput.sha256(directory.resolve(path))); }
    private List<Object> identity() {
        return new ArrayList<>(Arrays.asList(1,manifest.get("run_id"),current.phase(),current.scenario(),current.replication(),current.algorithm(),current.population(),current.iterations()));
    }
    public void begin(Profile.CaseKey key) throws IOException {
        if(current!=null || failed!=0 || !"RUNNING".equals(manifest.get("state")) || attempted>=config.expectedCases()) throw new IllegalStateException("Invalid stress lifecycle");
        current=key; caseEvaluations=0; best=null; computingPhase=null; computingEvaluation=null; attempted++; publish();
    }
    public void specification(ScenarioSpec spec) throws IOException {
        var record=new LinkedHashMap<String,Object>(); record.put("inputs",spec.inputs()); record.put("reference_seconds",spec.referenceSeconds());
        record.put("censor_s",spec.censorSeconds()); record.put("canonical_text",spec.canonicalText()); record.put("scenario_sha256",spec.fingerprint());
        String file="scenarios/replication-"+current.replication()+".json";
        Files.writeString(directory.resolve(file),JSON.toJson(record)+"\n",StandardOpenOption.CREATE_NEW); hash(file);
    }
    public void evaluation(Search.Evaluation value) throws IOException {
        if(current==null || current.population()==null || value.evaluation()!=caseEvaluations+1 || caseEvaluations>=config.evaluationBudget())
            throw new IllegalArgumentException("Invalid scalar evaluation order");
        if(value.feasible()!=(value.watts()!=null) || value.watts()!=null && (!Double.isFinite(value.watts()) || value.watts()<=0)
            || value.phase().equals("PREDATOR") && value.accepted()) throw new IllegalArgumentException("Invalid scalar evidence");
        if(value.accepted() && value.watts()!=null && (best==null || value.watts()<best)) best=value.watts();
        if(!Objects.equals(best,value.bestWatts())) throw new IllegalArgumentException("Invalid eligible best chain");
        var row=identity(); row.addAll(Arrays.asList(value.evaluation(),value.iteration(),value.phase(),value.member(),value.feasible(),value.watts(),value.accepted(),value.bestWatts()));
        evaluations.printRecord(row); caseEvaluations++; completedEvaluations++;
        // Progress is bounded in size and frequency, irrespective of total trace length.
        if(System.nanoTime()-lastProgress>=1_000_000_000L) { evaluations.flush(); publish(); }
    }
    /** Optional runtime snapshot; unpublished scalar evidence never enters scientific counters. */
    public void liveProgress(String phase,Search.Evaluation evaluation) throws IOException {
        if(current==null) throw new IllegalStateException("No active case for live progress");
        computingPhase=phase; computingEvaluation=evaluation;
        if(System.nanoTime()-lastProgress>=1_000_000_000L) publish();
    }
    private List<Object> caseRow(ScenarioSpec spec,String status,String code,String message) {
        var row=identity();
        row.addAll(Arrays.asList(Seeds.derive(config.masterSeed(),current.phase(),current.scenario(),current.replication(),"scenario"),
            Seeds.derive(config.masterSeed(),current.phase(),current.scenario(),current.replication(),"workload"),
            current.population()==null?null:Seeds.derive(config.masterSeed(),current.phase(),current.scenario(),current.replication(),"optimizer",current.algorithm()),
            spec==null?null:spec.fingerprint(),status,code,message,config.vmCount(),config.hostCount()));
        return row;
    }
    public void success(ScenarioSpec spec,PlacementPlan plan,double watts,int count,NativeMetrics.Result metrics,long nanos) throws IOException {
        if(current==null || count!=(current.population()==null?1:config.evaluationBudget()) || caseEvaluations!=(current.population()==null?0:count)
            || !Double.isFinite(watts) || watts!=PlacementObjective.watts(spec.inputs(),plan)
            || current.population()!=null && !Objects.equals(best,watts) || nanos<0) throw new IllegalArgumentException("Invalid stress result");
        for(double value:new double[]{metrics.energyJ(),metrics.energyKwh(),metrics.slaRate(),metrics.horizonSeconds(),metrics.releaseSeconds()})
            if(!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite metric");
        if(metrics.completed()!=config.vmCount() || metrics.failed()!=0 || metrics.censored()!=0 || metrics.slaViolations()!=0
            || metrics.slaRate()!=0 || metrics.energyJ()<=0 || metrics.energyKwh()!=metrics.energyJ()/3_600_000 || metrics.horizonSeconds()<=0)
            throw new IllegalArgumentException("Invalid static completion metrics");
        for(int vm=0;vm<plan.hostIds().size();vm++) { var row=identity(); row.addAll(List.of(vm,plan.hostIds().get(vm))); placements.printRecord(row); }
        var row=caseRow(spec,"RUN_OK",null,null);
        row.addAll(Arrays.asList(count,watts,metrics.energyJ(),metrics.energyKwh(),metrics.slaViolations(),metrics.slaRate(),metrics.completed(),metrics.failed(),metrics.censored(),metrics.horizonSeconds(),metrics.releaseSeconds(),nanos));
        cases.printRecord(row); flush();
        var totals=summaries.computeIfAbsent(current.algorithm(),k->new double[5]); totals[0]++; totals[1]+=watts; totals[2]+=metrics.energyJ(); totals[3]+=metrics.slaRate(); totals[4]+=nanos;
        if(current.population()==null) completedEvaluations++;
        successful++; current=null; publish();
    }
    public void failed(ScenarioSpec spec,String code,String message) throws IOException {
        if(current==null) throw new IllegalStateException("No active case");
        var row=caseRow(spec,"RUN_ERROR",code,message); row.add(caseEvaluations); for(int i=0;i<11;i++) row.add(null);
        cases.printRecord(row); failed++; flush(); fail(code,message);
    }
    public void fail(String code,String message) throws IOException {
        if("FAILED".equals(manifest.get("state"))) return;
        manifest.put("state","FAILED"); manifest.put("finished_at",Instant.now().toString()); manifest.put("error",Map.of("code",code,"message",message));
        try { flush(); hashRaw(); } finally { publish(); }
    }
    public void complete() throws IOException {
        if(successful!=config.expectedCases() || failed!=0 || current!=null) throw new IllegalStateException("Incomplete stress matrix");
        Files.createDirectory(directory.resolve("analysis"));
        try(var summary=writer("analysis/summary.csv",SUMMARY)) {
            for(var entry:summaries.entrySet()) {
                var t=entry.getValue(); summary.printRecord(1,manifest.get("run_id"),config.experimentPhase(),config.effective().get("scenarios"),entry.getKey(),(int)t[0],t[1]/t[0],t[2]/t[0],t[3]/t[0],t[4]/t[0]);
            }
        }
        flush(); hashRaw(); hash("analysis/summary.csv");
        manifest.put("state","COMPLETE"); manifest.put("finished_at",Instant.now().toString()); publish();
    }
    private void hashRaw() throws IOException {
        for(String path:List.of("raw/cases.csv","raw/placements.csv","raw/evaluations.csv")) if(Files.isRegularFile(directory.resolve(path))) hash(path);
    }
    private void flush() throws IOException { if(evaluations!=null)evaluations.flush(); if(placements!=null)placements.flush(); if(cases!=null)cases.flush(); }
    private void publish() throws IOException {
        manifest.put("attempted_cases",attempted); manifest.put("successful_cases",successful); manifest.put("failed_cases",failed);
        manifest.put("unattempted_cases",config.expectedCases()-attempted); manifest.put("completed_evaluations",completedEvaluations);
        atomic("run.json",manifest);
        var progress=new LinkedHashMap<String,Object>();
        for(String key:List.of("experiment_kind","stress_schema_version","run_id","state","expected_cases","attempted_cases","successful_cases","failed_cases","unattempted_cases","expected_evaluations","completed_evaluations","error")) progress.put(key,manifest.get(key));
        Map<String,Object> active=null;
        if(current!=null) {
            active=new LinkedHashMap<>(); active.put("phase",current.phase()); active.put("scenario",current.scenario()); active.put("replication",current.replication());
            active.put("algorithm",current.algorithm()); active.put("population",current.population()); active.put("iterations",current.iterations()); active.put("evaluation",caseEvaluations);
            if(computingPhase!=null) {
                active.put("computing_phase",computingPhase);
                active.put("computing_evaluation",computingEvaluation==null?0:computingEvaluation.evaluation());
                active.put("computing_iteration",computingEvaluation==null?null:computingEvaluation.iteration());
                active.put("computing_stage",computingEvaluation==null?null:computingEvaluation.phase());
            }
        }
        progress.put("current_case",active); atomic("progress.json",progress); lastProgress=System.nanoTime();
    }
    private void atomic(String file,Object value) throws IOException {
        var tmp=directory.resolve(file+".tmp"); Files.writeString(tmp,JSON.toJson(value)+"\n");
        Files.move(tmp,directory.resolve(file),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    @Override public void close() throws IOException {
        IOException failure=null;
        for(var writer:Arrays.asList(evaluations,placements,cases)) if(writer!=null) try { writer.close(); }
        catch(IOException e) { if(failure==null) failure=e; else failure.addSuppressed(e); }
        if(failure!=null) throw failure;
    }
}
