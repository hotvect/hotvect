package com.hotvect.offlineutils.export;

import com.hotvect.api.data.AvailableAction;
import com.hotvect.api.data.ranking.RankingDecision;
import com.hotvect.api.data.ranking.RankingOutcome;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RankingActionIds {
    private RankingActionIds() {
    }

    static <ACTION> RankedDecisions<ACTION> rankedDecisions(List<RankingDecision<ACTION>> decisions) {
        Map<String, Integer> actionIdToRank = new HashMap<>();
        Map<String, RankingDecision<ACTION>> actionIdToDecision = new HashMap<>();
        for (int i = 0; i < decisions.size(); i++) {
            RankingDecision<ACTION> decision = decisions.get(i);
            String actionId = decision.actionId();
            if (actionIdToRank.put(actionId, i) != null) {
                throw new IllegalArgumentException("Ranker returned duplicate decision for action id: " + actionId);
            }
            actionIdToDecision.put(actionId, decision);
        }
        return new RankedDecisions<>(actionIdToRank, actionIdToDecision);
    }

    static <OUTCOME, ACTION> Map<String, RankingOutcome<OUTCOME, ACTION>> outcomesByActionId(
            List<RankingOutcome<OUTCOME, ACTION>> outcomes
    ) {
        Map<String, RankingOutcome<OUTCOME, ACTION>> ret = new HashMap<>();
        for (RankingOutcome<OUTCOME, ACTION> outcome : outcomes) {
            String actionId = outcome.rankingDecision().actionId();
            if (ret.put(actionId, outcome) != null) {
                throw new IllegalArgumentException("Example contains duplicate outcome for action id: " + actionId);
            }
        }
        return ret;
    }

    static <ACTION> Set<String> requestActionIds(List<AvailableAction<ACTION>> actions) {
        Set<String> ret = new LinkedHashSet<>();
        for (AvailableAction<ACTION> action : actions) {
            ret.add(action.actionId());
        }
        return ret;
    }

    static void validateActionIdCoverage(
            String source,
            String itemName,
            Set<String> actualIds,
            Set<String> requestActionIds,
            int expectedSize
    ) {
        if (actualIds.size() != expectedSize) {
            String verb = source.equals("Example") ? "has" : "returned";
            throw new IllegalArgumentException(
                    source + " " + verb + " " + actualIds.size() + " " + itemName + "s for " + expectedSize + " actions"
            );
        }
        for (String actionId : actualIds) {
            if (!requestActionIds.contains(actionId)) {
                String verb = source.equals("Example") ? "contains" : "returned";
                throw new IllegalArgumentException(
                        source + " " + verb + " " + itemName + " for unknown action id: " + actionId
                );
            }
        }
        for (String actionId : requestActionIds) {
            if (!actualIds.contains(actionId)) {
                String verb = source.equals("Example") ? "is missing" : "did not return";
                throw new IllegalArgumentException(
                        source + " " + verb + " " + itemName + " for action id: " + actionId
                );
            }
        }
    }

    static void validateKnownActionIds(
            String source,
            String itemName,
            Set<String> actualIds,
            Set<String> requestActionIds
    ) {
        for (String actionId : actualIds) {
            if (!requestActionIds.contains(actionId)) {
                String verb = source.equals("Example") ? "contains" : "returned";
                throw new IllegalArgumentException(
                        source + " " + verb + " " + itemName + " for unknown action id: " + actionId
                );
            }
        }
    }

    record RankedDecisions<ACTION>(
            Map<String, Integer> actionIdToRank,
            Map<String, RankingDecision<ACTION>> actionIdToDecision
    ) {
    }
}
