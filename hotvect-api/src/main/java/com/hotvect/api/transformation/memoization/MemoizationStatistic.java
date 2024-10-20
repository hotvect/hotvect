package com.hotvect.api.transformation.memoization;

import com.codahale.metrics.Timer;
import com.codahale.metrics.UniformReservoir;
import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.data.FeatureNamespace;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class MemoizationStatistic {
    private MemoizationStatistic(){}

    // Use of non-volatile field is intentional (no problem missing some updates)
    private static Map<Namespace, Timer> CACHED_TIMERS = null;
    private static Map<Namespace, Timer> NON_CACHEDTIMERS = null;

    public static Timer.Context startTimer(Namespace columnName, boolean cached) {
        if(CACHED_TIMERS == null){
            return null;
        }
        Map<Namespace, Timer> timers;
        if(cached){
            timers = CACHED_TIMERS;
        } else {
            timers = NON_CACHEDTIMERS;
        }

        if(timers == null){
            return null;
        }
        return timers.computeIfAbsent(columnName, _k -> new Timer(new UniformReservoir(10000))).time();
    }

    public static Map<String, Object> result(){
        Map<Namespace, Timer> timers = CACHED_TIMERS;
        if(timers == null){
            return ImmutableMap.of("disabled", true);
        }

        Map<String, Object> result = new HashMap<>();

        addTimes(true, CACHED_TIMERS, result);
        addTimes(false, NON_CACHEDTIMERS, result);
        return result;
    }

    private static void addTimes(boolean cached, Map<Namespace, Timer> timers, Map<String, Object> result) {
        for (Map.Entry<Namespace, Timer> entry : timers.entrySet()) {
            Map<String, Object> e = new HashMap<>();
            e.put("is_cached", cached);
            e.put("is_feature", entry.getKey() instanceof FeatureNamespace);
            e.put("count", entry.getValue().getCount());
            e.put("meanRate", entry.getValue().getMeanRate());
            e.put("p50", entry.getValue().getSnapshot().getMedian());
            e.put("p75", entry.getValue().getSnapshot().get75thPercentile());
            e.put("p95", entry.getValue().getSnapshot().get95thPercentile());
            e.put("p98", entry.getValue().getSnapshot().get98thPercentile());
            e.put("p99", entry.getValue().getSnapshot().get99thPercentile());
            e.put("p999", entry.getValue().getSnapshot().get999thPercentile());
            result.put(entry.getKey().toString(), e);
        }
    }

    public static void reset(){
        CACHED_TIMERS = new ConcurrentHashMap<>();
        NON_CACHEDTIMERS = new ConcurrentHashMap<>();
    }

}
