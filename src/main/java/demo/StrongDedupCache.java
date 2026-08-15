package demo;

import java.lang.reflect.Executable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.testng.internal.MemberKey;

/**
 * The simpler alternative juherr proposed, for comparison: a {@code ClassValue -> Map<MemberKey,
 * Executable>} that deduplicates reflective handles but holds them <b>strongly</b> — no
 * SoftReference, no rebuild, no revive, no per-Entry locking.
 *
 * <p>It gives exactly the same deduplication as the PR's soft cache (all wrappers for one member
 * share one {@code Method}/{@code Constructor}). The only thing it cannot do is drop an idle handle
 * under memory pressure. This class exists purely to measure whether that one extra ability is worth
 * the soft-reference machinery.
 */
public final class StrongDedupCache {

  private final ClassValue<ConcurrentMap<MemberKey, Executable>> cache =
      new ClassValue<>() {
        @Override
        protected ConcurrentMap<MemberKey, Executable> computeValue(Class<?> type) {
          return new ConcurrentHashMap<>();
        }
      };

  /** Returns the one shared handle for {@code seed}'s member, storing {@code seed} the first time. */
  public Executable intern(Executable seed) {
    return cache
        .get(seed.getDeclaringClass())
        .computeIfAbsent(new MemberKey(seed), key -> seed);
  }
}
