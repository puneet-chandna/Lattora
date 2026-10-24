package org.puneet.cloudsimplus.hiippo.runtime;

import org.puneet.cloudsimplus.hiippo.placement.PlacementObjective;
import org.puneet.cloudsimplus.hiippo.placement.PlacementPlan;
import org.puneet.cloudsimplus.hiippo.placement.Search;
import org.puneet.cloudsimplus.hiippo.scenario.ScenarioSpec;
import java.io.IOException;
import java.nio.file.Path;

/** Durable case evidence owned by one static campaign run; the runner never knows the concrete schema. */
public interface CampaignEvidence extends AutoCloseable {
    Path directory();
    int workers();
    void begin(Profile.CaseKey key) throws IOException;
    void specification(ScenarioSpec spec) throws IOException;
    void evaluation(Search.Evaluation value) throws IOException;
    void liveProgress(String phase,Search.Evaluation evaluation) throws IOException;
    void success(ScenarioSpec spec,PlacementPlan plan,PlacementObjective.Score score,int evaluations,NativeMetrics.Result metrics,long nanos) throws IOException;
    void failed(ScenarioSpec spec,String code,String message) throws IOException;
    void fail(String code,String message) throws IOException;
    void complete() throws IOException;
    @Override void close() throws IOException;
}
