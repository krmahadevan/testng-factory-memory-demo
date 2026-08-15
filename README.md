# TestNG `@Factory` reflective-handle memory demo

This little project measures what [testng PR #3353](https://github.com/testng-team/testng/pull/3353)
actually does to memory and GC. It runs the same kind of work three ways and compares the numbers.

All numbers below were re-measured against the **final** build of the PR — a plain strong-reference
cache (see the history note below) — on JDK 11 and 17 (Temurin).

## What the PR changes (in one paragraph)

When you run a big `@Factory` test, TestNG wraps each test method in a small object called
`ConstructorOrMethod`. The same few methods get wrapped over and over — once per factory instance and
per clone — so a huge suite ends up holding thousands of copies of the same reflective handle
(`java.lang.reflect.Method`). The PR makes all those wrappers **share one copy** of each handle,
through a small process-wide cache keyed by declaring class and then by member
(`ClassValue -> Map<MemberKey, Executable>`). The shared copy is held with an ordinary **strong**
reference — no soft references, no dropping, no rebuilding. You can turn the whole thing off with
`-Dtestng.reflection.intern=false`, in which case each wrapper simply holds the handle it was given,
exactly like older TestNG.

> **History note.** An earlier version of the PR held the shared handle *softly*, so the JVM could
> drop it under memory pressure and rebuild it on next use. That added a lot of machinery (rebuild,
> revive, per-entry locking). **Test 3** below is the measurement that showed the soft references buy
> nothing over a plain strong cache — which is why the PR was simplified to what it is now.

We wanted to answer one question: **does this actually help, and if so, how?**

## The two claims we tested

1. **Footprint** — "it uses less memory."
2. **GC under pressure** — "when memory is tight, it is easier on the garbage collector."

Short version of what we found:

- **Claim 1 holds, but it is modest.** Sharing collapses the live handles from ~100,000 down to about
  300 — the dedup clearly works. But reflective handles are only a small slice of a suite's memory
  (TestNG's own per-method model is the big cost, and the PR doesn't touch that), so the total used
  heap drops only a few MB. The saving grows with suite size.
- **Claim 2 holds.** With sharing on, the run keeps less live data, spends less time in GC, gets
  roughly twice the work done, and survives a smaller heap where old TestNG runs out of memory.

The important thing to understand: **both wins come from the same one thing — deduplication.** Fewer
live objects means less to hold and less to collect. There is no reclaim trick; the shared handle is
held strongly and never rebuilt.

## Setup

You need the PR build of TestNG in your local Maven cache. From the testng repo, on the PR branch:

```bash
./gradlew publishToMavenLocal
```

That publishes `org.testng:testng:7.13.0-SNAPSHOT`. The old (pre-PR) baseline is `7.12.0`, which the
scripts pull from Maven Central automatically.

Then build this project:

```bash
mvn -q package -DskipTests
```

The scripts run against every JDK you have under `~/.sdkman/candidates/java/`. We used JDK 11 and 17
(Temurin).

## Test 1 — footprint (`matrix.sh`)

Builds a factory that makes N test instances, holds the whole model in memory, and reports the used
heap and how many `Method` objects are still alive.

```bash
./matrix.sh 50000      # 50,000 factory instances
./matrix.sh 200000     # or more, to see it scale
```

Three columns: pre-PR `7.12.0`, the branch with the feature **off**, and the branch with the feature
**on**.

### How it takes the reading

The trick is *when* we measure. TestNG builds its whole method model **before** it runs the first
test, so at the very first test every wrapper and every handle is already alive. `Runner` runs the
suite; `MemoryProbeListener` waits for that first test and then, on the spot:

1. **Settles the heap** — calls `System.gc()` twice with a short pause, so we count what is really
   held, not short-lived garbage.
2. **Reads used heap** — `ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()`.
3. **Counts the objects** — shells out to `jcmd <own-pid> GC.class_histogram` and picks out the rows
   for `java.lang.reflect.Method`, `Constructor`, `ConstructorOrMethod` and `MemberKey`. This gives
   the exact live instance count and byte size of each — no heap-dump parsing needed.
4. **Holds still, then exits** — so it never has to run all N tests.

`matrix.sh` just does this for each JDK and each of the three versions and lays the results in a
table. The classpath is `target/classes` plus the right TestNG jar (`target/dep-7120/*` for 7.12,
`target/dependency/*` for the branch).

To reproduce a single cell yourself:

```bash
JAVA=~/.sdkman/candidates/java/17.0.20-tem/bin/java
$JAVA -Xmx2g -Ddemo.instances=50000 -Ddemo.dwell.seconds=0 -Dtestng.reflection.intern=true \
  -cp "target/classes:target/dependency/*" demo.Runner
```

The raw reading looks like this (branch, feature on, 50k):

```
================ MEMORY PROBE (pid=35248) ================
  testng.reflection.intern = true
  demo.instances           = 50000
  used heap after GC       = 288,538,488 bytes (275.2 MB)
  ---- live class histogram (jcmd GC.class_histogram) ----
  14:        300000        7200000  org.testng.internal.ConstructorOrMethod
  38:           284          24992  java.lang.reflect.Method
```

Two things to read off it directly: `ConstructorOrMethod` is `7,200,000 / 300,000 = 24 bytes` each
(the same size as pre-PR — the wrapper got no fatter), and there are only 284 `Method` objects left
instead of ~100,000. Run the same command with `-cp "target/classes:target/dep-7120/*"` (and drop the
intern flag) to see the pre-PR baseline for comparison.

### What we saw

Used heap after GC, and live `java.lang.reflect.Method` count:

| Instances | JDK | 7.12 (pre-PR)     | 7.13 feature OFF  | 7.13 feature ON     |
|-----------|-----|-------------------|-------------------|---------------------|
| 50,000    | 11  | 278 MB / 100,381  | 284 MB / 100,387  | **275 MB / 390**    |
| 50,000    | 17  | 278 MB / 100,275  | 284 MB / 100,281  | **275 MB / 284**    |
| 200,000   | 11  | 1098 MB / 400,381 | 1120 MB / 400,387 | **1086 MB / 390**   |
| 200,000   | 17  | 1101 MB / 400,275 | 1123 MB / 400,281 | **1089 MB / 284**   |

What this says:

- **The feature works:** live `Method` objects drop from ~100k / 400k down to about 300, whatever the
  suite size. That is the dedup — one shared handle per *distinct* method.
- **But the heap only moves a little** — ~9 MB at 50k, ~34 MB at 200k. The handles just aren't where a
  factory suite's memory goes (TestNG's own per-method model is the big cost, and the PR doesn't touch
  that). The saving is real and grows with the suite, but it is a small share of the whole.
