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

/** Buffered configured-case evidence: objective predictions and observed outcomes are separate columns. */
public final class ScenarioOutput implements CampaignEvidence {
    private static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final String ID="scenario_schema_version,run_id,phase,scenario,replication,algorithm,population,iterations";
    private static final String CASE=ID+",scenario_seed,workload_seed,optimizer_seed,scenario_sha256,status,error_code,error_message,vm_count,host_count,evaluations,objective_kind,objective_version,objective_score,predicted_power_w,predicted_reserved_peak_utilization,normalized_power,normalized_utilization,observed_utilization,observed_energy_j,observed_energy_kwh,observed_sla_violations,observed_sla_rate,observed_completed_cloudlets,observed_failed_cloudlets,observed_censored_cloudlets,observed_horizon_s,release_s,simulations,search_simulations,allocation_wall_ns";
    private static final String PLACE=ID+",vm_id,host_id";
    private static final String TRACE=ID+",evaluation,iteration,stage,candidate_index,feasible,objective_score,predicted_power_w,predicted_reserved_peak_utilization,normalized_power,normalized_utilization,accepted,best_objective_score,best_power_w";
    private static final String SUMMARY="scenario_schema_version,run_id,phase,scenario,algorithm,n,objective_kind,objective_version,objective_mean,predicted_power_mean_w,predicted_reserved_peak_utilization_mean,normalized_power_mean,normalized_utilization_mean,observed_utilization_mean,observed_energy_mean_j,observed_sla_mean,allocation_mean_ns";
    private final ScenarioConfig config;
    private final Path directory;
    private final Map<String,Object> manifest=new LinkedHashMap<>();
    private final Map<String,String> hashes=new TreeMap<>();
    private final Map<String,double[]> summaries=new LinkedHashMap<>();
    private CSVPrinter cases,placements,evaluations;
    private Profile.CaseKey current;
    private int attempted,successful,failed,caseEvaluations;
    private long completedEvaluations,completedSimulations,lastProgress;
    private Double best,bestPower;
    private String computingPhase;
    private Search.Evaluation computingEvaluation;
    private int workers;
    private ScenarioOutput(ScenarioConfig config,Path directory) { this.config=config; this.directory=directory; }
    public Path directory() { return directory; }
    public int workers() { return workers; }
    public static ScenarioOutput create(ScenarioConfig config,Path outputRoot) throws Exception {
        int workers=CaseExecutor.workers();
        Files.createDirectories(outputRoot);
        var output=new ScenarioOutput(config,Files.createDirectory(outputRoot.resolve("scenario-"+UUID.randomUUID())));
        output.workers=workers;
        try {
            var m=output.manifest;
            m.put("experiment_kind","static_scenario"); m.put("scenario_schema_version",ScenarioConfig.SCHEMA_VERSION);
            m.put("protocol",ScenarioConfig.PROTOCOL); m.put("profile","scenario");
            m.put("run_id",output.directory.getFileName().toString());
            m.put("config_name",config.name()); m.put("config_sha256",config.configSha256());
            m.put("objective",JsonParser.parseString(config.canonicalJson()).getAsJsonObject().get("objective"));
            m.put("state","RUNNING"); m.put("started_at",Instant.now().toString()); m.put("finished_at",null);
            m.put("master_seed",config.masterSeed());
            m.put("source_version",RunOutput.version());
            var artifact=Path.of(ScenarioOutput.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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
            m.put("resolved_config",JsonParser.parseString(config.canonicalJson()));
            m.put("effective_config",config.effective());
            m.put("feasibility",feasibility(config));
            m.put("expected_cases",config.expectedCases()); m.put("expected_evaluations",config.expectedEvaluations());
            m.put("expected_simulations",config.expectedCases()); m.put("search_simulations",0);
            m.put("files",output.hashes); m.put("error",null);
            m.put("evidence_limit",ScenarioConfig.EVIDENCE_LIMIT);
            m.put("allocation_timing",ScenarioConfig.ALLOCATION_TIMING);
            m.put("model_limits",ScenarioConfig.MODEL_LIMITS);
            StringBuilder effective=new StringBuilder(); config.effective().forEach((k,v)->effective.append(k).append('=').append(v).append('\n'));
            Files.writeString(output.directory.resolve("effective.properties"),effective,StandardOpenOption.CREATE_NEW);
            output.hash("effective.properties");
            Files.createDirectory(output.directory.resolve("config"));
            Files.write(output.directory.resolve("config/requested.json"),config.request().bytes(),StandardOpenOption.CREATE_NEW);
            output.hash("config/requested.json");
            Files.writeString(output.directory.resolve("config/resolved.json"),config.canonicalJson(),StandardOpenOption.CREATE_NEW);
            output.hash("config/resolved.json");
            Files.createDirectory(output.directory.resolve("raw")); Files.createDirectory(output.directory.resolve("scenarios"));
            output.cases=output.writer("raw/cases.csv",CASE); output.placements=output.writer("raw/placements.csv",PLACE); output.evaluations=output.writer("raw/evaluations.csv",TRACE);
            output.flush(); output.publish(); return output;
        } catch(Exception e) {
            try { output.fail("OUTPUT_ERROR",e.toString()); } catch(Exception diagnostic) { e.addSuppressed(diagnostic); }
            try { output.close(); } catch(Exception diagnostic) { e.addSuppressed(diagnostic); }
            throw e;
        }
    }
    private static List<Map<String,Object>> feasibility(ScenarioConfig config) {
        var records=new ArrayList<Map<String,Object>>();
        int replication=0;
        for(var result:config.feasibility()) {
            var record=new LinkedHashMap<String,Object>();
            record.put("replication",replication++); record.put("kind",result.kind()); record.put("reason",result.reason());
            records.add(record);
        }
        return records;
    }
    private CSVPrinter writer(String path,String header) throws IOException {
        return new CSVPrinter(Files.newBufferedWriter(directory.resolve(path),StandardOpenOption.CREATE_NEW),
            CSVFormat.DEFAULT.builder().setRecordSeparator('\n').setHeader(header.split(",")).build());
    }
    private void hash(String path) throws IOException { hashes.put(path,RunOutput.sha256(directory.resolve(path))); }
    private List<Object> identity() {
        return new ArrayList<>(Arrays.asList(ScenarioConfig.SCHEMA_VERSION,manifest.get("run_id"),current.phase(),current.scenario(),current.replication(),current.algorithm(),current.population(),current.iterations()));
    }
    public void begin(Profile.CaseKey key) throws IOException {
        if(current!=null || failed!=0 || !"RUNNING".equals(manifest.get("state")) || attempted>=config.expectedCases()) throw new IllegalStateException("Invalid scenario lifecycle");
        current=key; caseEvaluations=0; best=null; bestPower=null; computingPhase=null; computingEvaluation=null; attempted++; publish();
    }
    public void specification(ScenarioSpec spec) throws IOException {
        var record=new LinkedHashMap<String,Object>(); record.put("inputs",spec.inputs()); record.put("reference_seconds",spec.referenceSeconds());
        record.put("censor_s",spec.censorSeconds()); record.put("canonical_text",spec.canonicalText()); record.put("scenario_sha256",spec.fingerprint());
        String file="scenarios/replication-"+current.replication()+".json";
        Files.writeString(directory.resolve(file),JSON.toJson(record)+"\n",StandardOpenOption.CREATE_NEW); hash(file);
    }
    private void validateScore(PlacementObjective.Score score) {
        if(score==null || !Double.isFinite(score.powerW()) || score.powerW()<=0
            || !Double.isFinite(score.reservedPeakUtilization()) || score.reservedPeakUtilization()<=0 || score.reservedPeakUtilization()>1
            || !Double.isFinite(score.normalizedPower()) || score.normalizedPower()<0 || score.normalizedPower()>1
            || !Double.isFinite(score.normalizedUtilization()) || score.normalizedUtilization()<0 || score.normalizedUtilization()>1
            || !Double.isFinite(score.objective()) || score.objective()<0 || score.objective()>1)
            throw new IllegalArgumentException("Invalid placement objective components");
        double expected=config.objective().powerWeight()*score.normalizedPower()
            +config.objective().utilizationWeight()*score.normalizedUtilization();
        if(score.objective()!=expected) throw new IllegalArgumentException("Weighted objective does not match its components");
    }
    public void evaluation(Search.Evaluation value) throws IOException {
        if(current==null || current.population()==null || value.evaluation()!=caseEvaluations+1 || caseEvaluations>=config.evaluationBudget())
            throw new IllegalArgumentException("Invalid scalar evaluation order");
        if(value.feasible()!=(value.score()!=null) || value.watts()!=null && (!Double.isFinite(value.watts()) || value.watts()<=0)
            || value.phase().equals("PREDATOR") && value.accepted()) throw new IllegalArgumentException("Invalid scalar evidence");
        if(value.score()!=null) validateScore(value.score());
        if(value.accepted() && value.score()!=null && (best==null || value.score().objective()<best)) best=value.score().objective();
        if(value.accepted() && value.score()!=null && (bestPower==null || value.score().powerW()<bestPower)) bestPower=value.score().powerW();
        if(!Objects.equals(best,value.bestObjectiveScore()) || !Objects.equals(bestPower,value.bestWatts()))
            throw new IllegalArgumentException("Invalid eligible best chain");
        var row=identity(); row.addAll(Arrays.asList(value.evaluation(),value.iteration(),value.phase(),value.member(),value.feasible(),
            value.objectiveScore(),value.watts(),value.reservedPeakUtilization(),value.normalizedPower(),value.normalizedUtilization(),
            value.accepted(),value.bestObjectiveScore(),value.bestWatts()));
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
    public void success(ScenarioSpec spec,PlacementPlan plan,PlacementObjective.Score score,int count,NativeMetrics.Result metrics,long nanos) throws IOException {
        if(current==null || count!=(current.population()==null?1:config.evaluationBudget()) || caseEvaluations!=(current.population()==null?0:count)
            || !Double.isFinite(score.powerW()) || score.powerW()!=PlacementObjective.watts(spec.inputs(),plan)
            || !score.equals(PlacementObjective.score(spec.inputs(),plan,config.objective()))
            || current.population()!=null && !Objects.equals(best,score.objective()) || nanos<0) throw new IllegalArgumentException("Invalid scenario result");
        for(double value:new double[]{metrics.energyJ(),metrics.energyKwh(),metrics.slaRate(),metrics.horizonSeconds(),metrics.releaseSeconds(),metrics.observedUtilization()})
            if(!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite metric");
        if(metrics.completed()!=config.vmCount() || metrics.failed()!=0 || metrics.censored()!=0 || metrics.slaViolations()!=0
            || metrics.slaRate()!=0 || metrics.energyJ()<=0 || metrics.energyKwh()!=metrics.energyJ()/3_600_000 || metrics.horizonSeconds()<=0
            || metrics.observedUtilization()<=0 || metrics.observedUtilization()>1)
            throw new IllegalArgumentException("Invalid configured completion metrics");
        for(int vm=0;vm<plan.hostIds().size();vm++) { var row=identity(); row.addAll(List.of(vm,plan.hostIds().get(vm))); placements.printRecord(row); }
        var row=caseRow(spec,"RUN_OK",null,null);
        row.addAll(Arrays.asList(count,config.objective().kindName(),ScenarioConfig.OBJECTIVE_VERSION,score.objective(),score.powerW(),
            score.reservedPeakUtilization(),score.normalizedPower(),score.normalizedUtilization(),metrics.observedUtilization(),
            metrics.energyJ(),metrics.energyKwh(),metrics.slaViolations(),metrics.slaRate(),metrics.completed(),metrics.failed(),
            metrics.censored(),metrics.horizonSeconds(),metrics.releaseSeconds(),1,0,nanos));
        cases.printRecord(row); flush();
        var totals=summaries.computeIfAbsent(current.algorithm(),k->new double[10]); totals[0]++; totals[1]+=score.objective();
        totals[2]+=score.powerW(); totals[3]+=score.reservedPeakUtilization(); totals[4]+=score.normalizedPower();
        totals[5]+=score.normalizedUtilization(); totals[6]+=metrics.observedUtilization(); totals[7]+=metrics.energyJ();
        totals[8]+=metrics.slaRate(); totals[9]+=nanos;
        if(current.population()==null) completedEvaluations++;
        successful++; completedSimulations++; current=null; publish();
    }
    public void failed(ScenarioSpec spec,String code,String message) throws IOException {
        if(current==null) throw new IllegalStateException("No active case");
        var row=caseRow(spec,"RUN_ERROR",code,message); row.add(caseEvaluations);
        while(row.size()<CASE.split(",").length) row.add(null);
        cases.printRecord(row); failed++; flush(); fail(code,message);
    }
    public void fail(String code,String message) throws IOException {
        if("FAILED".equals(manifest.get("state"))) return;
        manifest.put("state","FAILED"); manifest.put("finished_at",Instant.now().toString()); manifest.put("error",Map.of("code",code,"message",message));
        try { flush(); hashRaw(); } finally { publish(); }
    }
    public void complete() throws IOException {
        if(successful!=config.expectedCases() || failed!=0 || current!=null) throw new IllegalStateException("Incomplete scenario matrix");
        Files.createDirectory(directory.resolve("analysis"));
        try(var summary=writer("analysis/summary.csv",SUMMARY)) {
            for(var entry:summaries.entrySet()) {
                var t=entry.getValue(); summary.printRecord(ScenarioConfig.SCHEMA_VERSION,manifest.get("run_id"),ScenarioConfig.PHASE,config.name(),
                    entry.getKey(),(int)t[0],config.objective().kindName(),ScenarioConfig.OBJECTIVE_VERSION,
                    t[1]/t[0],t[2]/t[0],t[3]/t[0],t[4]/t[0],t[5]/t[0],t[6]/t[0],t[7]/t[0],t[8]/t[0],t[9]/t[0]);
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
        manifest.put("completed_simulations",completedSimulations);
        atomic("run.json",manifest);
        var progress=new LinkedHashMap<String,Object>();
        for(String key:List.of("experiment_kind","scenario_schema_version","run_id","state","expected_cases","attempted_cases","successful_cases","failed_cases","unattempted_cases","expected_evaluations","completed_evaluations","expected_simulations","completed_simulations","search_simulations","error")) progress.put(key,manifest.get(key));
        Map<String,Object> active=null;
        if(current!=null) {
            active=new LinkedHashMap<>(); active.put("phase",current.phase()); active.put("scenario",current.scenario()); active.put("replication",current.replication());
            active.put("algorithm",current.algorithm()); active.put("population",current.population()); active.put("iterations",current.iterations()); active.put("evaluation",caseEvaluations);
            active.put("objective_kind",config.objective().kindName());
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
