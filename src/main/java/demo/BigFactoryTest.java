package demo;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

/**
 * A deliberately fat {@code @Factory}-powered test class: one factory call produces {@value
 * #DEFAULT_INSTANCES} instances (override with {@code -Ddemo.instances=N}), and each instance
 * carries several {@code @Test}/{@code @BeforeMethod}/{@code @AfterMethod} methods.
 *
 * <p>Every one of those methods, on every instance, is wrapped by TestNG in a {@code
 * ConstructorOrMethod}. That is exactly the population PR #3353 targets:
 *
 * <ul>
 *   <li><b>Before the PR</b> (reproduced with {@code -Dtestng.reflection.intern=false}): each
 *       wrapper holds its own live {@link java.lang.reflect.Method}. Reflection hands back a fresh
 *       copy per lookup, so {@code instances * methodsPerInstance} distinct {@code Method} objects
 *       stay pinned for the whole suite.
 *   <li><b>With the PR</b> ({@code -Dtestng.reflection.intern=true}, the default): all wrappers for
 *       one physical method share a single interned {@code Method}, held softly, so the population
 *       collapses to roughly {@code methodsPerInstance} handles regardless of instance count.
 * </ul>
 *
 * <p>The method bodies are intentionally trivial — the memory under study is the retained reflective
 * <em>handles</em>, not anything the tests compute.
 */
public class BigFactoryTest {

  static final int DEFAULT_INSTANCES = 50_000;

  /** Number of factory-produced instances; override with {@code -Ddemo.instances=N}. */
  static final int INSTANCES = Integer.getInteger("demo.instances", DEFAULT_INSTANCES);

  @SuppressWarnings("unused")
  private final int id;

  public BigFactoryTest(int id) {
    this.id = id;
  }

  @Factory
  public static Object[] create() {
    Object[] instances = new Object[INSTANCES];
    for (int i = 0; i < INSTANCES; i++) {
      instances[i] = new BigFactoryTest(i);
    }
    return instances;
  }

  @BeforeMethod
  public void setUp() {}

  @AfterMethod
  public void tearDown() {}

  @Test
  public void alpha() {}

  @Test
  public void beta() {}

  @Test
  public void gamma() {}

  @Test
  public void delta() {}
}