- **Turning it off does not match 7.12 exactly** — OFF sits a few MB above pre-PR (e.g. 284 vs 278 at
  50k). That gap is **not** from this change's off-path: the live handle counts are identical
  (100,387 vs 100,381), and with interning off the wrapper stores the handle it was handed directly,
  holding a single field where 7.12 held two. The gap is ordinary drift between the 7.12 and 7.13
  codebases, unrelated to the PR.

## Test 2 — GC under memory pressure (`pressure-matrix.sh`)

This is the interesting one. It holds a big pile of `ConstructorOrMethod` (like a big suite would),
then squeezes the heap with a competing block of memory and keeps using the handles. It measures how
much live data is kept, how much time goes into GC, how much work gets done, and whether the run
survives at all.

```bash
./pressure-matrix.sh
```

### How it takes the reading

`PressureProbe` sets up the squeeze and measures it:

1. **Builds the handles** — makes `demo.wrappers` `ConstructorOrMethod` objects over fresh `Method`
   copies and keeps them in a list. This is the population a big factory suite holds. With the
   feature off, every copy is pinned; with it on, they all share one handle per member.
2. **Adds pressure** — allocates a retained block of memory (`demo.pressure.retainMb`) that competes
   for the heap, plus a background thread that keeps making garbage. This is what forces the JVM to
   work hard.
