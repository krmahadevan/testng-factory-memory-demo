package demo;

import java.lang.reflect.Executable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * A standalone copy of the shipped cache ({@code org.testng.internal.ExecutableCache}): a {@code
 * ClassValue -> Map<Executable, Executable>} that de-duplicates reflective handles and holds them
 * strongly. The {@link Executable} is its own key — {@code Method}/{@code Constructor} equality
 * already compares declaring class, name, parameter types and (for methods) return type — so no
 * extra key object is built per lookup.
 *
 * <p>Kept here so the demo can exercise the "strong dedup" strategy directly, independently of the
 * product jar.
 */
public final class StrongDedupCache {

  private final ClassValue<ConcurrentMap<Executable, Executable>> cache =
      new ClassValue<>() {
        @Override
        protected ConcurrentMap<Executable, Executable> computeValue(Class<?> type) {
          return new ConcurrentHashMap<>();
        }
      };

  /** Returns the one shared handle for {@code seed}'s member, storing {@code seed} the first time. */
  public Executable intern(Executable seed) {
    return cache.get(seed.getDeclaringClass()).computeIfAbsent(seed, Function.identity());
  }
}
