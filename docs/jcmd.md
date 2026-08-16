# A quick guide to `jcmd` (and how this demo uses it)

This project measures how many reflective handles a TestNG run keeps alive. The tool it leans on for
the exact counts is **`jcmd`**. If you have never used it, this page walks through what it is, how to
run it, what its output looks like, and how the demo parses that output.

## What `jcmd` is

`jcmd` ships **inside the JDK** (`$JAVA_HOME/bin/jcmd`) — you already have it. It sends a diagnostic
command to a **running** JVM and prints the reply. No agent, no restart, no special JVM flags: you
point it at a live process and ask a question.

It's the modern, one-stop replacement for the old `jmap` / `jstack` / `jinfo` tools.

## Step 1 — find the JVM you want

`jcmd -l` lists every JVM running as your user, one per line: the **process id** first, then the main
class (or jar) it was launched with.

```console
$ jcmd -l
87437 demo.Runner
87741 jdk.jcmd/sun.tools.jcmd.JCmd -l
```

Here `87437` is our demo. (The second line is `jcmd` itself.) That number — the pid — is what every
other command needs.

## Step 2 — ask a JVM what it can do

`jcmd <pid> help` lists the commands that JVM understands. There are dozens; the ones worth knowing:

| Command | What it tells you |
|---------|-------------------|
| `GC.class_histogram` | how many live objects of each class, and how many bytes — **this is the one the demo uses** |
| `GC.heap_info` | heap size and how much is used right now |
| `GC.heap_dump <file>` | write a full heap dump you can open in a profiler |
| `Thread.print` | a full thread dump (like `jstack`) |
| `VM.flags` / `VM.version` | the JVM's flags / version |

Try the simplest one first:

```console
$ jcmd 87437 VM.version
87437:
OpenJDK 64-Bit Server VM version 17.0.20+8
JDK 17.0.20
```

The reply always starts with the pid on its own line, then the command's output.

## Step 3 — the one the demo uses: `GC.class_histogram`

```console
$ jcmd 87437 GC.class_histogram
```

Two things to know about it:

1. It first triggers a **full GC**, then counts what is still alive. So the numbers are the
   **live set** — exactly what you want when measuring retained memory, not short-lived garbage.
2. It pauses the target app briefly while it counts. Fine for diagnostics; don't wire it into a hot
   loop in production.

### What the output looks like

```
87437:
 num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:         20000        3840000  org.testng.internal.TestNGMethod
   2:         29640        3493584  [B (java.base@17.0.20)
   3:         60373        2897904  java.util.HashMap (java.base@17.0.20)
   4:        150470        2413248  [Ljava.lang.String; (java.base@17.0.20)
   5:         30132        1928448  java.util.concurrent.ConcurrentHashMap (java.base@17.0.20)
   6:         80115        1922760  java.util.ArrayList (java.base@17.0.20)
   7:         10000        1840000  org.testng.internal.ConfigurationMethod
   ...
  14:         30000         720000  org.testng.internal.ConstructorOrMethod
  38:           284          24992  java.lang.reflect.Method (java.base@17.0.20)
  45:           163          11736  java.lang.reflect.Constructor (java.base@17.0.20)
```

Four columns, sorted by total bytes (biggest first):

| Column | Meaning |
|--------|---------|
| `num` | rank in the list (1 = most bytes). Just a row number; it changes run to run. |
| `#instances` | **how many live objects** of this class — the number the demo reads. |
| `#bytes` | total shallow bytes those objects occupy. |
| `class name (module)` | the class. `[B` = `byte[]`, `[Ljava.lang.String;` = `String[]`. The `(java.base@17.0.20)` suffix is the JDK module and version; classes from the app have no suffix. |

### Reading the interning story straight out of the histogram

Look at those last three rows together. This run made **5,000** factory instances:

- `TestNGMethod` = **20,000** (4 `@Test` methods × 5,000 instances)
- `ConfigurationMethod` = **10,000** (2 config methods × 5,000 instances)
- `ConstructorOrMethod` = **30,000** (one wrapper per test + config method — 20,000 + 10,000)
- `java.lang.reflect.Method` = **284** ← the payoff

Thirty thousand wrappers, but only **284** actual `Method` objects behind them. That is interning: all
the wrappers for one physical method share a single handle. Turn the feature off
(`-Dtestng.reflection.intern=false`) and that `Method` row jumps into the tens of thousands.

## Step 4 — how the demo parses it

The demo doesn't make you run `jcmd` by hand. `RetentionProbe` (and `MemoryProbeListener`) shell out
to it on **their own** pid — a JVM inspecting itself — via `ProcessHandle.current().pid()`, then read
the counts straight off the histogram lines.

The parsing is deliberately dumb: trim the line, split on whitespace, take field **1** (the count) and
field **3** (the class name), and match the class name against the three types we track.

```java
String[] parts = line.trim().split("\\s+");
//   parts[0] = "14:"                              row number (ignored)
//   parts[1] = "30000"                            <-- the count we want
//   parts[2] = "720000"                           bytes (ignored)
//   parts[3] = "org.testng.internal.ConstructorOrMethod"   <-- the class
long count = Long.parseLong(parts[1]);
switch (parts[3]) {
  case "java.lang.reflect.Method":                 methods = count;      break;
  case "java.lang.reflect.Constructor":            constructors = count; break;
  case "org.testng.internal.ConstructorOrMethod":  wrappers = count;     break;
  default: // a row we don't care about
}
```

The `(java.base@17.0.20)` module suffix lands in `parts[4]`, so matching on `parts[3]` alone is exact
— `java.lang.reflect.Method` matches but `java.lang.reflect.Method[]` (which shows as
`[Ljava.lang.reflect.Method;`) does not.

`RetentionProbe` then prints those three numbers on one `RETENTION | ... liveMethods=... ` line, and
`retention-matrix.sh` scrapes *that* line. So there are two hops: **jcmd histogram → parsed in Java →
one summary line → parsed in the shell script.**

## Finding `jcmd` reliably

The demo doesn't assume `jcmd` is on your `PATH`. It derives it from the JVM that's running, so it
always matches the JDK under test:

```java
String javaHome = System.getProperty("java.home");
File candidate = new File(javaHome, "bin/jcmd");
return candidate.canExecute() ? candidate.getAbsolutePath() : "jcmd"; // fall back to PATH
```

## Gotchas

- **Same user only.** You can only `jcmd` a JVM running as your own user (it's a security boundary).
- **`GC.class_histogram` forces a GC.** That's the point (you get the live set), but it means the
  numbers already have a collection applied — you don't need to `System.gc()` first, though the demo
  does anyway to be safe.
- **Row numbers aren't stable.** The `num` column is just a rank; don't key off it. Match on the
  class name.
- **Shallow bytes.** `#bytes` is the object's own size, not everything it points to. For "how many
  handles survive," the `#instances` count is the honest number, which is why the demo uses it.
