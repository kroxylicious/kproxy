/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious;

import org.apache.kafka.common.protocol.ApiKeys;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.infra.Blackhole;

import io.kroxylicious.proxy.filter.FilterInvoker;
import io.kroxylicious.proxy.filter.FilterInvokers;

public class MyBenchmark {

    @org.openjdk.jmh.annotations.State(Scope.Benchmark)
    public static class BenchState {
        volatile FilterInvoker instanceOfInvokerFilterHasTwoInterfaces = FilterInvokers.instanceOfInvoker(new TwoInterfaceFilter());
        volatile FilterInvoker mapInvokerFilterHasTwoInterfaces = FilterInvokers.mapInvoker(new TwoInterfaceFilter());
        volatile FilterInvoker fieldInvokerFilterHasTwoInterfaces = FilterInvokers.fieldInvoker(new TwoInterfaceFilter());
        volatile FilterInvoker arrayInvokerFilterHasTwoInterfaces = FilterInvokers.arrayInvoker(new TwoInterfaceFilter());
        volatile FilterInvoker mapInvokerFilterHasOneInterface = FilterInvokers.mapInvoker(new OneInterfaceFilter());
        volatile FilterInvoker fieldInvokerFilterHasOneInterface = FilterInvokers.fieldInvoker(new OneInterfaceFilter());
        volatile FilterInvoker instanceOfInvokerFilterHasOneInterface = FilterInvokers.instanceOfInvoker(new OneInterfaceFilter());
        volatile FilterInvoker arrayInvokerFilterHasOneInterface = FilterInvokers.arrayInvoker(new OneInterfaceFilter());
    }

    @Benchmark
    public void testInstanceOfInvokerFilterHasTwoInterfaces(BenchState state, Blackhole blackhole) {
        invoke(blackhole, state.instanceOfInvokerFilterHasTwoInterfaces);
    }

    @Benchmark
    public void testArrayInvokerFilterHasTwoInterfaces(BenchState state, Blackhole blackhole) {
        invoke(blackhole, state.arrayInvokerFilterHasTwoInterfaces);
    }

    @Benchmark
    public void testMapInvokerFilterHasTwoInterfaces(BenchState state, Blackhole blackhole) {
        invoke(blackhole, state.mapInvokerFilterHasTwoInterfaces);
    }

    @Benchmark
    public void testFieldInvokerFilterHasTwoInterfaces(BenchState state, Blackhole blackhole) {
        invoke(blackhole, state.fieldInvokerFilterHasTwoInterfaces);
    }

    @Benchmark
    public void testMapInvokerFilterHasOneInterface(BenchState state, Blackhole blackhole) {
        invoke(blackhole, state.mapInvokerFilterHasOneInterface);
    }

    @Benchmark
    public void testFieldInvokerFilterHasOneInterface(BenchState state, Blackhole blackhole) {
        invoke(blackhole, state.fieldInvokerFilterHasOneInterface);
    }

    @Benchmark
    public void testInstanceOfInvokerFilterHasOneInterface(BenchState state, Blackhole blackhole) {
        invoke(blackhole, state.instanceOfInvokerFilterHasOneInterface);
    }

    @Benchmark
    public void testArrayInvokerFilterHasOneInterface(BenchState state, Blackhole blackhole) {
        invoke(blackhole, state.arrayInvokerFilterHasOneInterface);
    }

    private static void invoke(Blackhole blackhole, FilterInvoker filter) {
        blackhole.consume(filter.shouldHandleRequest(ApiKeys.PRODUCE, ApiKeys.PRODUCE.latestVersion()));
        blackhole.consume(filter.shouldHandleRequest(ApiKeys.FETCH, ApiKeys.FETCH.latestVersion()));
        blackhole.consume(filter.shouldHandleResponse(ApiKeys.PRODUCE, ApiKeys.PRODUCE.latestVersion()));
        blackhole.consume(filter.shouldHandleResponse(ApiKeys.FETCH, ApiKeys.FETCH.latestVersion()));
    }

}
