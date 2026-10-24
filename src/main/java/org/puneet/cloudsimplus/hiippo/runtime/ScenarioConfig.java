package org.puneet.cloudsimplus.hiippo.runtime;

import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.puneet.cloudsimplus.hiippo.placement.Objective;
import org.puneet.cloudsimplus.hiippo.scenario.*;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Versioned static scenario configuration: strict JSON in, resolved document and generation spec out.
 * Every rejection is a single JSON-path-addressed sentence shared verbatim with the Python model.
 */
public record ScenarioConfig(String name,long masterSeed,int replications,List<String> algorithms,
                             SearchEffort optimizer,Objective objective,long powerMaxWSum,
                             GenerationSpec generationSpec,Request request,
                             String configSha256,String canonicalJson) implements StaticCampaign {
    public static final int CONFIG_VERSION=1;
    public static final int SCHEMA_VERSION=2;
    public static final String PHASE="scenario";
    public static final String PROTOCOL="static_scenario_v2";
    public static final String CONFIG_NAME="[a-z][a-z0-9._-]{0,63}";
    public static final List<String> CANONICAL_ALGORITHMS=List.of("HO","GA","FirstFit","BestFit");
    public static final String MODEL_LIMITS="Only constant full utilization (1.0), one cloudlet per VM, VmSchedulerSpaceShared, "
        +"CloudletSchedulerSpaceShared, no migrations, no overcommit, a single simultaneous release and a linear idle-to-max power "
        +"curve over the host's own total MIPS are simulated. PE, MIPS, RAM, bandwidth and storage limits are static reservation "
        +"admission checks; they are not simulated network or storage contention.";
    public static final String EVIDENCE_LIMIT="Scalar traces check order, budgets and eligible-best consistency; they cannot "
        +"independently replay discarded candidates or guarantee detection of coherent non-best scalar tampering.";
    public static final String ALLOCATION_TIMING="End-to-end search including synchronous buffered scalar trace writing; "
        +"not pure CPU or directly comparable to frozen allocation timing.";
    public static final int OBJECTIVE_VERSION=Objective.VERSION;
    public static final String SLA_UNSUPPORTED="objective.kind sla is unsupported: constant full utilization predicts zero SLA violations for every feasible placement; use power, utilization or weighted until a contention model is supported";
    public static final int MAX_HOST_CLASSES=16, MAX_HOSTS=100000, MAX_VMS=100000, MAX_REPLICATIONS=10000,
        MAX_DECLARED_VALUES=4096, MIN_SECONDS=1, MAX_SECONDS=1440, EXACT_EVENT_DIVISOR=8;

    /** Even candidate count and positive iterations; both present only when a search algorithm is configured. */
    public record SearchEffort(int population,int iterations) {}
    /** The exact request document that was resolved; compared by content, never by identity. */
    public record Request(byte[] bytes) {
        public Request { bytes=Objects.requireNonNull(bytes).clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public boolean equals(Object other) { return other instanceof Request request && Arrays.equals(bytes,request.bytes); }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
        @Override public String toString() { return "Request["+bytes.length+" bytes]"; }
    }

    private static final List<String> DEMANDS=List.of("pes","mips_per_pe","ram_mib","bw_mbps","storage_mib");
    private static final Map<String,int[]> HOST_FIELDS=Map.ofEntries(
        Map.entry("count",new int[]{1,MAX_HOSTS}), Map.entry("pes",new int[]{1,Integer.MAX_VALUE}),
        Map.entry("mips_per_pe",new int[]{1,Integer.MAX_VALUE}), Map.entry("ram_mib",new int[]{1,Integer.MAX_VALUE}),
        Map.entry("bw_mbps",new int[]{1,Integer.MAX_VALUE}), Map.entry("storage_mib",new int[]{1,Integer.MAX_VALUE}),
        Map.entry("idle_w",new int[]{0,Integer.MAX_VALUE}));
    private static final List<String> HOST_KEYS=List.of("count","pes","mips_per_pe","ram_mib","bw_mbps","storage_mib","idle_w","max_w");
    private static final List<String> TOP_KEYS=List.of("config_version","name","master_seed","replications","algorithms","objective","optimizer","hosts","vms","workload");
    private static final List<String> OPTIMIZER_KEYS=List.of("population","iterations");
    private static final List<String> OBJECTIVE_KEYS=List.of("kind","weights");
    private static final List<String> WEIGHT_KEYS=List.of("power","utilization");
    private static final List<String> WORKLOAD_KEYS=List.of("length_seconds","utilization","release_seconds");

    public ScenarioConfig {
        Objects.requireNonNull(name); Objects.requireNonNull(algorithms); Objects.requireNonNull(objective);
        Objects.requireNonNull(generationSpec); Objects.requireNonNull(request); Objects.requireNonNull(configSha256);
        Objects.requireNonNull(canonicalJson);
        algorithms=List.copyOf(algorithms);
        if(replications<1 || replications>MAX_REPLICATIONS) throw new IllegalArgumentException("Invalid replication count");
        if(powerMaxWSum<1) throw new IllegalArgumentException("Predicted power normalization must be positive");
        if(!sha256(request.bytes()).equals(configSha256))
            throw new IllegalArgumentException("Requested configuration does not match its recorded digest");
    }

    public static ScenarioConfig load(Path file) throws IOException {
        return resolve(read(file));
    }
    /** Resolve an already-read request document; used by {@link #load} and by exact-parity tests. */
    static ScenarioConfig resolve(byte[] bytes) {
        String text;
        try {
            text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch(CharacterCodingException error) { throw new IllegalArgumentException("config must contain valid UTF-8"); }
        return resolve(parse(text),sha256(bytes),new Request(bytes));
    }
    private static byte[] read(Path file) throws IOException {
        try { return Files.readAllBytes(file); }
        catch(IOException|RuntimeException error) {
            throw new IOException("config file is missing or unreadable: "+file,error);
        }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static ScenarioConfig resolve(Map<String,Object> document,String sha256,Request request) {
        unknown(document,TOP_KEYS,"");
        if(!integer(document.get("config_version"),1,1)) throw new IllegalArgumentException("config_version must be the integer 1");
        String name=string(document.get("name"));
        if(name==null || !name.matches(CONFIG_NAME))
            throw new IllegalArgumentException("name must be a string matching "+CONFIG_NAME);
        BigDecimal seed=number(document.get("master_seed"));
        if(seed==null || !integral(seed) || !fits(seed,Long.MIN_VALUE,Long.MAX_VALUE))
            throw new IllegalArgumentException("master_seed must be a signed 64-bit integer");
        if(!integer(document.get("replications"),1,MAX_REPLICATIONS))
            throw new IllegalArgumentException("replications must be an integer from 1 to "+MAX_REPLICATIONS);
        int replications=number(document.get("replications")).intValueExact();
        List<String> algorithms=algorithms(document.get("algorithms"));
        Objective objective=objective(document.get("objective"));
        SearchEffort optimizer=optimizer(document.get("optimizer"),algorithms);
        List<GenerationSpec.HostClass> hostClasses=hostClasses(document.get("hosts"));
        int hostCount=0; long powerMaxWSum=0;
        for(var hostClass:hostClasses) {
            hostCount=Math.addExact(hostCount,hostClass.count());
            powerMaxWSum=Math.addExact(powerMaxWSum,Math.multiplyExact((long)hostClass.count(),hostClass.maxW()));
        }
        if(hostCount<1 || hostCount>MAX_HOSTS)
            throw new IllegalArgumentException("total host count must be from 1 to "+MAX_HOSTS);
        if(powerMaxWSum<1)
            throw new IllegalArgumentException("hosts must declare a positive total max_w power ceiling");
        var vms=demands(document.get("vms"),hostClasses);
        var workload=workload(document.get("workload"));
        if((long)vms.count()*hostCount>Integer.MAX_VALUE)
            throw new IllegalArgumentException("vms.count multiplied by total host count must not exceed "+Integer.MAX_VALUE);
        if((long)replications*algorithms.size()>Integer.MAX_VALUE)
            throw new IllegalArgumentException("replications multiplied by algorithms must not exceed "+Integer.MAX_VALUE);
        long worstMips=0, worstSeconds=0, worstPes=0;
        for(long value:vms.mipsPerPe()) worstMips=Math.max(worstMips,value);
        for(long value:workload.lengthSeconds()) worstSeconds=Math.max(worstSeconds,value);
        for(int value:vms.pes()) worstPes=Math.max(worstPes,value);
        try {
            for(long mips:vms.mipsPerPe()) for(long seconds:workload.lengthSeconds()) Math.multiplyExact(mips,seconds);
            for(int pes:vms.pes()) Math.multiplyExact((long)pes,Math.multiplyExact(worstMips,worstSeconds));
        } catch(ArithmeticException error) {
            throw new IllegalArgumentException("a VM's workload length in MI does not fit a signed 64-bit integer",error);
        }
        if(optimizer!=null) {
            int search=0, baseline=0;
            for(String algorithm:algorithms) if(algorithm.equals("HO")||algorithm.equals("GA")) search++; else baseline++;
            long budget=(long)optimizer.population()+3L*optimizer.population()*optimizer.iterations();
            if((long)replications*(search*budget+baseline)>Integer.MAX_VALUE)
                throw new IllegalArgumentException("total expected evaluations must not exceed "+Integer.MAX_VALUE);
        }
        var spec=new GenerationSpec(hostClasses,vms,workload);
        return new ScenarioConfig(name,seed.longValueExact(),replications,algorithms,optimizer,objective,powerMaxWSum,
            spec,request,sha256,canonical(name,seed.longValueExact(),replications,algorithms,optimizer,objective,
            powerMaxWSum,hostClasses,vms,workload));
    }

    private static Objective objective(Object value) {
        if(value==null) return Objective.power();
        if(!(value instanceof Map<?,?>)) throw new IllegalArgumentException("objective must be an object with a kind");
        @SuppressWarnings("unchecked") var declared=unknown((Map<String,Object>)value,OBJECTIVE_KEYS,"objective");
        Object rawKind=declared.get("kind");
        if(!(rawKind instanceof String kind) || !List.of("power","utilization","sla","weighted").contains(kind))
            throw new IllegalArgumentException("objective.kind must be one of power,utilization,sla,weighted");
        if(kind.equals("sla")) throw new IllegalArgumentException(SLA_UNSUPPORTED);
        Object rawWeights=declared.get("weights");
        if(!kind.equals("weighted")) {
            if(rawWeights!=null) throw new IllegalArgumentException("objective.weights is only valid when objective.kind is weighted");
            return kind.equals("power")?Objective.power():Objective.utilization();
        }
        if(!(rawWeights instanceof Map<?,?>)) throw new IllegalArgumentException("objective.weights is required when objective.kind is weighted");
        var weights=cast((Map<?,?>)rawWeights);
        for(String key:weights.keySet()) {
            if(key.equals("sla")) throw new IllegalArgumentException("objective.weights.sla is unsupported: constant full utilization cannot predict SLA differences");
            if(!WEIGHT_KEYS.contains(key)) throw new IllegalArgumentException("unknown field: objective.weights."+key);
        }
        BigDecimal power=number(weights.get("power")), utilization=number(weights.get("utilization"));
        if(power==null || utilization==null || !weight(power) || !weight(utilization)
            || power.add(utilization).compareTo(BigDecimal.ONE)!=0)
            throw new IllegalArgumentException("objective.weights must contain power and utilization numbers from 0.0 to 1.0 whose sum is exactly 1.0");
        return Objective.weighted(power.doubleValue(),utilization.doubleValue());
    }
    private static boolean weight(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO)>=0 && value.compareTo(BigDecimal.ONE)<=0;
    }
    private static List<String> algorithms(Object value) {
        if(value==null) return CANONICAL_ALGORITHMS;
        if(!(value instanceof List<?> declared) || declared.isEmpty())
            throw new IllegalArgumentException("algorithms must be a non-empty array without duplicates, drawn from "+String.join(",",CANONICAL_ALGORITHMS));
        var selected=new TreeSet<Integer>();
        for(var entry:declared) {
            int index=entry instanceof String text?CANONICAL_ALGORITHMS.indexOf(text):-1;
            if(index<0 || !selected.add(index))
                throw new IllegalArgumentException("algorithms must be a non-empty array without duplicates, drawn from "+String.join(",",CANONICAL_ALGORITHMS));
        }
        var ordered=new ArrayList<String>();
        for(int index:selected) ordered.add(CANONICAL_ALGORITHMS.get(index));
        return List.copyOf(ordered);
    }
    private static boolean search(List<String> algorithms) { return algorithms.contains("HO") || algorithms.contains("GA"); }
    private static SearchEffort optimizer(Object value,List<String> algorithms) {
        if(!search(algorithms)) {
            if(value!=null) throw new IllegalArgumentException("optimizer is unused: algorithms exclude HO and GA");
            return null;
        }
        if(!(value instanceof Map<?,?>)) throw new IllegalArgumentException("optimizer is required when HO or GA is configured");
        @SuppressWarnings("unchecked") var document=unknown((Map<String,Object>)value,OPTIMIZER_KEYS,"optimizer");
        int population=Integer.MIN_VALUE, iterations=Integer.MIN_VALUE;
        Object rawPopulation=document.get("population");
        if(integer(rawPopulation,2,Integer.MAX_VALUE)) population=number(rawPopulation).intValueExact();
        if(population==Integer.MIN_VALUE || population%2!=0)
            throw new IllegalArgumentException("optimizer.population must be an even integer of at least 2");
        Object rawIterations=document.get("iterations");
        if(integer(rawIterations,1,Integer.MAX_VALUE)) iterations=number(rawIterations).intValueExact();
        if(iterations==Integer.MIN_VALUE)
            throw new IllegalArgumentException("optimizer.iterations must be an integer of at least 1");
        try {
            if(Math.addExact(population,Math.multiplyExact(3,Math.multiplyExact(population,iterations)))>Integer.MAX_VALUE)
                throw new IllegalArgumentException("evaluation budget must not exceed "+Integer.MAX_VALUE);
        } catch(ArithmeticException error) { throw new IllegalArgumentException("evaluation budget must not exceed "+Integer.MAX_VALUE,error); }
        return new SearchEffort(population,iterations);
    }
    private static List<GenerationSpec.HostClass> hostClasses(Object value) {
        if(!(value instanceof List<?> classes) || classes.size()>MAX_HOST_CLASSES)
            throw new IllegalArgumentException("hosts must be an array of at most "+MAX_HOST_CLASSES+" classes");
        var result=new ArrayList<GenerationSpec.HostClass>();
        for(int index=0;index<classes.size();index++) {
            String path="hosts["+index+"]";
            if(!(classes.get(index) instanceof Map<?,?> raw))
                throw new IllegalArgumentException(path+".count must be an integer from 1 to "+MAX_HOSTS);
            @SuppressWarnings("unchecked") var declared=unknown((Map<String,Object>)raw,HOST_KEYS,path);
            int[] values=new int[HOST_KEYS.size()];
            for(int field=0;field<HOST_KEYS.size();field++) {
                String key=HOST_KEYS.get(field);
                int[] bounds=HOST_FIELDS.getOrDefault(key,new int[]{0,Integer.MAX_VALUE});
                int minimum=key.equals("max_w")?values[HOST_KEYS.indexOf("idle_w")]:bounds[0];
                if(!integer(declared.get(key),minimum,bounds[1]))
                    throw new IllegalArgumentException(path+"."+key+" must be an integer from "+minimum+" to "+bounds[1]);
                values[field]=number(declared.get(key)).intValueExact();
            }
            try { Math.multiplyExact((long)values[1],(long)values[2]); }
            catch(ArithmeticException error) {
                throw new IllegalArgumentException(path+".pes multiplied by mips_per_pe must not exceed "+Long.MAX_VALUE,error);
            }
            result.add(new GenerationSpec.HostClass(values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7]));
        }
        return List.copyOf(result);
    }
    private static GenerationSpec.VmDemands demands(Object value,List<GenerationSpec.HostClass> hostClasses) {
        if(!(value instanceof Map<?,?>))
            throw new IllegalArgumentException("vms.count must be an integer from 1 to "+MAX_VMS);
        @SuppressWarnings("unchecked") var declared=unknown((Map<String,Object>)value,
            List.copyOf(concat(List.of("count"),DEMANDS)),"vms");
        if(!integer(declared.get("count"),1,MAX_VMS))
            throw new IllegalArgumentException("vms.count must be an integer from 1 to "+MAX_VMS);
        var lists=new ArrayList<List<Long>>();
        for(String field:DEMANDS) {
            var resolved=values(declared.get(field),1,Integer.MAX_VALUE,"vms."+field,
                "vms."+field+" must be a non-empty non-decreasing integer list or a min/max integer range",
                "vms."+field+" must not declare more than "+MAX_DECLARED_VALUES+" values");
            if(field.equals("mips_per_pe")) for(long declaredValue:resolved) if(declaredValue%EXACT_EVENT_DIVISOR!=0)
                throw new IllegalArgumentException("vms.mips_per_pe value "+declaredValue+" must be a multiple of "+EXACT_EVENT_DIVISOR
                    +": the 0.125 s event interval must stay exact");
            long largest=0; for(var hostClass:hostClasses) largest=Math.max(largest,hostField(hostClass,field));
            long demanded=resolved.getLast();
            if(demanded>largest)
                throw new IllegalArgumentException("vms."+field+" value "+demanded+" exceeds every host class "+field+" ("+largest+")");
            lists.add(resolved);
        }
        var pes=new ArrayList<Integer>(); for(long drawn:lists.get(0)) pes.add((int)drawn);
        return new GenerationSpec.VmDemands(number(declared.get("count")).intValueExact(),List.copyOf(pes),lists.get(1),
            lists.get(2),lists.get(3),lists.get(4));
    }
    private static GenerationSpec.Workload workload(Object value) {
        if(!(value instanceof Map<?,?>))
            throw new IllegalArgumentException("workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range from "+MIN_SECONDS+" to "+MAX_SECONDS);
        @SuppressWarnings("unchecked") var declared=unknown((Map<String,Object>)value,WORKLOAD_KEYS,"workload");
        var message="workload.length_seconds must be a non-empty non-decreasing integer list or a min/max integer range from "+MIN_SECONDS+" to "+MAX_SECONDS;
        var seconds=new ArrayList<Integer>();
        for(long drawn:values(declared.get("length_seconds"),MIN_SECONDS,MAX_SECONDS,"workload.length_seconds",message,message))
            seconds.add((int)drawn);
        if(!equalsNumber(declared.get("utilization"),1))
            throw new IllegalArgumentException("workload.utilization must be 1.0: constant full utilization is the only simulated model");
        if(!equalsNumber(declared.get("release_seconds"),0))
            throw new IllegalArgumentException("workload.release_seconds must be 0.0: a single simultaneous release is the only simulated model");
        return new GenerationSpec.Workload(List.copyOf(seconds));
    }
    private static boolean equalsNumber(Object value,long expected) {
        return value instanceof BigDecimal declared && declared.compareTo(BigDecimal.valueOf(expected))==0;
    }
    /** Ascending resolved list: an explicit list keeps its weights, a min/max range expands to [min..max]. */
    private static List<Long> values(Object value,int minimum,int maximum,String path,String message,String countMessage) {
        List<Long> resolved=new ArrayList<>();
        if(value instanceof List<?> declared) {
            if(declared.isEmpty()) throw new IllegalArgumentException(message);
            long previous=minimum-1L;
            for(var entry:declared) {
                BigDecimal number=number(entry);
                if(number==null || !integral(number) || !fits(number,minimum,maximum) || number.longValueExact()<previous)
                    throw new IllegalArgumentException(message);
                previous=number.longValueExact(); resolved.add(previous);
            }
        } else if(value instanceof Map<?,?> declared) {
            unknown(cast(declared),List.of("min","max"),path);
            BigDecimal min=number(declared.get("min")), max=number(declared.get("max"));
            if(min==null || max==null || !integral(min) || !integral(max) || !fits(min,minimum,maximum) || !fits(max,minimum,maximum)
                || min.longValueExact()>max.longValueExact())
                throw new IllegalArgumentException(message);
            // Count before expanding: a bounded range must never allocate before its own cap is checked.
            if(max.subtract(min).longValueExact()+1>MAX_DECLARED_VALUES) throw new IllegalArgumentException(countMessage);
            for(long number=min.longValueExact();number<=max.longValueExact();number++) resolved.add(number);
        } else throw new IllegalArgumentException(message);
        if(resolved.size()>MAX_DECLARED_VALUES) throw new IllegalArgumentException(countMessage);
        return List.copyOf(resolved);
    }
    private static long hostField(GenerationSpec.HostClass hostClass,String field) {
        return switch(field) {
            case "pes" -> hostClass.pes(); case "mips_per_pe" -> hostClass.mipsPerPe(); case "ram_mib" -> hostClass.ramMiB();
            case "bw_mbps" -> hostClass.bwMbps(); default -> hostClass.storageMiB();
        };
    }
    private static String canonical(String name,long masterSeed,int replications,List<String> algorithms,SearchEffort optimizer,
                                    Objective objective,long powerMaxWSum,List<GenerationSpec.HostClass> hostClasses,
                                    GenerationSpec.VmDemands vms,GenerationSpec.Workload workload) {
        var document=new LinkedHashMap<String,Object>();
        document.put("config_version",CONFIG_VERSION); document.put("name",name); document.put("master_seed",masterSeed);
        document.put("replications",replications); document.put("algorithms",algorithms);
        var objectiveRecord=new LinkedHashMap<String,Object>();
        objectiveRecord.put("kind",objective.kindName()); objectiveRecord.put("version",OBJECTIVE_VERSION);
        var weights=new LinkedHashMap<String,Object>();
        weights.put("power",objective.powerWeight()); weights.put("utilization",objective.utilizationWeight());
        objectiveRecord.put("weights",weights);
        var normalization=new LinkedHashMap<String,Object>();
        normalization.put("power",Objective.POWER_NORMALIZATION); normalization.put("power_max_w_sum",powerMaxWSum);
        normalization.put("utilization",Objective.UTILIZATION_NORMALIZATION);
        objectiveRecord.put("normalization",normalization);
        document.put("objective",objectiveRecord);
        if(optimizer!=null) {
            var record=new LinkedHashMap<String,Object>(); record.put("population",optimizer.population()); record.put("iterations",optimizer.iterations());
            document.put("optimizer",record);
        }
        var hosts=new ArrayList<Map<String,Object>>();
        for(var hostClass:hostClasses) {
            var record=new LinkedHashMap<String,Object>();
            record.put("count",hostClass.count()); record.put("pes",hostClass.pes()); record.put("mips_per_pe",hostClass.mipsPerPe());
            record.put("ram_mib",hostClass.ramMiB()); record.put("bw_mbps",hostClass.bwMbps()); record.put("storage_mib",hostClass.storageMiB());
            record.put("idle_w",hostClass.idleW()); record.put("max_w",hostClass.maxW()); hosts.add(record);
        }
        document.put("hosts",hosts);
        var demands=new LinkedHashMap<String,Object>();
        demands.put("count",vms.count()); demands.put("pes",vms.pes()); demands.put("mips_per_pe",vms.mipsPerPe());
        demands.put("ram_mib",vms.ramMiB()); demands.put("bw_mbps",vms.bwMbps()); demands.put("storage_mib",vms.storageMiB());
        document.put("vms",demands);
        var length=new LinkedHashMap<String,Object>();
        length.put("length_seconds",workload.lengthSeconds()); length.put("utilization",1.0); length.put("release_seconds",0.0);
        document.put("workload",length);
        return new com.google.gson.GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(document)+"\n";
    }

    @Override public String phase() { return PHASE; }
    @Override public String scenarioName() { return name; }
    @Override public Objective objective() { return objective; }
    @Override public Integer searchPopulation() { return optimizer==null?null:optimizer.population(); }
    @Override public Integer searchIterations() { return optimizer==null?null:optimizer.iterations(); }
    public Integer population() { return searchPopulation(); }
    public Integer iterations() { return searchIterations(); }
    public int vmCount() { return generationSpec.vms().count(); }
    public int hostClassCount() { return generationSpec.hostClasses().size(); }
    public int hostCount() {
        int total=0; for(var hostClass:generationSpec.hostClasses()) total=Math.addExact(total,hostClass.count());
        return total;
    }
    public int evaluationBudget() {
        return optimizer==null?0:Math.addExact(optimizer.population(),Math.multiplyExact(3,Math.multiplyExact(optimizer.population(),optimizer.iterations())));
    }
    public int expectedCases() { return Math.multiplyExact(replications,algorithms.size()); }
    public int expectedEvaluations() {
        int search=0, baseline=0;
        for(String algorithm:algorithms) if(this.search(algorithm)) search++; else baseline++;
        return Math.addExact(Math.multiplyExact(replications,Math.multiplyExact(search,evaluationBudget())),Math.multiplyExact(replications,baseline));
    }
    // Records cannot hold mutable state; memoize into a static map keyed by the immutable record value.
    private static final Map<ScenarioConfig,List<Feasibility.Result>> CLASSIFICATIONS=new WeakHashMap<>();
    /** Per-replication classification; deterministic RNG-only generation, no simulation, computed once per config. */
    public List<Feasibility.Result> feasibility() {
        var computed=CLASSIFICATIONS.get(this);
        if(computed==null) {
            computed=new ArrayList<Feasibility.Result>();
            for(int replication=0;replication<replications;replication++)
                computed.add(Feasibility.classify(ScenarioGenerator.configuredInputs(masterSeed,PHASE,name,replication,generationSpec),replication));
            CLASSIFICATIONS.put(this,computed);
        }
        return computed;
    }
    @Override public ScenarioSpec specFor(Profile.CaseKey key) {
        var feasibility=feasibility().get(key.replication());
        if(feasibility.kind().equals("INFEASIBLE_PROVED")) throw new ScenarioInfeasible(feasibility.reason());
        return ScenarioGenerator.generateConfigured(masterSeed,PHASE,name,key.replication(),generationSpec);
    }
    @Override public SortedMap<String,String> effective() {
        var map=new TreeMap<String,String>();
        map.put("profile","scenario"); map.put("experiment.kind","static_scenario"); map.put("scenario.schema.version",Integer.toString(SCHEMA_VERSION));
        map.put("experiment.phase",PHASE); map.put("config.name",name); map.put("config.sha256",configSha256);
        map.put("master.seed",Long.toString(masterSeed)); map.put("replication.count",Integer.toString(replications));
        map.put("algorithms",String.join(",",algorithms)); map.put("objective.kind",objective.kindName());
        map.put("objective.version",Integer.toString(OBJECTIVE_VERSION));
        map.put("objective.weights.power",Double.toString(objective.powerWeight()));
        map.put("objective.weights.utilization",Double.toString(objective.utilizationWeight()));
        map.put("objective.normalization.power",Objective.POWER_NORMALIZATION);
        map.put("objective.normalization.power_max_w_sum",Long.toString(powerMaxWSum));
        map.put("objective.normalization.utilization",Objective.UTILIZATION_NORMALIZATION);
        map.put("objective.sla.model",Objective.SLA_MODEL);
        map.put("vm.count",Integer.toString(vmCount()));
        map.put("host.count",Integer.toString(hostCount())); map.put("host.classes",Integer.toString(hostClassCount()));
        map.put("expected.cases",Integer.toString(expectedCases())); map.put("expected.evaluations",Integer.toString(expectedEvaluations()));
        map.put("utilization.model","constant_full"); map.put("utilization.value","1.0");
        map.put("workload.length.seconds",generationSpec.workload().lengthSeconds().getFirst()+","+generationSpec.workload().lengthSeconds().getLast());
        if(optimizer!=null) {
            map.put("evaluation.budget",Integer.toString(evaluationBudget()));
            map.put("optimizer.population",Integer.toString(optimizer.population()));
            map.put("optimizer.iterations",Integer.toString(optimizer.iterations()));
        }
        return Collections.unmodifiableSortedMap(map);
    }

    private static List<String> concat(List<String> first,List<String> second) {
        var all=new ArrayList<>(first); all.addAll(second); return all;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Map<?,?> value) { return (Map<String,Object>)value; }
    private static Map<String,Object> unknown(Map<?,?> document,List<String> allowed,String path) {
        var object=cast(document);
        for(String key:object.keySet()) if(!allowed.contains(key))
            throw new IllegalArgumentException("unknown field: "+(path.isEmpty()?key:path+"."+key));
        return object;
    }
    private static String string(Object value) { return value instanceof String text?text:null; }
    private static BigDecimal number(Object value) { return value instanceof BigDecimal decimal?decimal:null; }
    private static boolean integral(BigDecimal value) { return value.stripTrailingZeros().scale()<=0; }
    private static boolean fits(BigDecimal value,long minimum,long maximum) {
        return integral(value) && value.compareTo(BigDecimal.valueOf(minimum))>=0 && value.compareTo(BigDecimal.valueOf(maximum))<=0;
    }
    private static boolean integer(Object value,int minimum,int maximum) {
        BigDecimal declared=number(value);
        return declared!=null && fits(declared,minimum,maximum);
    }

    /** Non-lenient JSON reader: duplicate keys, non-finite numbers and trailing content are all rejections. */
    private static Map<String,Object> parse(String text) {
        try(var reader=new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            Object value=read(reader,"");
            if(reader.peek()!=JsonToken.END_DOCUMENT) throw new JsonSyntaxException("trailing content after the root object");
            if(!(value instanceof Map<?,?> root)) throw new JsonSyntaxException("the root value must be an object");
            return cast(root);
        } catch(IllegalArgumentException error) { throw error; }
        catch(Exception error) { throw new IllegalArgumentException("invalid JSON: "+detail(error)); }
    }
    private static String detail(Exception error) {
        String message=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
        int line=message.indexOf(" at line ");
        String detail=line>=0?"malformed"+message.substring(line):message;
        int newline=detail.indexOf('\n');
        return newline<0?detail:detail.substring(0,newline);
    }
    private static Object read(JsonReader reader,String path) throws IOException {
        return switch(reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                var object=new LinkedHashMap<String,Object>();
                while(reader.hasNext()) {
                    String key=reader.nextName(), child=path.isEmpty()?key:path+"."+key;
                    if(object.containsKey(key)) throw new IllegalArgumentException("duplicate field: "+child);
                    object.put(key,read(reader,child));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                var array=new ArrayList<Object>();
                while(reader.hasNext()) array.add(read(reader,path+"["+array.size()+"]"));
                reader.endArray(); yield array;
            }
            case STRING -> reader.nextString();
            case NUMBER -> {
                String literal=reader.nextString();
                if(literal.equals("NaN")||literal.equals("Infinity")||literal.equals("-Infinity"))
                    throw new IllegalArgumentException("invalid JSON: non-finite number");
                if(!literal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
                    throw new IllegalArgumentException("invalid JSON: malformed number "+literal);
                yield new BigDecimal(literal);
            }
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> { reader.nextNull(); yield null; }
            default -> throw new JsonSyntaxException("unexpected token "+reader.peek()+" at '"+(path.isEmpty()?"root":path)+"'");
        };
    }
}
