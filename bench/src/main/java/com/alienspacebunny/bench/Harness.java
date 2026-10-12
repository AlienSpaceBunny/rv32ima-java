package com.alienspacebunny.bench;

import com.alienspacebunny.bench.Workloads.Workload;
import com.alienspacebunny.emu.MemoryBus;
import com.alienspacebunny.emu.RV32IMACore;
import java.io.PrintStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Runs the selected workloads against one bus in one process and prints one CSV row per
 * invocation. Each process warms every selected workload together (at least {@code
 * --min-rounds} complete rounds and {@code --warmup-seconds} of accumulated timed execution),
 * then takes {@code --samples} measured rounds. Every invocation, including warmup, is verified.
 *
 * <p>Arguments: {@code --bus NAME} (required), {@code --workloads a,b|all}, {@code
 * --warmup-seconds S}, {@code --min-rounds R}, {@code --samples N}, {@code --label L}, {@code
 * --reverse} (reverse workload order), {@code --skew K} (negative check: perturb every
 * expectation by K), {@code --scale full|test}, {@code --decoded-cache N} (give every guest core a
 * decoded instruction cache of N slots), {@code --attempts N} (run every guest workload through
 * {@code stepAttempts} with N attempts per call and an AP-like controller, instead of {@code step}
 * batches), {@code --step-loop} (with {@code --attempts N}: run each attempt as a {@code
 * step(count=1)} call between the same callbacks, V-32's AP loop before {@code stepAttempts}) and
 * {@code --cache-stats} (print each cached core's counters to stderr at the end, one
 * {@code cache-stats} CSV row per workload).
 */
final class Harness {
    static final String HEADER = "label,runtime,bus,workload,phase,round,nanos,work,gc_count,gc_ms,alloc_bytes";

    private Harness() {}

    static void main(String[] args, Map<String, IntFunction<MemoryBus>> buses) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try {
            run(args, buses, out);
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.err.println("FAIL: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args, Map<String, IntFunction<MemoryBus>> buses, PrintStream out) {
        String busName = null;
        String selection = "all";
        double warmupSeconds = 10;
        int minRounds = 3;
        int samples = 3;
        String label = "";
        boolean reverse = false;
        long skew = 0;
        Workloads.Scale scale = Workloads.Scale.FULL;
        int cacheSlots = 0;
        int attempts = 0;
        boolean stepLoop = false;
        boolean cacheStats = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--bus" -> busName = args[++i];
                case "--workloads" -> selection = args[++i];
                case "--warmup-seconds" -> warmupSeconds = Double.parseDouble(args[++i]);
                case "--min-rounds" -> minRounds = Integer.parseInt(args[++i]);
                case "--samples" -> samples = Integer.parseInt(args[++i]);
                case "--label" -> label = args[++i];
                case "--reverse" -> reverse = true;
                case "--skew" -> skew = Long.parseLong(args[++i]);
                case "--scale" -> scale = Workloads.Scale.valueOf(args[++i].toUpperCase(Locale.ROOT));
                case "--decoded-cache" -> cacheSlots = Integer.parseInt(args[++i]);
                case "--attempts" -> attempts = Integer.parseInt(args[++i]);
                case "--step-loop" -> stepLoop = true;
                case "--cache-stats" -> cacheStats = true;
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        IntFunction<MemoryBus> factory = buses.get(busName);
        if (factory == null) {
            throw new IllegalArgumentException("--bus must be one of " + buses.keySet());
        }

        List<Workload> workloads = select(Workloads.all(scale, skew, cacheSlots, attempts, stepLoop), selection);
        if (reverse) {
            Collections.reverse(workloads);
        }
        MemoryBus bus = factory.apply(Workloads.MEM_SIZE);
        for (Workload w : workloads) {
            w.prepareOnce(bus);
        }

        Runner runner = new Runner(out, label, busName, bus);
        out.println(HEADER);
        for (Workload w : workloads) {
            runner.invoke(w, "first", 0);
        }
        long warmupNanos = (long) (warmupSeconds * 1e9);
        int round = 0;
        while (round < minRounds || runner.timedNanos < warmupNanos) {
            round++;
            for (Workload w : workloads) {
                runner.invoke(w, null, round);
            }
        }
        for (int s = 1; s <= samples; s++) {
            for (Workload w : workloads) {
                runner.invoke(w, "measured", s);
            }
        }
        if (cacheStats) {
            printCacheStats(label, workloads, runner.work);
        }
    }

    static final String CACHE_STATS_HEADER =
            "cache-stats,label,workload,slots,invocations,instructions,fills,bypasses,delegations,flushes";

    /** Every cached core's counters over the whole process, as CSV rows on stderr. */
    private static void printCacheStats(String label, List<Workload> workloads, Map<Workload, long[]> work) {
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        err.println(CACHE_STATS_HEADER);
        for (Workload w : workloads) {
            RV32IMACore core = w.core();
            if (core == null || !core.hasDecodedCache()) {
                continue;
            }
            RV32IMACore.DecodedCacheStats stats = core.getDecodedCacheStats();
            long[] totals = work.getOrDefault(w, new long[2]);
            err.println(String.join(
                    ",",
                    "cache-stats",
                    label,
                    w.name,
                    Integer.toString(Workloads.cacheSlots()),
                    Long.toString(totals[0]),
                    Long.toString(totals[1]),
                    Long.toString(stats.fills()),
                    Long.toString(stats.bypasses()),
                    Long.toString(stats.delegations()),
                    Long.toString(stats.flushes())));
        }
    }

    private static List<Workload> select(List<Workload> all, String selection) {
        if (selection.equals("all")) {
            return new ArrayList<>(all);
        }
        List<Workload> chosen = new ArrayList<>();
        for (String name : selection.split(",", -1)) {
            Workload match = all.stream()
                    .filter(w -> w.name.equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown workload " + name));
            chosen.add(match);
        }
        return chosen;
    }

    static String runtime() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null ? "native" : "jvm";
    }

    /** Times, verifies and reports one invocation at a time. */
    private static final class Runner {
        private final PrintStream out;
        private final String label;
        private final String busName;
        private final MemoryBus bus;
        private final List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long timedNanos;

        /** Per workload: invocations and total work, warmup included. */
        final Map<Workload, long[]> work = new IdentityHashMap<>();

        Runner(PrintStream out, String label, String busName, MemoryBus bus) {
            this.out = out;
            this.label = label;
            this.busName = busName;
            this.bus = bus;
        }

        /** {@code phase == null} is warmup: verified and accumulated, but not printed. */
        void invoke(Workload w, String phase, int round) {
            w.prepare(bus);
            long[] gcBefore = gcTotals();
            long allocBefore = allocatedBytes();
            long start = System.nanoTime();
            long work = w.run(bus);
            long nanos = System.nanoTime() - start;
            long allocAfter = allocatedBytes();
            long[] gcAfter = gcTotals();
            w.verify(bus);
            timedNanos += nanos;
            long[] totals = this.work.computeIfAbsent(w, k -> new long[2]);
            totals[0]++;
            totals[1] += work;
            if (phase != null) {
                long alloc = allocBefore < 0 || allocAfter < 0 ? -1 : allocAfter - allocBefore;
                out.println(String.join(
                        ",",
                        label,
                        runtime(),
                        busName,
                        w.name,
                        phase,
                        Integer.toString(round),
                        Long.toString(nanos),
                        Long.toString(work),
                        Long.toString(gcAfter[0] - gcBefore[0]),
                        Long.toString(gcAfter[1] - gcBefore[1]),
                        Long.toString(alloc)));
            }
        }

        private long[] gcTotals() {
            long count = 0;
            long millis = 0;
            for (GarbageCollectorMXBean gc : gcs) {
                count += Math.max(0, gc.getCollectionCount());
                millis += Math.max(0, gc.getCollectionTime());
            }
            return new long[] {count, millis};
        }

        private long allocatedBytes() {
            try {
                if (threads instanceof com.sun.management.ThreadMXBean sun) {
                    return sun.getCurrentThreadAllocatedBytes();
                }
            } catch (UnsupportedOperationException _) {
                // Not every runtime reports allocation; the column is -1 then.
            }
            return -1;
        }
    }
}