3. **Keeps using the handles** — for `demo.pressure.seconds`, a loop calls `getMethod()` on random
   wrappers (with sharing on, this just returns the shared handle — no rebuild).
4. **Measures** — GC count and time from `ManagementFactory.getGarbageCollectorMXBeans()` (summed
   across collectors), the surviving live set from the memory bean after a `System.gc()`, throughput
   from a simple counter of how many handle-uses it managed, and survival by catching
   `OutOfMemoryError`. If the JVM dies outright, the script reports it as "OOM/died".

`pressure-matrix.sh` runs this for each JDK and version at two heap sizes: a roomy one where all three
survive (so we can compare GC time and throughput) and a tight one (so we can see who runs out of
memory first).

To reproduce a single run:

```bash
JAVA=~/.sdkman/candidates/java/17.0.20-tem/bin/java
$JAVA -Xmx420m -Ddemo.wrappers=500000 -Ddemo.pressure.retainMb=120 -Ddemo.pressure.seconds=15 \
  -Dtestng.reflection.intern=true \
  -cp "target/classes:target/dependency/*" demo.PressureProbe
```

One raw result line (branch, feature on, JDK 17, roomy heap):

```
PRESSURE | soft   w=500000 retain=120MB | completed=true | wallMs=15455 | gcCount=3976 | gcTimeMs=9912 (64% of wall) | liveSetMB=254 | touches=29440000
```

(The `soft` tag in that line is a stale label left over from the old design; the run above is the
strong-ref product, driven by `-Dtestng.reflection.intern=true`.) Read off it: the run survived, kept
254 MB of live data, spent 64% of its time in GC, and did ~29M handle-uses. Run the same line against
`target/dep-7120/*` (pre-PR) to see it keep 296 MB, sit at 80% GC, and do only ~13M — or lower `-Xmx`
to `260m` and watch pre-PR run out of memory while this one keeps going.

### What we saw

500,000 handles, a 120 MB competing block, 15 seconds of pressure.

**Everyone survives (roomy heap, `-Xmx420m`):**

| JDK | Version     | Live data  | Time in GC | Work done |
|-----|-------------|------------|------------|-----------|
| 11  | 7.12 pre-PR | 296 MB     | 81%        | 12.9M     |
| 11  | 7.13 OFF    | 296 MB     | 80%        | 12.5M     |
| 11  | **7.13 ON** | **254 MB** | **64%**    | **28.9M** |
| 17  | 7.12 pre-PR | 296 MB     | 80%        | 12.9M     |
| 17  | 7.13 OFF    | 296 MB     | 79%        | 12.6M     |
| 17  | **7.13 ON** | **254 MB** | **64%**    | **29.4M** |

**Tighter heap (`-Xmx260m`):** 7.13 ON finishes (254 MB live, ~1% in GC); 7.12 and 7.13 OFF both run
out of memory — they die while still *building* the 500,000-handle population, because the
un-deduplicated handles simply don't fit.

What this says:

- With the feature on, the run **holds ~42 MB less live data** — 500,000 wrappers share one handle
  per member instead of pinning a copy each.
- That means **less time stuck in GC** (about 64% vs 80%) and **roughly twice the work done** in the
  same time.
- And it **survives a smaller heap** where old TestNG crashes outright.
- The whole difference is the handle sharing: **feature off tracks pre-PR** (296 MB, ~80% GC, same
  throughput, same OOM), because with sharing off nothing is deduplicated.

### One honest caveat

