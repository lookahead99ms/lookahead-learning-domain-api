package com.lookahead.domain.db.store.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProtectedContentCacheTest {
    @Test void byteAndEntryBoundsEvictAndReturnedArraysCannotPoisonCache() {
        var cache = new ProtectedContentCache(4,2);
        cache.read("a",()->new byte[]{1,2})[0]=9;
        assertThat(cache.read("a",()->{throw new AssertionError();})).containsExactly(1,2);
        cache.read("b",()->new byte[]{3,4}); cache.read("c",()->new byte[]{5,6});
        assertThat(cache.size()).isEqualTo(2); assertThat(cache.bytes()).isEqualTo(4);
        cache.read("large",()->new byte[5]); assertThat(cache.size()).isEqualTo(2);
        var calls = new int[1];cache.read("a",()->{calls[0]++;return new byte[]{7};});assertThat(calls[0]).isEqualTo(1);
        assertThat(cache.bytes()).isLessThanOrEqualTo(4);
    }
}
