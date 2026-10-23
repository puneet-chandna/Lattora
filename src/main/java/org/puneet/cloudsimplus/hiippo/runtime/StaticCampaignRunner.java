package org.puneet.cloudsimplus.hiippo.runtime;

import org.puneet.cloudsimplus.hiippo.placement.*;
import org.puneet.cloudsimplus.hiippo.scenario.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Protocol-neutral paired static cases with bounded workers and streamed, canonically ordered scalar evidence. */
public final class StaticCampaignRunner {
    private StaticCampaignRunner() {}
    public static Path execute(StaticCampaign campaign,CampaignEvidence output) throws Exception {
        if(output.workers()>1) return executeParallel(campaign,output);
        ScenarioSpec spec=null;
        try {
            for(int replication=0;replication<campaign.replications();replication++) {
                spec=null;
                for(String algorithm:campaign.algorithms()) {
                    var key=key(campaign,replication,algorithm);
                    output.begin(key);
                    String code="RUN_ERROR";
                    try {
                        if(spec==null) {
                            code="SCENARIO_REJECTED";
                            spec=campaign.specFor(key);
                            code="OUTPUT_ERROR"; output.specification(spec);
                        }
                        code="RUN_ERROR";
                        var result=evaluate(campaign,spec,key,output::evaluation,phase->{});
                        code="OUTPUT_ERROR"; output.success(spec,result.plan(),result.watts(),result.evaluations(),result.metrics(),result.nanos());
                    } catch(Exception e) {
                        code=rejectedCode(e,code);
                        try { output.failed(spec,code,e.toString()); } catch(Exception diagnostic) { e.addSuppressed(diagnostic); }
                        throw e;
                    }
                }
            }
            output.complete();
            return output.directory();
        } catch(Exception e) {
            // Also retain failures outside the active case (summary/hash publication).
            try { output.fail(failureCode(e),e.toString()); } catch(Exception diagnostic) { e.addSuppressed(diagnostic); }
            throw e;
        }
    }
    static Profile.CaseKey key(StaticCampaign campaign,int replication,String algorithm) {
        boolean search=campaign.search(algorithm);
        return new Profile.CaseKey(campaign.phase(),campaign.scenarioName(),replication,algorithm,
            search?campaign.searchPopulation():null,search?campaign.searchIterations():null);
    }
    record CaseResult(PlacementPlan plan,double watts,int evaluations,NativeMetrics.Result metrics,long nanos) {}
    @FunctionalInterface interface CaseRunner {
        CaseResult run(StaticCampaign campaign,ScenarioSpec spec,Profile.CaseKey key,Search.EvaluationSink sink,Consumer<String> phase) throws Exception;
    }
    private static final class CaseFailure extends Exception {
        final String code;
        CaseFailure(String code,Exception cause) { super(cause); this.code=code; }
    }
    private record LiveProgress(String phase,Search.Evaluation evaluation) {}
    private record Work(Profile.CaseKey key,ScenarioSpec spec,Exception failure,Path spool,AtomicReference<LiveProgress> progress) {}
    /** Recognized campaign rejections win over the in-flight phase; anything else keeps the phase's own code. */
    private static String rejectedCode(Exception e,String current) {
        if(e instanceof CampaignFailure failure) return failure.code();
        if(e instanceof CaseFailure failure) return failure.code;
        return e instanceof java.io.IOException?"OUTPUT_ERROR":current;
    }
    private static String failureCode(Exception e) {
        return e instanceof CampaignFailure||e instanceof CaseFailure?rejectedCode(e,"RUN_ERROR")
            :e instanceof java.io.IOException?"OUTPUT_ERROR":"RUN_ERROR";
    }
    private static CaseResult evaluate(StaticCampaign campaign,ScenarioSpec spec,Profile.CaseKey key,Search.EvaluationSink sink,Consumer<String> phase) throws Exception {
        String code="RUN_ERROR";
        try {
            PlacementPlan plan; double watts; int evaluations; long start=System.nanoTime();
            if(key.population()!=null) {
                phase.accept("optimizing");
                long seed=Seeds.derive(campaign.masterSeed(),key.phase(),key.scenario(),key.replication(),"optimizer",key.algorithm());
                var search=Search.runStreaming(spec.inputs(),Search.Algorithm.valueOf(key.algorithm()),key.population(),key.iterations(),seed,sink);
                code="ALGORITHM_NO_FEASIBLE_PLACEMENT";
                var best=search.best().orElseThrow(()->new IllegalStateException("No feasible final placement"));
                plan=best.plan(); watts=best.watts(); evaluations=search.evaluations();
            } else {
                var planned=key.algorithm().equals("FirstFit")?BaselinePlanner.firstFit(spec.inputs()):BaselinePlanner.bestFit(spec.inputs());
                code="ALGORITHM_NO_FEASIBLE_PLACEMENT";
                var best=planned.orElseThrow(()->new IllegalStateException("No feasible final placement"));
                plan=best.plan(); watts=best.objectiveWatts(); evaluations=best.evaluations();
            }
            long nanos=System.nanoTime()-start;
            code="RUN_ERROR";
            phase.accept("simulating");
            return new CaseResult(plan,watts,evaluations,NativeMetrics.run(spec,plan),nanos);
        } catch(Exception e) { throw new CaseFailure(e instanceof java.io.IOException?"OUTPUT_ERROR":code,e); }
    }
    static Path executeParallel(StaticCampaign campaign,CampaignEvidence output) throws Exception {
        return executeParallel(campaign,output,StaticCampaignRunner::evaluate);
    }
    static Path executeParallel(StaticCampaign campaign,CampaignEvidence output,CaseRunner runner) throws Exception {
        Path spools=output.directory().resolve("case-spools");
        List<String> algorithms=campaign.algorithms();
        ScenarioSpec spec=null; int next=0;
        int pendingLimit=4*output.workers();
        Exception terminalFailure=null;
        try {
            Files.createDirectory(spools);
            while(next<campaign.expectedCases()) {
                var window=new ArrayList<Work>();
                var tasks=new ArrayList<java.util.concurrent.Callable<CaseResult>>();
                while(next<campaign.expectedCases() && window.size()<pendingLimit) {
                    int index=next++,replication=index/algorithms.size(); String algorithm=algorithms.get(index%algorithms.size());
                    boolean search=campaign.search(algorithm);
                    var key=key(campaign,replication,algorithm);
                    Exception failure=null;
                    try { if(spec==null || spec.inputs().seeds().replication()!=replication)
                        spec=campaign.specFor(key); }
                    catch(Exception e) { spec=null; failure=e instanceof CampaignFailure recognized
                        ?new CaseFailure(recognized.code(),e):new CaseFailure("SCENARIO_REJECTED",e); }
                    var work=new Work(key,spec,failure,spools.resolve("case-"+index+".spool"),new AtomicReference<>(new LiveProgress("planning",null))); window.add(work);
                    tasks.add(()->{
                        if(work.failure()!=null) throw work.failure();
                        Consumer<String> phase=p->work.progress().updateAndGet(previous->new LiveProgress(p,previous.evaluation()));
                        CaseResult result;
                        if(!search) result=runner.run(campaign,work.spec(),work.key(),value->{ throw new IllegalStateException("Baseline emitted optimizer evidence"); },phase);
                        else try(var spool=new EvaluationSpool(work.spool())) {
                            result=runner.run(campaign,work.spec(),work.key(),value->{
                                spool.accept(value); work.progress().set(new LiveProgress("optimizing",value));
                            },phase);
                        }
                        phase.accept("finished"); return result;
                    });
                    if(failure!=null) break;
                }
                try(var executor=new CaseExecutor<>(output.workers(),pendingLimit,tasks)) {
                    for(int i=0;i<window.size();i++) {
                        var work=window.get(i); output.begin(work.key()); boolean replayed=false;
                        try {
                            if(work.spec()!=null && algorithms.get(0).equals(work.key().algorithm())) output.specification(work.spec());
                            var result=executor.await(i,()->{
                                var live=work.progress().get(); output.liveProgress(live.phase(),live.evaluation());
                            });
                            var live=work.progress().get(); output.liveProgress(live.phase(),live.evaluation());
                            EvaluationSpool.replay(work.spool(),output::evaluation); replayed=true;
                            output.success(work.spec(),result.plan(),result.watts(),result.evaluations(),result.metrics(),result.nanos());
                            Files.deleteIfExists(work.spool());
                        } catch(Exception e) {
                            executor.close();
                            if(!replayed) try { EvaluationSpool.replay(work.spool(),output::evaluation); }
                            catch(Exception diagnostic) { e.addSuppressed(diagnostic); }
                            String code=rejectedCode(e,"RUN_ERROR");
                            try { output.failed(work.spec(),code,e.toString()); } catch(Exception diagnostic) { e.addSuppressed(diagnostic); }
                            throw e;
                        }
                    }
                }
            }
            deleteSpools(spools);
            output.complete(); return output.directory();
        } catch(Exception e) {
            terminalFailure=e;
            String code=failureCode(e);
            try { output.fail(code,e.toString()); } catch(Exception diagnostic) { e.addSuppressed(diagnostic); }
            throw e;
        } finally {
            // Every owned worker has terminated before disposing unpublished evidence.
            try { deleteSpools(spools); }
            catch(Exception cleanup) { if(terminalFailure!=null) terminalFailure.addSuppressed(cleanup); else throw cleanup; }
        }
    }
    private static void deleteSpools(Path directory) throws java.io.IOException {
        if(!Files.exists(directory)) return;
        try(var files=Files.list(directory)) { for(var file:files.toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(directory);
    }
}
