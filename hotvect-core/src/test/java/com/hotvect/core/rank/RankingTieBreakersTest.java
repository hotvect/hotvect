package com.hotvect.core.rank;

import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RankingTieBreakersTest {
    private static final int TIE_BREAK_HASH_SEED = 0x2510C4E5;

    @Test
    void stableTieBreakKeyIsDeterministicForExampleAndAction() {
        long first = RankingTieBreakers.stableTieBreakKey("example", "sku-a");
        long second = RankingTieBreakers.stableTieBreakKey("example", "sku-a");

        assertEquals(first, second);
    }

    @Test
    void stableTieBreakKeyUsesExampleAndActionId() {
        long baseline = RankingTieBreakers.stableTieBreakKey("example", "sku-a");

        assertNotEquals(baseline, RankingTieBreakers.stableTieBreakKey("other-example", "sku-a"));
        assertNotEquals(baseline, RankingTieBreakers.stableTieBreakKey("example", "sku-b"));
    }

    @Test
    void stableTieBreakKeySaltsSyntheticActionIdsByExample() {
        long baseline = RankingTieBreakers.stableTieBreakKey("example", "0");

        assertNotEquals(baseline, RankingTieBreakers.stableTieBreakKey("other-example", "0"));
    }

    @Test
    void stableTieBreakKeyUsesMurmur32Hash() {
        long expected = Hashing.murmur3_32_fixed(TIE_BREAK_HASH_SEED)
                .newHasher()
                .putInt("example".length())
                .putUnencodedChars("example")
                .putInt("sku-a".length())
                .putUnencodedChars("sku-a")
                .hash()
                .padToLong();

        assertEquals(expected, RankingTieBreakers.stableTieBreakKey("example", "sku-a"));
    }

    @Test
    void stableTieBreakKeyRequiresActionId() {
        assertThrows(IllegalArgumentException.class, () -> RankingTieBreakers.stableTieBreakKey("example", ""));
    }

    @Test
    void stableTieBreakKeyRequiresExampleId() {
        assertThrows(IllegalArgumentException.class, () -> RankingTieBreakers.stableTieBreakKey("", "sku-a"));
    }
}
