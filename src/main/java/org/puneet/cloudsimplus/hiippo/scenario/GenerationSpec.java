package org.puneet.cloudsimplus.hiippo.scenario;

import java.util.*;

/** Resolved, RNG-free generation inputs for configured static scenarios: what to emit, not how to draw it. */
public record GenerationSpec(List<HostClass> hostClasses,VmDemands vms,Workload workload) {
    public GenerationSpec {
        Objects.requireNonNull(hostClasses); Objects.requireNonNull(vms); Objects.requireNonNull(workload);
        hostClasses=List.copyOf(hostClasses);
        if(hostClasses.isEmpty()) throw new IllegalArgumentException("At least one host class is required");
    }
    /** One emitted host class repeated {@code count} times in file order; no RNG is consumed. */
    public record HostClass(int count,int pes,long mipsPerPe,long ramMiB,long bwMbps,long storageMiB,int idleW,int maxW) {
        public HostClass {
            if(count<1) throw new IllegalArgumentException("Host class count must be positive");
            if(pes<1 || mipsPerPe<1 || ramMiB<1 || bwMbps<1 || storageMiB<1)
                throw new IllegalArgumentException("Host class resources must be positive");
            if(idleW<0 || maxW<idleW) throw new IllegalArgumentException("Invalid host power");
            Math.multiplyExact(pes,mipsPerPe);
        }
        public long totalMips() { return Math.multiplyExact(pes,mipsPerPe); }
    }
    /** Ascending candidate values; duplicates act as weights. Each VM draws one value per field, in field order. */
    public record VmDemands(int count,List<Integer> pes,List<Long> mipsPerPe,List<Long> ramMiB,
                            List<Long> bwMbps,List<Long> storageMiB) {
        public VmDemands {
            if(count<1) throw new IllegalArgumentException("VM count must be positive");
            pes=ascendingCounts(pes,"pes");
            mipsPerPe=ascendingPositive(mipsPerPe,"mipsPerPe");
            ramMiB=ascendingPositive(ramMiB,"ramMiB");
            bwMbps=ascendingPositive(bwMbps,"bwMbps");
            storageMiB=ascendingPositive(storageMiB,"storageMiB");
        }
    }
    /** Workload lengths in whole minutes; each VM draws one length from its own stream. */
    public record Workload(List<Integer> lengthSeconds) {
        public Workload {
            var values=List.copyOf(Objects.requireNonNull(lengthSeconds));
            if(values.isEmpty()) throw new IllegalArgumentException("Workload lengths must not be empty");
            int previous=0;
            for(int value:values) {
                if(value<1) throw new IllegalArgumentException("Workload lengths must be positive");
                if(value<previous) throw new IllegalArgumentException("Workload lengths must be non-decreasing");
                previous=value;
            }
            lengthSeconds=values;
        }
    }
    private static List<Long> ascendingPositive(List<Long> values,String field) {
        var copy=List.copyOf(Objects.requireNonNull(values));
        if(copy.isEmpty()) throw new IllegalArgumentException("VM "+field+" must not be empty");
        long previous=0;
        for(long value:copy) {
            if(value<1) throw new IllegalArgumentException("VM "+field+" must be positive");
            if(value<previous) throw new IllegalArgumentException("VM "+field+" must be non-decreasing");
            previous=value;
        }
        return copy;
    }
    private static List<Integer> ascendingCounts(List<Integer> values,String field) {
        var copy=List.copyOf(Objects.requireNonNull(values));
        if(copy.isEmpty()) throw new IllegalArgumentException("VM "+field+" must not be empty");
        int previous=0;
        for(int value:copy) {
            if(value<1) throw new IllegalArgumentException("VM "+field+" must be positive");
            if(value<previous) throw new IllegalArgumentException("VM "+field+" must be non-decreasing");
            previous=value;
        }
        return copy;
    }
}
