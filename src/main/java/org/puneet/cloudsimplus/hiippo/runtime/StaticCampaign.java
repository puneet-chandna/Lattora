package org.puneet.cloudsimplus.hiippo.runtime;

import org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec;
import java.util.*;

/** Resolved static campaign: what the protocol-neutral runner needs, with no evidence-format knowledge. */
interface StaticCampaign {
    SortedMap<String,String> effective();
    int expectedCases();
    int evaluationBudget();
    long masterSeed();
    List<String> algorithms();
    int replications();
    String phase();
    String scenarioName();
    /** Candidates per search case; null when the campaign configures no search algorithm. */
    Integer searchPopulation();
    /** Iterations per search case; null when the campaign configures no search algorithm. */
    Integer searchIterations();
    ScenarioSpec specFor(Profile.CaseKey key);
    default boolean search(String algorithm) { return algorithm.equals("HO") || algorithm.equals("GA"); }
}
