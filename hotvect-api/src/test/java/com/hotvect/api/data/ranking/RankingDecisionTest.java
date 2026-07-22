package com.hotvect.api.data.ranking;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RankingDecisionTest {

    @Test
    public void testEqualsNonNullProperties() {
        var decision1 =  RankingDecision.builder("jump", "jump").withScore(5.1).withProbability(0.4).build();
        var decision2 =  RankingDecision.builder("jump", "jump").withScore(5.1).withProbability(0.4).build();
        var decision3 =  RankingDecision.builder("jump", "jump").withScore(5.1).withProbability(0.39).build();
        var decision4 =  RankingDecision.builder("jump", "jump").withScore(5.2).withProbability(0.4).build();
        var decision5 =  RankingDecision.builder("kick", "kick").withScore(5.1).withProbability(0.4).build();
        Assertions.assertEquals(decision1, decision2);
        Assertions.assertEquals(decision2, decision1);
        Assertions.assertNotEquals(decision1, decision3);
        Assertions.assertNotEquals(decision3, decision1);
        Assertions.assertNotEquals(decision1, decision4);
        Assertions.assertNotEquals(decision4, decision1);
        Assertions.assertNotEquals(decision1, decision5);
        Assertions.assertNotEquals(decision5, decision1);
    }

    @Test
    public void testEqualsNullProbability() {
        var decision1 =  RankingDecision.builder("jump", "jump").withScore(5.1).withProbability(null).build();
        var decision2 =  RankingDecision.builder("jump", "jump").withScore(5.1).withProbability(null).build();
        var decision3 =  RankingDecision.builder("jump", "jump").withScore(5.1).withProbability(0.4).build();
        Assertions.assertEquals(decision1, decision2);
        Assertions.assertEquals(decision2, decision1);
        Assertions.assertNotEquals(decision1, decision3);
        Assertions.assertNotEquals(decision3, decision1);
    }

    @Test
    public void testEqualsNullScore() {
        var decision1 =  RankingDecision.builder("jump", "jump").withScore(null).withProbability(0.4).build();
        var decision2 =  RankingDecision.builder("jump", "jump").withScore(null).withProbability(0.4).build();
        var decision3 =  RankingDecision.builder("jump", "jump").withScore(5.1).withProbability(0.4).build();
        Assertions.assertEquals(decision1, decision2);
        Assertions.assertEquals(decision2, decision1);
        Assertions.assertNotEquals(decision1, decision3);
        Assertions.assertNotEquals(decision3, decision1);
    }

    @Test
    public void testEqualsIncludesActionIndex() {
        var decision1 =  RankingDecision.builder("jump", 3, "jump").withScore(5.1).withProbability(0.4).build();
        var decision2 =  RankingDecision.builder("jump", 4, "jump").withScore(5.1).withProbability(0.4).build();
        var decision3 =  RankingDecision.builder("jump", 3, "jump").withScore(5.1).withProbability(0.4).build();
        Assertions.assertNotEquals(decision1, decision2);
        Assertions.assertEquals(decision1, decision3);
        Assertions.assertEquals(decision1.hashCode(), decision3.hashCode());
    }

    @SuppressWarnings("removal")
    @Test
    public void deprecatedConstructorsSynthesizeActionIdsFromActionIndex() {
        Assertions.assertEquals("3", new RankingDecision<>(3, "jump").actionId());
        Assertions.assertEquals("3", new RankingDecision<>(3, 5.1, "jump").actionId());
        Assertions.assertEquals("3", new RankingDecision<>(3, "jump", 0.4).actionId());
        Assertions.assertEquals("3", new RankingDecision<>(3, 5.1, "jump", 0.4).actionId());
    }

    @SuppressWarnings("removal")
    @Test
    public void canonicalConstructorSynthesizesLegacyNullActionIdFromActionIndex() {
        var decision = new RankingDecision<>(null, 3, 5.1, "jump", null, java.util.Map.of());

        Assertions.assertEquals("3", decision.actionId());
    }
}
