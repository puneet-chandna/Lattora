package org.puneet.cloudsimplus.hiippo.runtime;

import org.puneet.cloudsimplus.hiippo.scenario.ScenarioGenerator;
import org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec;
import java.nio.file.Path;
import java.util.*;

/** Resolved static stress inputs; presets belong to the supervising runner. */
public record StressConfig(int vmCount,int hostCount,int population,int iterations,int replications,
                           long masterSeed,String experimentPhase,Path outputRoot) implements StaticCampaign {
    public StressConfig {
        Objects.requireNonNull(outputRoot);
        if(vmCount<1 || hostCount<1 || population<2 || population%2!=0 || iterations<1 || replications<1)
            throw new IllegalArgumentException("Stress requires positive dimensions/T/R and even population >=2");
        if(!List.of("stress","stress_calibration").contains(experimentPhase))
            throw new IllegalArgumentException("Invalid stress experiment phase");
        if(outputRoot.toString().isBlank()) throw new IllegalArgumentException("Stress output directory is required");
        Math.addExact(population,Math.multiplyExact(3,Math.multiplyExact(population,iterations)));
        Math.multiplyExact(4,replications);
    }

    public static StressConfig parse(String[] args) {
        CaseExecutor.workers();
        var flags=Set.of("--profile","--vms","--hosts","--population","--iterations","--replications",
            "--seed","--experiment-phase","--output-dir");
        var options=new HashMap<String,String>();
        for(int i=0;i<args.length;i++) {
            String key=args[i];
            if(!flags.contains(key)) throw new IllegalArgumentException("Unknown stress option: "+key);
            if(options.containsKey(key)) throw new IllegalArgumentException("Duplicate option: "+key);
            if(++i==args.length || args[i].startsWith("--") || args[i].isBlank())
                throw new IllegalArgumentException("Missing value for "+key);
            options.put(key,args[i]);
        }
        for(String flag:flags) if(!options.containsKey(flag)) throw new IllegalArgumentException(flag+" is required");
        if(!options.get("--profile").equals("stress")) throw new IllegalArgumentException("--profile must be stress");
        for(String flag:List.of("--vms","--hosts","--population","--iterations","--replications"))
            if(!options.get(flag).matches("[0-9]+")) throw new IllegalArgumentException(flag+" must be positive decimal");
        if(!options.get("--seed").matches("[+-]?[0-9]+")) throw new IllegalArgumentException("--seed must be signed decimal");
        return new StressConfig(Integer.parseInt(options.get("--vms")),Integer.parseInt(options.get("--hosts")),
            Integer.parseInt(options.get("--population")),Integer.parseInt(options.get("--iterations")),
            Integer.parseInt(options.get("--replications")),Long.parseLong(options.get("--seed")),
            options.get("--experiment-phase"),Path.of(options.get("--output-dir")));
    }

    public int evaluationBudget() { return Math.addExact(population,Math.multiplyExact(3,Math.multiplyExact(population,iterations))); }
    public int expectedCases() { return Math.multiplyExact(4,replications); }
    @Override public List<String> algorithms() { return List.of("HO","GA","FirstFit","BestFit"); }
    @Override public String phase() { return experimentPhase; }
    @Override public String scenarioName() { return "Static-V"+vmCount+"-H"+hostCount; }
    @Override public Integer searchPopulation() { return population; }
    @Override public Integer searchIterations() { return iterations; }
    @Override public ScenarioSpec specFor(Profile.CaseKey key) {
        return ScenarioGenerator.generateStatic(masterSeed,key.phase(),key.scenario(),key.replication(),vmCount,hostCount);
    }
    public SortedMap<String,String> effective() {
        var map=new TreeMap<String,String>();
        map.put("profile","stress"); map.put("experiment.kind","static_stress"); map.put("stress.schema.version","1");
        map.put("experiment.phase",experimentPhase); map.put("vm.count",Integer.toString(vmCount)); map.put("host.count",Integer.toString(hostCount));
        map.put("population",Integer.toString(population)); map.put("iterations",Integer.toString(iterations));
        map.put("replications",Integer.toString(replications)); map.put("master.seed",Long.toString(masterSeed));
        map.put("evaluation.budget",Integer.toString(evaluationBudget())); map.put("expected.cases",Integer.toString(expectedCases()));
        map.put("scenarios","Static-V"+vmCount+"-H"+hostCount); map.put("algorithms","HO,GA,FirstFit,BestFit");
        return Collections.unmodifiableSortedMap(map);
    }
}
