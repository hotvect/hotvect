package com.hotvect.api.data.ranking;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RankingDecisionTest {

    @Test
    public void testEqualsNonNullProperties() {
        var decision1 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.4).build();
        var decision2 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.4).build();
        var decision3 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.39).build();
        var decision4 =  RankingDecision.builder(3, "jump").withScore(5.2).withProbability(0.4).build();
        var decision5 =  RankingDecision.builder(3, "kick").withScore(5.1).withProbability(0.4).build();
        var decision6 =  RankingDecision.builder(4, "jump").withScore(5.1).withProbability(0.4).build();
        Assertions.assertTrue(decision1.equals(decision2) && decision2.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision3) || decision3.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision4) || decision4.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision5) || decision5.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision6) || decision6.equals(decision1));
    }

    @Test
    public void testEqualsNullProbability() {
        var decision1 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(null).build();
        var decision2 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(null).build();
        var decision3 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.4).build();
        var decision4 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.4).build();
        var decision5 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.4).build();
        var decision6 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.4).build();
        Assertions.assertTrue(decision1.equals(decision2) && decision2.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision3) || decision3.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision4) || decision4.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision5) || decision5.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision6) || decision6.equals(decision1));
    }

    @Test
    public void testEqualsNullScore() {
        var decision1 =  RankingDecision.builder(3, "jump").withScore(null).withProbability(0.4).build();
        var decision2 =  RankingDecision.builder(3, "jump").withScore(null).withProbability(0.4).build();
        var decision3 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.4).build();
        var decision4 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.4).build();
        var decision5 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.4).build();
        var decision6 =  RankingDecision.builder(3, "jump").withScore(5.1).withProbability(0.4).build();
        Assertions.assertTrue(decision1.equals(decision2) && decision2.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision3) || decision3.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision4) || decision4.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision5) || decision5.equals(decision1));
        Assertions.assertFalse(decision1.equals(decision6) || decision6.equals(decision1));
    }

}
