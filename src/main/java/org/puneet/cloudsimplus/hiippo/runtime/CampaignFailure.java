package org.puneet.cloudsimplus.hiippo.runtime;

/** A recognized campaign rejection whose stable code is recorded instead of the generic RUN_ERROR. */
public interface CampaignFailure {
    String code();
}
