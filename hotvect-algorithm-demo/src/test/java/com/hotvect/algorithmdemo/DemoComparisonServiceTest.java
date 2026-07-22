package com.hotvect.algorithmdemo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.hotvect.algorithmserver.ContractViolationException;
import com.hotvect.algorithmserver.DecodedOnlineCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DemoComparisonServiceTest {
    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void onlineCandidatesComeOnlyFromDecodedOnlineProperties() {
        List<DemoComparisonService.OnlineCandidate> candidates = DemoComparisonService.onlineCandidatesFromDecoded(List.of(
                new DecodedOnlineCandidate(
                        "sku-a",
                        0.42,
                        Map.of(
                                "request", Map.of("rank", 3.0),
                                "impression", Map.of("rank", 1.0, "score", 0.9)
                        ),
                        7)));

        assertEquals(1, candidates.size());
        DemoComparisonService.OnlineCandidate candidate = candidates.getFirst();
        assertEquals("sku-a", candidate.actionId());
        assertEquals(7, candidate.originalIndex());
        assertEquals(2, candidate.metricsByView().size());
        assertEquals(3.0, candidate.metricsByView().get("online.request").rank());
        assertEquals(0.42, candidate.metricsByView().get("online.request").score());
        assertEquals(1.0, candidate.metricsByView().get("online.impression").rank());
        assertEquals(0.9, candidate.metricsByView().get("online.impression").score());
    }

    @Test
    void decodedOnlineCandidatesWithoutRankDoNotCreateViews() {
        List<DemoComparisonService.OnlineCandidate> candidates = DemoComparisonService.onlineCandidatesFromDecoded(List.of(
                new DecodedOnlineCandidate(
                        "sku-a",
                        0.42,
                        Map.of("impression", Map.of("score", 0.9)),
                        0)));

        assertEquals(0, candidates.size());
        assertFalse(DemoComparisonService.onlineViewIds(candidates).contains("online.impression"));
    }

    @Test
    void defaultViewIdsPreferFirstAlgorithmVersusOnlineImpression() {
        ArrayNode views = views(
                view("online.request", "online"),
                view("online.impression", "online"),
                view("algo-a", "algorithm"),
                view("algo-b", "algorithm"));

        assertEquals(List.of("algo-a", "online.impression"), DemoComparisonService.defaultViewIds(views));
    }

    @Test
    void defaultViewIdsFallBackToTwoAlgorithmsWhenNoOnlineViewExists() {
        ArrayNode views = views(
                view("algo-a", "algorithm"),
                view("algo-b", "algorithm"));

        assertEquals(List.of("algo-a", "algo-b"), DemoComparisonService.defaultViewIds(views));
    }

    @Test
    void defaultViewIdsUseOnePaneForOneAlgorithm() {
        ArrayNode views = views(view("algo-a", "algorithm"));

        assertEquals(List.of("algo-a"), DemoComparisonService.defaultViewIds(views));
    }

    @Test
    void resolveViewIdsRejectsUnknownRequestedViewId() {
        ArrayNode views = views(
                view("algo-a", "algorithm"),
                view("online.impression", "online"));

        ContractViolationException error = assertThrows(
                ContractViolationException.class,
                () -> DemoComparisonService.resolveViewIds(views, List.of("missing")));
        assertEquals("Unknown view_id", error.getMessage());
        assertEquals("missing", error.getDetails());
    }

    private static ArrayNode views(com.fasterxml.jackson.databind.node.ObjectNode... views) {
        ArrayNode out = OM.createArrayNode();
        for (com.fasterxml.jackson.databind.node.ObjectNode view : views) {
            out.add(view);
        }
        return out;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode view(String viewId, String kind) {
        com.fasterxml.jackson.databind.node.ObjectNode view = OM.createObjectNode();
        view.put("view_id", viewId);
        view.put("kind", kind);
        return view;
    }
}
