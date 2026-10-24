package org.puneet.cloudsimplus.hiippo.placement;

import org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec;
import java.io.IOException;
import java.util.*;

/** Protocol v2 pure bounded search. Draw order is checked by scripts/optimizer_oracle.py. */
public final class Search {
    public enum Algorithm { HO, GA }
    public record Solution(List<Double> genes,PlacementPlan plan,PlacementObjective.Score score,long creation) {
        public Solution { genes=List.copyOf(genes); }
        /** Predicted steady power in watts; kept for protocol-v2 evidence and the independent oracle. */
        public double watts() { return score.powerW(); }
    }
    /** Empty values mean an invalid candidate; no NaN/Infinity is exposed for serialization. */
    public record Trace(int evaluation,int iteration,String phase,int member,long dominant,
                        Optional<List<Double>> genes,Optional<PlacementPlan> plan,Optional<PlacementObjective.Score> score,boolean accepted) {
        public Trace { genes=genes.map(List::copyOf); }
        public Optional<Double> watts() { return score.map(PlacementObjective.Score::powerW); }
    }
    public record Result(Optional<Solution> best,List<Trace> trace) {
        public Result { trace=List.copyOf(trace); }
    }
    /** Scalar evidence only; null scores denote infeasible candidates or no eligible feasible best yet. */
    public record Evaluation(int evaluation,int iteration,String phase,int member,boolean feasible,
                             PlacementObjective.Score score,boolean accepted,Double bestWatts,Double bestObjectiveScore) {
        public Double watts() { return score==null?null:score.powerW(); }
        public Double reservedPeakUtilization() { return score==null?null:score.reservedPeakUtilization(); }
        public Double normalizedPower() { return score==null?null:score.normalizedPower(); }
        public Double normalizedUtilization() { return score==null?null:score.normalizedUtilization(); }
        public Double objectiveScore() { return score==null?null:score.objective(); }
    }
    @FunctionalInterface public interface EvaluationSink { void accept(Evaluation evaluation) throws IOException; }
    public record StreamResult(Optional<Solution> best,int evaluations) {}
    public static StreamResult runStreaming(ScenarioSpec.Inputs inputs,Algorithm algorithm,int n,int t,long seed,
                                           EvaluationSink sink) throws IOException {
        return runStreaming(inputs,algorithm,n,t,seed,Objective.power(),sink);
    }
    public static StreamResult runStreaming(ScenarioSpec.Inputs inputs,Algorithm algorithm,int n,int t,long seed,
                                           Objective objective,EvaluationSink sink) throws IOException {
        return runStreaming(inputs,algorithm,n,t,new Random(seed),objective,sink);
    }
    static StreamResult runStreaming(ScenarioSpec.Inputs inputs,Algorithm algorithm,int n,int t,Random random,
                                    EvaluationSink sink) throws IOException {
        return runStreaming(inputs,algorithm,n,t,random,Objective.power(),sink);
    }
    static StreamResult runStreaming(ScenarioSpec.Inputs inputs,Algorithm algorithm,int n,int t,Random random,
                                     Objective objective,EvaluationSink sink) throws IOException {
        return new Search(inputs,random,budget(n,t),objective,Objects.requireNonNull(sink)).execute(algorithm,n,t);
    }
    private record Candidate(double[] genes,PlacementPlan plan,PlacementObjective.Score score,long creation) {}
    private static double objective(Candidate candidate) {
        return candidate.score==null?Double.POSITIVE_INFINITY:candidate.score.objective();
    }
    private static double power(Candidate candidate) {
        return candidate.score==null?Double.POSITIVE_INFINITY:candidate.score.powerW();
    }
    private static final Comparator<Candidate> ORDER=(a,b)-> {
        int c=Double.compare(objective(a),objective(b));
        if(c==0) c=Double.compare(power(a),power(b));
        for(int j=0;c==0 && j<a.genes.length;j++) c=Double.compare(a.genes[j],b.genes[j]);
        return c!=0?c:Long.compare(a.creation,b.creation);
    };
    // Eq14, beta=1.5: Gamma(2.5)=3*sqrt(pi)/4, Gamma(1.25) below.
    private static final double SIGMA=Math.pow((3*Math.sqrt(Math.PI)/4)*Math.sin(3*Math.PI/4)
        /(0.9064024770554771*1.5*Math.pow(2,0.25)),2.0/3);
    private final ScenarioSpec.Inputs inputs;
    private final Random random;
    private final int dimensions,budget;
    private final Objective objective;
    private final long powerNormalizer;
    private final List<Trace> trace;
    private final EvaluationSink sink;
    private int evaluations;
    private Double bestWatts;
    private Double bestObjectiveScore;
    private Search(ScenarioSpec.Inputs inputs,Random random,int budget,Objective objective,EvaluationSink sink) {
        this.inputs=inputs; this.random=Objects.requireNonNull(random); this.budget=budget; dimensions=inputs.vms().size();
        this.objective=Objects.requireNonNull(objective); powerNormalizer=PlacementObjective.normalizerWatts(inputs);
        if(powerNormalizer<=0) throw new IllegalArgumentException("Predicted power normalization must be positive");
        this.sink=sink; trace=sink==null?new ArrayList<>():null;
    }
    public static int budget(int n,int t) {
        if(n<2 || n%2!=0 || t<1) throw new IllegalArgumentException("Even N>=2 and T>=1 required");
        return Math.addExact(n,Math.multiplyExact(3,Math.multiplyExact(n,t)));
    }
    public static Result run(ScenarioSpec.Inputs inputs,Algorithm algorithm,int n,int t,long seed) {
        return run(inputs,algorithm,n,t,seed,Objective.power());
    }
    public static Result run(ScenarioSpec.Inputs inputs,Algorithm algorithm,int n,int t,long seed,Objective objective) {
        return run(inputs,algorithm,n,t,new Random(seed),objective);
    }
    /** Explicit component RNG; overload also admits fixed supplied draw fixtures. */
    public static Result run(ScenarioSpec.Inputs inputs,Algorithm algorithm,int n,int t,Random random) {
        return run(inputs,algorithm,n,t,random,Objective.power());
    }
    public static Result run(ScenarioSpec.Inputs inputs,Algorithm algorithm,int n,int t,Random random,Objective objective) {
        var search=new Search(inputs,random,budget(n,t),objective,null);
        try { return new Result(search.execute(algorithm,n,t).best(),search.trace); }
        catch(IOException impossible) { throw new AssertionError("Detailed search has no I/O sink",impossible); }
    }
    private StreamResult execute(Algorithm algorithm,int n,int t) throws IOException {
        Objects.requireNonNull(algorithm);
        var population=new ArrayList<Candidate>();
        for(int i=0;i<n;i++) population.add(evaluate(uniform(),0,"INITIAL",i,-1,null,true));
        if(algorithm==Algorithm.HO) ho(population,t); else ga(population);
        var best=Collections.min(population,ORDER);
        Optional<Solution> solution=best.plan==null?Optional.empty():Optional.of(new Solution(box(best.genes),best.plan,best.score,best.creation));
        if(evaluations!=budget) throw new IllegalStateException("Evaluation budget mismatch");
        return new StreamResult(solution,evaluations);
    }
    public static Optional<PlacementPlan> repair(ScenarioSpec.Inputs inputs,double[] genes) {
        return repair(inputs,genes,Objective.power());
    }
    public static Optional<PlacementPlan> repair(ScenarioSpec.Inputs inputs,double[] genes,Objective objective) {
        if(genes.length!=inputs.vms().size()) throw new IllegalArgumentException("Gene count mismatch");
        for(double g:genes) if(!Double.isFinite(g)) return Optional.empty();
        var ledger=new PlacementLedger(inputs); var ids=new ArrayList<Integer>(); int hosts=inputs.hosts().size();
        for(int vm=0;vm<genes.length;vm++) {
            if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Case search cancelled");
            int host=Math.min(hosts-1,(int)Math.floor(Math.clamp(genes[vm],0,1)*hosts));
            if(!ledger.canPlace(vm,host)) {
                host=-1; double least=Double.POSITIVE_INFINITY;
                for(int h=0;h<hosts;h++) if(ledger.canPlace(vm,h)) {
                    double cost=objective.kind()==Objective.Kind.POWER
                        ?ledger.incrementalWatts(vm,h)
                        :PlacementObjective.incremental(inputs,ledger,vm,h,objective);
                    if(cost<least) { host=h; least=cost; }
                }
            }
            if(host<0) return Optional.empty();
            ledger.place(vm,host); ids.add(host);
        }
        return Optional.of(new PlacementPlan(ids));
    }
    private static List<Double> box(double[] genes) { return Arrays.stream(genes).boxed().toList(); }
    private double[] uniform() {
        double[] x=new double[dimensions]; for(int j=0;j<dimensions;j++) x[j]=random.nextDouble(); return x;
    }
    private Candidate evaluate(double[] proposal,int iteration,String phase,int member,long dominant,Candidate incumbent,boolean eligible) throws IOException {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Case search cancelled");
        if(evaluations>=budget) throw new IllegalStateException("Budget exhausted");
        double[] x=proposal.clone(); boolean finite=true;
        for(int j=0;j<x.length;j++) { if(!Double.isFinite(x[j])) finite=false; else x[j]=Math.clamp(x[j],0,1); }
        var plan=finite?repair(inputs,x,objective):Optional.<PlacementPlan>empty();
        var score=plan.map(p->PlacementObjective.score(inputs,p,objective)).orElse(null);
        var candidate=new Candidate(x,plan.orElse(null),score,evaluations++);
        boolean accepted=eligible && (incumbent==null || objective(candidate)<objective(incumbent));
        if(sink==null) {
            trace.add(new Trace(evaluations,iteration,phase,member,dominant,finite?Optional.of(box(x)):Optional.empty(),
                plan,score==null?Optional.empty():Optional.of(score),accepted));
        } else {
            // Only population-eligible accepted candidates contribute; HO predators are probes.
            if(accepted && score!=null) {
                if(bestWatts==null || score.powerW()<bestWatts) bestWatts=score.powerW();
                if(bestObjectiveScore==null || score.objective()<bestObjectiveScore) bestObjectiveScore=score.objective();
            }
            sink.accept(new Evaluation(evaluations,iteration,phase,member,plan.isPresent(),
                score,accepted,bestWatts,bestObjectiveScore));
        }
        return candidate;
    }
    private Candidate update(double[] x,int t,String phase,int i,long dominant,Candidate old) throws IOException {
        var proposal=evaluate(x,t,phase,i,dominant,old,true);
        return objective(proposal)<objective(old)?proposal:old;
    }
    private Candidate tournament(List<Candidate> p) {
        Candidate best=p.get(random.nextInt(p.size()));
        for(int k=1;k<3;k++) { var c=p.get(random.nextInt(p.size())); if(ORDER.compare(c,best)<0) best=c; }
        return best;
    }
    private void ga(List<Candidate> p) throws IOException {
        int generation=0,n=p.size();
        while(evaluations<budget) {
            generation++; var next=new ArrayList<Candidate>(); next.add(Collections.min(p,ORDER));
            while(next.size()<n && evaluations<budget) {
                double[] a=tournament(p).genes.clone(),b=tournament(p).genes.clone();
                boolean cross=random.nextDouble()<0.8;
                if(cross && dimensions>1) {
                    int cut=1+random.nextInt(dimensions-1);
                    for(int j=cut;j<dimensions;j++) { double swap=a[j]; a[j]=b[j]; b[j]=swap; }
                }
                for(double[] child:new double[][]{a,b}) {
                    if(next.size()==n || evaluations==budget) break;
                    for(int j=0;j<dimensions;j++) if(random.nextDouble()<1.0/dimensions) child[j]=random.nextDouble();
                    next.add(evaluate(child,generation,"CHILD",next.size(),-1,null,true));
                }
            }
            p.clear(); p.addAll(next);
        }
    }
    private void ho(List<Candidate> p,int iterations) throws IOException {
        int n=p.size();
        for(int t=1;t<=iterations;t++) {
            var dominant=Collections.min(p,ORDER); double[] best=dominant.genes;
            for(int i=0;i<n/2;i++) {
                var old=p.get(i); double[] x=old.genes;
                int i1=1+random.nextInt(2),i2=1+random.nextInt(2),rho1=random.nextInt(2),rho2=random.nextInt(2);
                int count=1+random.nextInt(n); int[] indices=new int[n]; for(int k=0;k<n;k++) indices[k]=k;
                double[] mean=new double[dimensions];
                for(int k=0;k<count;k++) {
                    int chosen=k+random.nextInt(n-k),swap=indices[k]; indices[k]=indices[chosen]; indices[chosen]=swap;
                    for(int j=0;j<dimensions;j++) mean[j]+=p.get(indices[k]).genes[j];
                }
                for(int j=0;j<dimensions;j++) mean[j]/=count;
                double[][] h={uniform(),uniform(),uniform(),uniform(),new double[dimensions]};
                double r5=random.nextDouble();
                for(int j=0;j<dimensions;j++) { h[0][j]=i2*h[0][j]+1-rho1; h[1][j]=2*h[1][j]-1; h[3][j]=i1*h[3][j]+1-rho2; h[4][j]=r5; }
                double[] h1=h[random.nextInt(5)],h2=h[random.nextInt(5)]; double y1=random.nextDouble();
                double[] male=new double[dimensions],female=new double[dimensions];
                for(int j=0;j<dimensions;j++) male[j]=x[j]+y1*(best[j]-i1*x[j]);
                if(Math.exp(-(double)t/iterations)>0.6) {
                    for(int j=0;j<dimensions;j++) female[j]=x[j]+h1[j]*(best[j]-i2*mean[j]);
                } else if(random.nextDouble()>0.5) {
                    for(int j=0;j<dimensions;j++) female[j]=x[j]+h2[j]*(mean[j]-best[j]);
                } else Arrays.fill(female,random.nextDouble());
                var after=update(male,t,"RIVER_MALE",i,dominant.creation,old);
                p.set(i,update(female,t,"RIVER_FEMALE",i,dominant.creation,after));
            }
            for(int i=n/2;i<n;i++) {
                var old=p.get(i);
                var predator=evaluate(uniform(),t,"PREDATOR",i,dominant.creation,null,false);
                double f=2+2*random.nextDouble(),c=1+0.5*random.nextDouble(),d=2+random.nextDouble(),g=2*random.nextDouble()-1;
                double[] levy=new double[dimensions];
                for(int j=0;j<dimensions;j++) {
                    double z1=random.nextGaussian(),z2; do { z2=random.nextGaussian(); } while(z2==0);
                    levy[j]=0.05*SIGMA*z1/Math.pow(Math.abs(z2),2.0/3);
                }
                boolean close=objective.kind()==Objective.Kind.POWER
                    ?power(predator)<power(old):objective(predator)<objective(old);
                double[] r9=close?null:uniform(),defense=new double[dimensions];
                for(int j=0;j<dimensions;j++) {
                    double distance=Math.abs(predator.genes[j]-old.genes[j]);
                    double denominator=close?distance:2*distance+r9[j];
                    if(denominator==0) denominator=Double.MIN_NORMAL;
                    defense[j]=levy[j]*predator.genes[j]+(f/(c-d*Math.cos(2*Math.PI*g)))*(1/denominator);
                }
                p.set(i,update(defense,t,"DEFENSE",i,dominant.creation,old));
            }
            for(int i=0;i<n;i++) {
                double[] vector=uniform(); for(int j=0;j<dimensions;j++) vector[j]=2*vector[j]-1;
                double gaussian=random.nextGaussian(),scalar=random.nextDouble(); int choice=random.nextInt(3); double r10=random.nextDouble();
                double[] escape=new double[dimensions];
                for(int j=0;j<dimensions;j++) escape[j]=p.get(i).genes[j]+r10*(choice==0?vector[j]:choice==1?gaussian:scalar)/t;
                p.set(i,update(escape,t,"ESCAPE",i,dominant.creation,p.get(i)));
            }
        }
    }
}
