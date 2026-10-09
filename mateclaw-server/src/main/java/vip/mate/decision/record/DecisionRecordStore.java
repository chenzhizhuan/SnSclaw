package vip.mate.decision.record;

import vip.mate.decision.api.DecisionOutcome;
import vip.mate.decision.api.DecisionValue;

public interface DecisionRecordStore {
    void insert(DecisionRecord record);
    void outcome(String id, DecisionOutcome outcome, DecisionValue actualValue);
}
