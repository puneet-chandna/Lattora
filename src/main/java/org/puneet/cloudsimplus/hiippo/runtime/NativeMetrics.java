package org.puneet.cloudsimplus.hiippo.runtime;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.puneet.cloudsimplus.hiippo.placement.*;
import org.puneet.cloudsimplus.hiippo.scenario.*;
import java.util.*;

/** Exact event intervals for the frozen full-utilization, one-cloudlet-per-VM model. */
public final class NativeMetrics {
    private NativeMetrics() {}
    /** Absolute native times; null start/terminal means not observed by the censor. */
    public record Observation(double submission,Double start,Double terminal,boolean success) {}
    public record Result(double energyJ,double energyKwh,int slaViolations,double slaRate,
                         int completed,int failed,int censored,double horizonSeconds,double releaseSeconds,
                         double observedUtilization) {}

    public static Result run(ScenarioSpec spec,PlacementPlan plan) {
        if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Case simulation cancelled");
        return run(spec,PlannedSimulation.create(spec.inputs(),plan));
    }
    static Result run(ScenarioSpec spec,PlannedSimulation run) {
        var objects=run.objects(); var simulation=objects.simulation();
        int count=objects.cloudlets().size();
        Double[] starts=new Double[count], terminals=new Double[count];
        double[] release={Double.NaN};
        long[] delivered={0};
        simulation.addOnEventProcessingListener(e->delivered[0]++);
        run.broker().addOnVmsCreatedListener(e->{ run.verifyActualPlacement(); release[0]=simulation.clock(); });
        for(var c:objects.cloudlets()) {
            int id=(int)c.getId();
            c.addOnStartListener(e->{
                run.verifyActualPlacement();
                if(starts[id]!=null || !Double.isFinite(release[0]) || c.getDcArrivalTime()!=release[0])
                    throw new IllegalStateException("Invalid common workload release");
                var vm=objects.vms().get(id);
                if(vm.getHost().getVmScheduler().getTotalAllocatedMipsForVm(vm)!=spec.inputs().vms().get(id).totalMips())
                    throw new IllegalStateException("Native reserved CPU differs from specification");
                starts[id]=c.getStartTime();
            });
            c.addOnFinishListener(e->{
                if(terminals[id]!=null) throw new IllegalStateException("Repeated cloudlet completion");
                terminals[id]=c.getFinishTime();
            });
        }
        try {
            simulation.startSync();
            while(simulation.isRunning()) {
                if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Case simulation cancelled");
                double limit=(Double.isFinite(release[0])?release[0]:0)+spec.censorSeconds();
                double processingTime=simulation.clock(); long before=delivered[0];
                // runFor advances one event timestamp, bounded by limit; no periodic CPU sampling.
                // It executes delivered events at the OLD clock before delivering the next timestamp.
                simulation.runFor(Math.max(0,limit-processingTime));
                for(var c:objects.cloudlets()) if(unsuccessful(c.getStatus()) && terminals[(int)c.getId()]==null)
                    terminals[(int)c.getId()]=processingTime;
                // Drain same-time delivery/processing at censor without advancing past it.
                if(processingTime>=limit && delivered[0]==before) break;
            }
            if(!run.placementVerified() || !Double.isFinite(release[0])) throw new IllegalStateException("Workload was not released");
            var observations=new ArrayList<Observation>();
            for(int i=0;i<count;i++) {
                var c=objects.cloudlets().get(i);
                observations.add(new Observation(release[0],starts[i],terminals[i],c.getStatus()==Cloudlet.Status.SUCCESS));
            }
            return calculate(spec,run.plan(),release[0],observations);
        } finally { if(simulation.isRunning()) simulation.terminate(); }
    }
    private static boolean unsuccessful(Cloudlet.Status status) {
        return status==Cloudlet.Status.FAILED || status==Cloudlet.Status.CANCELED || status==Cloudlet.Status.FAILED_RESOURCE_UNAVAILABLE;
    }
    public static Result calculate(ScenarioSpec spec,PlacementPlan plan,double release,List<Observation> observed) {
        plan.validate(spec.inputs());
        int count=spec.inputs().cloudlets().size(),completed=0,failed=0,censored=0,violations=0;
        if(!Double.isFinite(release) || release<0 || observed.size()!=count) throw new IllegalArgumentException("Invalid metric observations");
        double horizon=0,censor=spec.censorSeconds();
        for(int i=0;i<count;i++) {
            var c=observed.get(i);
            if(c.submission()!=release || c.start()!=null && (!Double.isFinite(c.start()) || c.start()<release)
                || c.terminal()!=null && (!Double.isFinite(c.terminal()) || c.terminal()<release || c.start()!=null && c.terminal()<c.start())
                || c.success() && (c.start()==null || c.terminal()==null)) throw new IllegalArgumentException("Invalid cloudlet times");
            if(c.terminal()==null || c.terminal()-release>censor) { censored++; violations++; }
            else {
                horizon=Math.max(horizon,c.terminal()-release);
                if(c.success()) { completed++; if((c.terminal()-c.submission())/spec.referenceSeconds().get(i)>1.10) violations++; }
                else { failed++; violations++; }
            }
        }
        if(censored>0) horizon=censor;
        if(horizon<=0) throw new IllegalArgumentException("Nonpositive workload horizon");
        var events=new TreeMap<Double,long[]>();
        int hostCount=spec.inputs().hosts().size(); boolean[] used=new boolean[hostCount];
        events.put(0.0,new long[hostCount]); events.put(horizon,new long[hostCount]);
        for(int i=0;i<count;i++) {
            int host=plan.hostIds().get(i); used[host]=true; var c=observed.get(i);
            if(c.start()==null || c.start()-release>=horizon) continue;
            double start=c.start()-release,end=c.terminal()==null?horizon:Math.min(horizon,c.terminal()-release);
            long mips=spec.inputs().vms().get(i).totalMips();
            events.computeIfAbsent(start,k->new long[hostCount])[host]+=mips;
            events.computeIfAbsent(end,k->new long[hostCount])[host]-=mips;
        }
        double energy=0,previous=0,utilizedMipsSeconds=0; long totalCapacity=0; long[] active=new long[hostCount];
        for(var host:spec.inputs().hosts()) totalCapacity=Math.addExact(totalCapacity,host.totalMips());
        for(var event:events.entrySet()) {
            double interval=event.getKey()-previous;
            for(var host:spec.inputs().hosts()) if(used[host.id()]) {
                double utilization=(double)active[host.id()]/host.totalMips();
                if(utilization<0 || utilization>1) throw new IllegalArgumentException("Invalid actual CPU utilization");
                energy+=(host.idleW()+(host.maxW()-host.idleW())*utilization)*interval;
                utilizedMipsSeconds+=active[host.id()]*interval;
                active[host.id()]+=event.getValue()[host.id()];
            }
            previous=event.getKey();
        }
        if(!Double.isFinite(energy) || energy<=0) throw new IllegalArgumentException("Invalid integrated energy");
        double observedUtilization=utilizedMipsSeconds/(totalCapacity*horizon);
        if(!Double.isFinite(observedUtilization) || observedUtilization<0 || observedUtilization>1+1e-12)
            throw new IllegalArgumentException("Invalid observed utilization");
        return new Result(energy,energy/3_600_000.0,violations,(double)violations/count,completed,failed,censored,horizon,release,
            Math.min(1.0,observedUtilization));
    }
}