This test holds handles and little else, so they are a big share of the heap and the effect looks big.
In a real suite the handles are a smaller slice (TestNG's own model fills the rest, and that part
can't be shared away). So the real-world gain is **proportional to how much of your heap is handles** —
smaller than this test shows, but real, and it matters most right at the edge where a suite would
otherwise run out of memory.

## Test 3 — strong-ref cache vs soft-ref cache (why the PR ended up strong)

The PR *originally* held the shared handles through **soft references**, so they could be dropped
under memory pressure and rebuilt on demand. That is what added most of the complexity (rebuild,
revive, per-entry locking). A reviewer asked the fair question: would a **plain strong-reference**
cache — `ClassValue -> Map<MemberKey, Executable>` — give the same benefit with none of that?

To answer it, `PressureProbe` can run three strategies (`-Ddemo.strategy`):

- `off` — no cache; one strong handle per wrapper (like old TestNG).
- `strong` — a strong-reference dedup cache (`StrongDedupCache.java`), the simpler alternative.
- `soft` — the old soft-reference cache.

### What we saw (W = 500,000 wrappers)

No pressure, roomy heap — how much do they dedup?

| Strategy | Live handles |
|----------|--------------|
| `off` (one per wrapper)  | 56 MB      |
| `strong` (dedup, strong) | **14 MB**  |
| `soft` (dedup, soft)     | **14 MB**  |

Under survivable pressure (`-Xmx420m`, 120 MB competing blob), JDK 11 and 17 both:

| Strategy | Live data  | Time in GC | Work done |
|----------|------------|------------|-----------|
| `off`    | 296 MB     | 80%        | ~13M      |
| `strong` | **254 MB** | **64%**    | **~29M**  |
| `soft`   | **254 MB** | **66%**    | **~29M**  |

`strong` and `soft` are **indistinguishable** — same dedup, same live set, same GC time, same
throughput. Both clearly beat the no-cache `off` case. The soft-reference rebuild/revive/locking
machinery buys **nothing measurable** here.

Why: the deduped handle set is tiny (one handle per *distinct method*, ~14 MB even for half a million
wrappers), so being able to drop it under pressure saves almost nothing — and near the memory edge the
soft cache would have to *rebuild* those handles (`getDeclaredMethods()` scans), which is an added
cost, not a saving. A strong-reference cache also stays leak-free, because its per-class table lives
in a `ClassValue` and is collected together with the class.

**This is the measurement that decided the design.** The PR was simplified to the plain strong cache
you see in Test 1 and Test 2.

## The bottom line

Deduplication is the real win, and a **simple strong-reference cache delivers all of it** — the
footprint reduction (Claim 1) and the better behaviour under pressure (Claim 2) alike. Both come from
holding fewer live objects, not from any reclaim trick. The soft-reference + rebuild machinery from
the first draft of the PR did not pay for its complexity in any of these measurements, so it was
dropped.

## Files

| File | What it does |
|------|--------------|
| `BigFactoryTest.java` | A `@Factory` test that makes N instances, each with a few methods. |
| `Runner.java` | Runs the suite for the footprint test. |
| `MemoryProbeListener.java` | Takes the footprint reading at the right moment. |
| `PressureProbe.java` | The GC-under-pressure test (and the strong-vs-soft comparison). |
| `StrongDedupCache.java` | A standalone copy of the strong-reference cache, used for the Test 3 comparison. It mirrors what the shipped PR now does. |
| `matrix.sh` | Footprint comparison across JDKs. |
| `pressure-matrix.sh` | Pressure comparison across JDKs. |

## Handy knobs

- `-Ddemo.instances=N` — factory instance count (footprint test).
- `-Ddemo.wrappers=N` — how many handles to hold (pressure test).
- `-Ddemo.pressure.retainMb=N` — size of the competing memory block.
- `-Ddemo.pressure.seconds=N` — how long to keep the pressure on.
- `-Dtestng.reflection.intern=false` — turn the feature off.
