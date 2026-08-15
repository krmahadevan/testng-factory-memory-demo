package demo;

import org.testng.TestNG;

/**
 * Entry point for the footprint test (Test 1). Runs the {@link BigFactoryTest} suite once, under
 * whatever {@code -Dtestng.reflection.intern} the JVM was launched with, and lets {@link
 * MemoryProbeListener} take the reading.
 *
 * <p>TestNG builds the whole method model before it runs the first test, so the listener can pause
 * at that first test — when every handle is alive — take the measurement, hold the heap steady for a
 * dump, and exit. That is why this is fast no matter how many instances the factory makes.
 *
 * <p>The GC-under-pressure test (Test 2) lives in {@link PressureProbe}, not here.
 */
public final class Runner {

  static final boolean INTERN =
      Boolean.parseBoolean(System.getProperty("testng.reflection.intern", "true"));
  static final String MODE = System.getProperty("demo.mode", "snapshot");

  private Runner() {}

  static String label() {
    return (INTERN ? "intern-on" : "intern-off") + "-" + MODE + "-" + BigFactoryTest.INSTANCES;
  }

  public static void main(String[] args) {
    System.out.println(
        "Starting demo: " + label() + "  (java.home=" + System.getProperty("java.home") + ")");

    TestNG testng = new TestNG();
    testng.setUseDefaultListeners(false); // no HTML/XML reports: 50k+ instances would be enormous
    testng.setVerbose(0);
    testng.setTestClasses(new Class[] {BigFactoryTest.class});
    testng.addListener(new MemoryProbeListener());

    long start = System.currentTimeMillis();
    testng.run();
    System.out.println("Suite finished in " + (System.currentTimeMillis() - start) + " ms");
  }
}
