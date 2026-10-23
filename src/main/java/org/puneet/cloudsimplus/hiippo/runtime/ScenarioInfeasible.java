package org.puneet.cloudsimplus.hiippo.runtime;

/** Proved-infeasible configured scenario: the recorded reason is the evidence, never a retry. */
public final class ScenarioInfeasible extends IllegalArgumentException implements CampaignFailure {
    public ScenarioInfeasible(String reason) { super(reason); }
    @Override public String code() { return "SCENARIO_INFEASIBLE"; }
}
