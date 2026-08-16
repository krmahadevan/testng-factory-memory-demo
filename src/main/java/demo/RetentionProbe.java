package demo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import org.testng.TestNG;

/**
 * Isolated test of juherr's #2: how much memory stays retained <em>after</em> a TestNG run finishes.
 *
 * <p>The footprint test (Test 1) measures the peak, while the suite is alive. This one measures the
 * opposite end: it runs the {@link BigFactoryTest} suite to completion, drops every reference to
 * TestNG and the suite model, forces GC (keeping this application's class loader alive), then counts
 * how many reflective handles are still live.
 *
 * <p>The point of the measurement: interning trades a big <b>temporary</b> population (one handle per
 * wrapper, alive only while the suite runs) for a small <b>persistent</b> one (one handle per
 * distinct member, alive for as long as the declaring class stays loaded). Pre-PR TestNG and the
 * kill switch keep no cache, so once the suite is collected their per-wrapper handles go with it.
 * This probe shows that persistent cost directly, for 7.12 vs 7.13-off vs 7.13-on.
 */
public final class RetentionProbe {

  private RetentionProbe() {}

  public static void main(String[] args) {
    boolean intern = Boolean.parseBoolean(System.getProperty("testng.reflection.intern", "true"));

    // Run the suite in its own scope so that TestNG and everything it built become unreachable the
    // moment the method returns — nothing leaks onto this stack frame.
    runSuiteToCompletion();

    // Let the model go: settle the heap so only genuinely retained objects survive.
    settle();

    long pid = ProcessHandle.current().pid();
    long usedBytes = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    Counts counts = liveCounts(pid);

    System.out.printf(
        "RETENTION | intern=%-3s instances=%d | usedHeapMB=%.1f | liveMethods=%d | liveConstructors=%d | liveWrappers=%d%n",
        intern ? "on" : "off",
        BigFactoryTest.INSTANCES,
        usedBytes / (1024.0 * 1024.0),
        counts.methods,
        counts.constructors,
        counts.wrappers);
  }

  private static void runSuiteToCompletion() {
    TestNG testng = new TestNG();
    testng.setUseDefaultListeners(false); // no reports: they would themselves retain result objects
    testng.setVerbose(0);
    testng.setTestClasses(new Class[] {BigFactoryTest.class});
    testng.run();
  }

  private static void settle() {
    for (int i = 0; i < 6; i++) {
      System.gc();
      sleep(200);
    }
  }

  /** Live instance counts for the three types that matter, read from a live class histogram. */
  private static final class Counts {
    final long methods;
    final long constructors;
    final long wrappers;

    Counts(long methods, long constructors, long wrappers) {
      this.methods = methods;
      this.constructors = constructors;
      this.wrappers = wrappers;
    }
  }

  /**
   * Runs {@code jcmd <pid> GC.class_histogram} (which does a full GC and counts <em>live</em>
   * objects) and pulls out the counts for {@code Method}, {@code Constructor} and the TestNG wrapper.
   */
  private static Counts liveCounts(long pid) {
    long methods = 0;
    long constructors = 0;
    long wrappers = 0;
    try {
      Process process =
          new ProcessBuilder(jcmdPath(), Long.toString(pid), "GC.class_histogram")
              .redirectErrorStream(true)
              .start();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          String[] parts = line.trim().split("\\s+");
          if (parts.length < 4) {
            continue;
          }
          long count = parseLongOrZero(parts[1]);
          switch (parts[3]) {
            case "java.lang.reflect.Method":
              methods = count;
              break;
            case "java.lang.reflect.Constructor":
              constructors = count;
              break;
            case "org.testng.internal.ConstructorOrMethod":
              wrappers = count;
              break;
            default:
              // not a row we track
          }
        }
      }
      process.waitFor();
    } catch (Exception e) {
      System.out.println("  (jcmd histogram unavailable: " + e.getMessage() + ")");
    }
    return new Counts(methods, constructors, wrappers);
  }

  private static long parseLongOrZero(String s) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return 0;
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
