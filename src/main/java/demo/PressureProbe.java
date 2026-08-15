package demo;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import org.testng.internal.ConstructorOrMethod;

/**
 * Isolated test of Claim 2: under memory pressure, are the soft-referenced reflective handles (7.13)
 * gentler on the GC than the strongly-held ones of older TestNG (7.12)?
 *
 * <p>It builds and RETAINS a big population of {@link ConstructorOrMethod} — exactly what a large
 * {@code @Factory} suite keeps alive — then squeezes the heap with a retained blob plus steady
 * garbage while repeatedly using the handles. With interning on the underlying {@code Method}s are
 * held softly, so the GC may drop idle ones under pressure and they are rebuilt on next use; older
 * TestNG (and the kill switch) pin every one. We report how hard the collector worked and how big
 * the surviving live set was each way.
 *
 * <p>Knobs: {@code -Ddemo.wrappers} (how many handles to retain), {@code -Ddemo.pressure.retainMb}
 * (size of the competing retained blob), {@code -Ddemo.pressure.seconds} (how long to churn).
 */
public final class PressureProbe {

  @SuppressWarnings("unused")
  static class Sample {
    void a(String s) {}

    void b(int i) {}

    Object c() {
      return null;
    }

    void d(long x, long y) {}

    String e(Object o) {
      return null;
    }
  }

  public static void main(String[] args) throws Exception {
    int wrappers = Integer.getInteger("demo.wrappers", 300_000);
    int seconds = Integer.getInteger("demo.pressure.seconds", 20);
    int retainMb = Integer.getInteger("demo.pressure.retainMb", 0);
    // Strategy: soft = ConstructorOrMethod with interning on (the PR); off = ConstructorOrMethod with
    // interning off (a strong handle per wrapper, like pre-7.13); strong = a strong-reference dedup
    // cache (juherr's simpler alternative). soft/off are driven by -Dtestng.reflection.intern.
    String strategy = System.getProperty("demo.strategy", "soft");
    String label = padStrategy(strategy) + " w=" + wrappers + " retain=" + retainMb + "MB";

    // Build W wrappers over FRESH Method copies — one handle per @Factory instance/clone. With no
    // dedup every copy is pinned; both cache strategies collapse them to one handle per member.
    Method[] decl = Sample.class.getDeclaredMethods();
    List<?> model;
    IntFunction<Method> lookup;
    if ("strong".equalsIgnoreCase(strategy)) {
      StrongDedupCache sharedCache = new StrongDedupCache();
      List<StrongWrapper> wraps = new ArrayList<>(wrappers);
      for (int i = 0; i < wrappers; i++) {
        wraps.add(new StrongWrapper(fresh(decl[i % decl.length]), sharedCache));
      }
      model = wraps;
      lookup = i -> wraps.get(i).get();
    } else {
      List<ConstructorOrMethod> wraps = new ArrayList<>(wrappers);
      for (int i = 0; i < wrappers; i++) {
        wraps.add(new ConstructorOrMethod(fresh(decl[i % decl.length])));
      }
      model = wraps;
      lookup = i -> wraps.get(i).getMethod();
    }

    // A retained blob that competes with the handles for heap, so the collector is forced to reclaim
    // whatever it can (the soft handles) to make room.
    List<byte[]> retained = new ArrayList<>();
    for (int i = 0; i < retainMb; i++) {
      retained.add(new byte[1024 * 1024]);
    }

    AtomicBoolean stop = new AtomicBoolean(false);
    Thread allocator = startChurn(stop);

    boolean completed = false;
    long touches = 0;
    long start = System.currentTimeMillis();
    try {
      Random r = new Random(1);
      long deadline = start + seconds * 1000L;
      while (System.currentTimeMillis() < deadline) {
        for (int k = 0; k < 20_000; k++) {
          lookup.apply(r.nextInt(wrappers)); // use the handle (forces a rebuild if it was reclaimed)
          touches++;
        }
      }
      completed = true;
    } catch (Throwable t) {
      System.out.println(label + " | RUN-ERROR: " + t.getClass().getSimpleName());
    } finally {
      stop.set(true);
      allocator.interrupt();
    }

    // Keep model + retained strongly reachable through the measurement.
    long liveSet = liveSetAfterGc();
    report(label, completed, System.currentTimeMillis() - start, touches, liveSet);
    if (model.size() < 0 || retained.size() < 0) {
      throw new IllegalStateException(); // unreachable; pins model + retained past the measurement
    }
  }

  private static Thread startChurn(AtomicBoolean stop) {
    Thread t =
        new Thread(
            () -> {
              java.util.ArrayDeque<byte[]> churn = new java.util.ArrayDeque<>();
              while (!stop.get()) {
                churn.addLast(new byte[256 * 1024]);
                if (churn.size() > 64) {
                  churn.removeFirst();
                }
              }
            },
            "churn");
    t.setDaemon(true);
    t.start();
    return t;
  }

  private static long liveSetAfterGc() throws InterruptedException {
    System.gc();
    Thread.sleep(200);
    System.gc();
    Thread.sleep(200);
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private static void report(
      String label, boolean completed, long wallMs, long touches, long liveSet) {
    long gcCount = 0;
    long gcTime = 0;
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (gc.getCollectionCount() > 0) {
        gcCount += gc.getCollectionCount();
      }
      if (gc.getCollectionTime() > 0) {
        gcTime += gc.getCollectionTime();
      }
    }
    double gcPct = wallMs > 0 ? (100.0 * gcTime / wallMs) : 0;
    System.out.printf(
        "PRESSURE | %s | completed=%s | wallMs=%d | gcCount=%d | gcTimeMs=%d (%.0f%% of wall) | liveSetMB=%.0f | touches=%d%n",
        label, completed, wallMs, gcCount, gcTime, gcPct, liveSet / (1024.0 * 1024.0), touches);
  }

  private static Method fresh(Method proto) throws NoSuchMethodException {
    // A brand-new Method copy each call, so the no-dedup case really does pin one per wrapper.
    return Sample.class.getDeclaredMethod(proto.getName(), proto.getParameterTypes());
  }

  private static String padStrategy(String strategy) {
    return String.format("%-6s", strategy);
  }

  /**
   * A minimal wrapper for the strong-cache strategy: {declaring class, shared strong handle} — the
   * same two-reference shape as a strong-holding ConstructorOrMethod, so the comparison is fair.
   */
  static final class StrongWrapper {
    @SuppressWarnings("unused") // held only to match ConstructorOrMethod's footprint
    private final Class<?> declaringClass;

    private final Executable handle;

    StrongWrapper(Executable seed, StrongDedupCache cache) {
      this.declaringClass = seed.getDeclaringClass();
      this.handle = cache.intern(seed);
    }

    Method get() {
      return (Method) handle;
    }
  }

  private PressureProbe() {}
}
