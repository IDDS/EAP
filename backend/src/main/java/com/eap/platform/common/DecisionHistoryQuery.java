package com.eap.platform.common;
public class DecisionHistoryQuery extends ObservationHistoryQuery {
    @Override protected String timeField(){return "decidedAt";}
}
