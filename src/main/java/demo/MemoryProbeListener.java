package demo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;

/**
 * Takes a single, deterministic memory reading at the moment the suite starts executing.
 *
 * <p>TestNG materialises the <em>entire</em> method model before it invokes the first test method,
 * so by the time {@link #beforeInvocation} fires for the very first time, all
 * {@code instances * methodsPerInstance} {@code ConstructorOrMethod} wrappers — and, with interning
 * off, all their distinct reflective handles — are already live. We are inside a listener callback
 * on the runner's own stack, so the whole model is strongly reachable and cannot be collected while
 * we measure or dwell.
 *
 * <p>The reading is two independent signals:
 *
 * <ol>
 *   <li><b>Used heap after GC</b> (via {@link MemoryMXBean}) — a tool-independent number. The
 *       absolute value includes fixed suite overhead, but the on-vs-off <em>delta</em> isolates the
 *       reflective-handle retention this change is about.
 *   <li><b>A live class histogram</b> (via {@code jcmd <pid> GC.class_histogram}) — the exact live
 *       instance count and shallow bytes for {@code java.lang.reflect.Method}/{@code Constructor}
 *       and the TestNG wrapper types. Best-effort: skipped with a note if {@code jcmd} is absent.
 * </ol>
 *
 * <p>In {@code snapshot} mode the probe then holds everything pinned for {@code demo.dwell.seconds}
 * so an external profiler (jvmguard via its REST API, or {@code jmap}) can grab a heap dump of this
 * exact steady state, and finally exits. In {@code pressure} mode it does not dwell or exit — the
 * suite runs on so Claim 2 (soft reclaim under memory pressure) can play out.
 */
public class MemoryProbeListener implements IInvokedMethodListener {

  private static final AtomicBoolean FIRED = new AtomicBoolean(false);

  private final boolean snapshotMode = "snapshot".equalsIgnoreCase(Runner.MODE);
  private final int dwellSeconds = Integer.getInteger("demo.dwell.seconds", 45);

  @Override
  public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {
    if (!FIRED.compareAndSet(false, true)) {
      return; // only the first invocation takes the reading
    }
    long pid = ProcessHandle.current().pid();

    // Settle the heap so we count what is actually retained, not float.
    System.gc();
    sleep(250);
    System.gc();
    sleep(250);

    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    long usedBytes = memory.getHeapMemoryUsage().getUsed();

    System.out.println();
    System.out.println("================ MEMORY PROBE (pid=" + pid + ") ================");
    System.out.println("  testng.reflection.intern = " + Runner.INTERN);
    System.out.println("  demo.instances           = " + BigFactoryTest.INSTANCES);
    System.out.println("  mode                     = " + Runner.MODE);
    System.out.printf("  used heap after GC       = %,d bytes (%.1f MB)%n", usedBytes, usedBytes / (1024.0 * 1024.0));
    if (Boolean.parseBoolean(System.getProperty("demo.jcmd", "true"))) {
      System.out.println("  ---- live class histogram (jcmd GC.class_histogram) ----");
      printHistogram(pid);
    }
    System.out.println("=================================================================");
    System.out.println();

    if (snapshotMode) {
      System.out.println(
          ">>> SNAPSHOT READY: heap is steady and pinned for "
              + dwellSeconds
              + "s. Capture a heap dump now (jvmguard REST capture, or: jmap -dump:live,format=b,file=heap-"
              + Runner.label()
              + ".hprof "
              + pid
              + ").");
      sleep(dwellSeconds * 1000L);
      System.out.println(">>> Dwell elapsed; exiting without running the remaining tests.");
      System.out.flush();
      Runtime.getRuntime().halt(0); // skip the (now irrelevant) rest of the suite
    }
  }

  @Override
  public void afterInvocation(IInvokedMethod method, ITestResult testResult) {}

  /** Runs {@code jcmd <pid> GC.class_histogram} and prints the rows we care about (or top-N). */
  private static void printHistogram(long pid) {
    boolean full = Boolean.getBoolean("demo.histogram.full");
    int topN = Integer.getInteger("demo.histogram.top", 25);
    String jcmd = jcmdPath();
    try {
      Process process =
          new ProcessBuilder(jcmd, Long.toString(pid), "GC.class_histogram")
              .redirectErrorStream(true)
              .start();
      int shown = 0;
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          boolean interesting =
              line.contains("java.lang.reflect.Method")
                  || line.contains("java.lang.reflect.Constructor")
                  || line.contains("java.lang.ref.SoftReference")
                  || line.contains("java.lang.ref.WeakReference")
                  || line.contains("org.testng.internal.ConstructorOrMethod")
                  || line.contains("org.testng.internal.MemberKey");
          if (full) {
            if (shown++ < topN) {
              System.out.println("  " + line.trim());
            }
          } else if (interesting) {
            System.out.println("  " + line.trim());
          }
        }
      }
      process.waitFor();
    } catch (Exception e) {
      System.out.println("  (jcmd histogram unavailable: " + e.getMessage()
          + " — rely on 'used heap' above and the jvmguard/jmap heap dump)");
    }
  }

  private static String jcmdPath() {
    String javaHome = System.getProperty("java.home");
    if (javaHome != null) {
      java.io.File candidate = new java.io.File(javaHome, "bin/jcmd");
      if (candidate.canExecute()) {
        return candidate.getAbsolutePath();
      }
    }
    return "jcmd"; // fall back to PATH
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
