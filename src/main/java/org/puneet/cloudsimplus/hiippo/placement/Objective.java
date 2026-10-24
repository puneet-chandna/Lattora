package org.puneet.cloudsimplus.hiippo.placement;

/**
 * Versioned placement objective. Components are dimensionless and minimized:
 * normalized predicted power plus normalized reserved peak utilization.
 * SLA optimization is intentionally absent: the supported constant-full-utilization
 * model predicts no SLA differences, so configuring it is rejected before execution.
 */
public record Objective(Kind kind,double powerWeight,double utilizationWeight) {
    public enum Kind { POWER, UTILIZATION, WEIGHTED }
    public static final int VERSION=1;
    public static final String POWER_NORMALIZATION="sum_host_max_w";
    public static final String UTILIZATION_NORMALIZATION="reserved_peak_fraction";
    public static final String SLA_MODEL="unsupported_constant_full";

    public Objective {
        if(kind==null) throw new IllegalArgumentException("Objective kind is required");
        for(double weight:new double[]{powerWeight,utilizationWeight})
            if(!Double.isFinite(weight) || weight<0 || weight>1)
                throw new IllegalArgumentException("Objective weights must be finite and from 0.0 to 1.0");
        if(Math.abs(powerWeight+utilizationWeight-1.0)>1e-15)
            throw new IllegalArgumentException("Objective weights must sum to 1.0");
    }
    public static Objective power() { return new Objective(Kind.POWER,1.0,0.0); }
    public static Objective utilization() { return new Objective(Kind.UTILIZATION,0.0,1.0); }
    public static Objective weighted(double powerWeight,double utilizationWeight) {
        return new Objective(Kind.WEIGHTED,powerWeight,utilizationWeight);
    }
    public String kindName() { return kind.name().toLowerCase(java.util.Locale.ROOT); }
}
