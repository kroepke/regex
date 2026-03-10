# Java Regex Library Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a working Java regex engine (parse → AST → HIR → NFA → PikeVM) validated against the upstream Rust test suite.

**Architecture:** Multi-module Maven project mirroring upstream crate boundaries. UTF-8 byte-oriented NFA with CharSequence→UTF-8 encoding at the Input boundary. Zero runtime dependencies for library modules.

**Tech Stack:** Java 21, Maven, JUnit 6.0.1, Jackson dataformat-toml 2.20.0 (test only)

**Spec:** `docs/superpowers/specs/2026-03-10-regex-library-initial-design.md`

---

## Chunk 1: Project Scaffolding

### Task 1: Maven Parent POM

**Files:**
- Create: `pom.xml`

- [ ] **Step 1: Create parent POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>lol.ohai.regex</groupId>
    <artifactId>regex-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>regex-parent</name>
    <description>Java reimplementation of the Rust regex crate</description>

    <modules>
        <module>regex-syntax</module>
        <module>regex-automata</module>
        <module>regex</module>
        <module>regex-test</module>
    </modules>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>6.0.1</junit.version>
        <jackson.version>2.20.0</jackson.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.junit</groupId>
                <artifactId>junit-bom</artifactId>
                <version>${junit.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.fasterxml.jackson.dataformat</groupId>
                <artifactId>jackson-dataformat-toml</artifactId>
                <version>${jackson.version}</version>
            </dependency>
            <!-- internal modules -->
            <dependency>
                <groupId>lol.ohai.regex</groupId>
                <artifactId>regex-syntax</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>lol.ohai.regex</groupId>
                <artifactId>regex-automata</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>lol.ohai.regex</groupId>
                <artifactId>regex-test</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.15.0</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.5</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

- [ ] **Step 2: Verify POM parses**

Run: `mvn validate`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "add parent POM with module structure and dependency management"
```

### Task 2: Module POMs and Package Structure

**Files:**
- Create: `regex-syntax/pom.xml`
- Create: `regex-automata/pom.xml`
- Create: `regex/pom.xml`
- Create: `regex-test/pom.xml`
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/package-info.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/package-info.java`
- Create: `regex/src/main/java/lol/ohai/regex/package-info.java`
- Create: `regex-test/src/main/java/lol/ohai/regex/test/package-info.java`

- [ ] **Step 1: Create regex-syntax POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>lol.ohai.regex</groupId>
        <artifactId>regex-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>regex-syntax</artifactId>
    <name>regex-syntax</name>
    <description>Regex parser: pattern string to AST to HIR</description>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Create regex-automata POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>lol.ohai.regex</groupId>
        <artifactId>regex-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>regex-automata</artifactId>
    <name>regex-automata</name>
    <description>Regex engines: NFA compiler, PikeVM</description>

    <dependencies>
        <dependency>
            <groupId>lol.ohai.regex</groupId>
            <artifactId>regex-syntax</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>lol.ohai.regex</groupId>
            <artifactId>regex-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Create regex POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>lol.ohai.regex</groupId>
        <artifactId>regex-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>regex</artifactId>
    <name>regex</name>
    <description>Public regex API</description>

    <dependencies>
        <dependency>
            <groupId>lol.ohai.regex</groupId>
            <artifactId>regex-automata</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>lol.ohai.regex</groupId>
            <artifactId>regex-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: Create regex-test POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>lol.ohai.regex</groupId>
        <artifactId>regex-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>regex-test</artifactId>
    <name>regex-test</name>
    <description>Shared test infrastructure for upstream TOML test suite</description>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.dataformat</groupId>
            <artifactId>jackson-dataformat-toml</artifactId>
        </dependency>
    </dependencies>
</project>
```

Note: `regex-test` dependencies are **not** test-scoped — this module *is* test infrastructure, consumed by other modules in their test scope.

- [ ] **Step 5: Create package-info.java files**

Create minimal package-info.java in each module's main source directory to establish the package structure:

`regex-syntax/src/main/java/lol/ohai/regex/syntax/package-info.java`:
```java
/**
 * Regex parser: pattern string to AST and HIR.
 */
package lol.ohai.regex.syntax;
```

`regex-automata/src/main/java/lol/ohai/regex/automata/package-info.java`:
```java
/**
 * Regex engines: NFA compiler, PikeVM, and supporting utilities.
 */
package lol.ohai.regex.automata;
```

`regex/src/main/java/lol/ohai/regex/package-info.java`:
```java
/**
 * Public regex API.
 */
package lol.ohai.regex;
```

`regex-test/src/main/java/lol/ohai/regex/test/package-info.java`:
```java
/**
 * Shared test infrastructure for running the upstream TOML test suite.
 */
package lol.ohai.regex.test;
```

- [ ] **Step 6: Create module-info.java for library modules**

`regex-syntax/src/main/java/module-info.java`:
```java
module lol.ohai.regex.syntax {
    exports lol.ohai.regex.syntax;
}
```

`regex-automata/src/main/java/module-info.java`:
```java
module lol.ohai.regex.automata {
    requires lol.ohai.regex.syntax;
    exports lol.ohai.regex.automata;
}
```

`regex/src/main/java/module-info.java`:
```java
module lol.ohai.regex {
    requires lol.ohai.regex.automata;
    exports lol.ohai.regex;
}
```

No `module-info.java` for `regex-test` — it's test infrastructure consumed on the classpath.

- [ ] **Step 7: Verify build compiles**

Run: `mvn compile`
Expected: BUILD SUCCESS for all 4 modules

- [ ] **Step 8: Create .gitignore**

```
target/
*.iml
.idea/
*.class
```

- [ ] **Step 9: Commit**

```bash
git add regex-syntax/ regex-automata/ regex/ regex-test/ .gitignore
git commit -m "add module POMs, package structure, and module-info.java"
```

---

## Chunk 2: Test Infrastructure (`regex-test`)

### Task 3: Test Model Classes

**Files:**
- Create: `regex-test/src/main/java/lol/ohai/regex/test/RegexTest.java`
- Create: `regex-test/src/main/java/lol/ohai/regex/test/Span.java`
- Create: `regex-test/src/main/java/lol/ohai/regex/test/Captures.java`
- Create: `regex-test/src/main/java/lol/ohai/regex/test/MatchKind.java`
- Create: `regex-test/src/main/java/lol/ohai/regex/test/SearchKind.java`

These types model the upstream TOML test format. See `upstream/regex/regex-test/lib.rs` lines 211-1444 for the complete field specification.

- [ ] **Step 1: Create Span record**

```java
package lol.ohai.regex.test;

/**
 * A byte offset span [start, end) in a haystack.
 */
public record Span(int start, int end) {
    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException(
                "invalid span: [" + start + ", " + end + ")");
        }
    }
}
```

- [ ] **Step 2: Create MatchKind enum**

```java
package lol.ohai.regex.test;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum MatchKind {
    @JsonProperty("all")
    ALL,
    @JsonProperty("leftmost-first")
    LEFTMOST_FIRST,
    @JsonProperty("leftmost-longest")
    LEFTMOST_LONGEST
}
```

- [ ] **Step 3: Create SearchKind enum**

```java
package lol.ohai.regex.test;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum SearchKind {
    @JsonProperty("earliest")
    EARLIEST,
    @JsonProperty("leftmost")
    LEFTMOST,
    @JsonProperty("overlapping")
    OVERLAPPING
}
```

- [ ] **Step 4: Create Captures class**

This is the most complex model type — TOML can represent captures in 4 different formats. Requires a custom Jackson deserializer.

```java
package lol.ohai.regex.test;

import java.util.List;
import java.util.Optional;

/**
 * A single match result with capture groups.
 * The first group (index 0) is always the overall match.
 */
public record Captures(int id, List<Optional<Span>> groups) {

    /** Convenience: the overall match span. */
    public Span span() {
        return groups.getFirst().orElseThrow(
            () -> new IllegalStateException("group 0 must always be present"));
    }
}
```

- [ ] **Step 5: Create CapturesDeserializer**

Create: `regex-test/src/main/java/lol/ohai/regex/test/CapturesDeserializer.java`

Custom Jackson deserializer handling the 4 TOML match formats:
1. `[5, 12]` → simple span, id=0
2. `{id: 1, span: [5, 12]}` → span with pattern id
3. `[[5, 12], [7, 9], []]` → multiple groups, id=0
4. `{id: 1, spans: [[5, 12], [7, 9], []]}` → full captures

Ref: `upstream/regex/regex-test/lib.rs` lines 1258-1330 (`CapturesFormat`).

Implementation: Use `JsonParser.currentToken()` to branch on ARRAY vs OBJECT, then on array-element type (number vs array) to distinguish formats 1/3.

- [ ] **Step 6: Create RegexTest class**

```java
package lol.ohai.regex.test;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A single test case from the upstream TOML test suite.
 *
 * @see <a href="upstream/regex/regex-test/lib.rs">upstream test format</a>
 */
public record RegexTest(
    String name,
    /** Either a single pattern or multiple patterns. */
    @JsonProperty("regex") Object regexRaw,
    String haystack,
    Span bounds,
    List<Captures> matches,
    @JsonProperty("match-limit") Integer matchLimit,
    @JsonProperty(defaultValue = "true") boolean compiles,
    @JsonProperty(defaultValue = "false") boolean anchored,
    @JsonProperty("case-insensitive") @JsonProperty(defaultValue = "false") boolean caseInsensitive,
    @JsonProperty(defaultValue = "false") boolean unescape,
    @JsonProperty(defaultValue = "true") boolean unicode,
    @JsonProperty(defaultValue = "true") boolean utf8,
    @JsonProperty("line-terminator") String lineTerminator,
    @JsonProperty("match-kind") MatchKind matchKind,
    @JsonProperty("search-kind") SearchKind searchKind
) {
    // Note: regexRaw is Object because TOML can be String or List<String>.
    // Accessor methods below normalize this.

    /** Group name derived from the TOML filename. Set after deserialization. */
    // This is a derived field - handled by RegexTestSuite loader.

    public List<String> regexes() {
        if (regexRaw instanceof String s) {
            return List.of(s);
        }
        @SuppressWarnings("unchecked")
        var list = (List<String>) regexRaw;
        return list;
    }

    public RegexTest {
        if (matchKind == null) matchKind = MatchKind.LEFTMOST_FIRST;
        if (searchKind == null) searchKind = SearchKind.LEFTMOST;
        if (lineTerminator == null) lineTerminator = "\n";
        if (matches == null) matches = List.of();
    }
}
```

Note: The exact Jackson mapping for `regexRaw` and the dual `@JsonProperty` annotations will need refinement during implementation. The upstream uses a custom deserializer (`RegexesFormat`) — we'll likely need one too. Consult `upstream/regex/regex-test/lib.rs` lines 1131-1180.

- [ ] **Step 7: Write unit test for model deserialization**

Create: `regex-test/src/test/java/lol/ohai/regex/test/RegexTestModelTest.java`

Test that a minimal TOML fragment deserializes correctly:

```java
@Test
void deserializeSimpleTest() {
    String toml = """
        [[test]]
        name = "basic"
        regex = "a"
        haystack = "a"
        matches = [[0, 1]]
        """;
    // Deserialize and verify fields
}

@Test
void deserializeCapturesFormats() {
    // Test all 4 capture formats
}

@Test
void deserializeWithDefaults() {
    // Verify unicode=true, utf8=true, compiles=true defaults
}
```

- [ ] **Step 8: Run tests**

Run: `mvn test -pl regex-test`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add regex-test/
git commit -m "add regex-test model classes with TOML deserialization"
```

### Task 4: TOML Test Suite Loader

**Files:**
- Create: `regex-test/src/main/java/lol/ohai/regex/test/RegexTestSuite.java`

- [ ] **Step 1: Write test for suite loading**

Create: `regex-test/src/test/java/lol/ohai/regex/test/RegexTestSuiteTest.java`

```java
@Test
void loadMiscToml() {
    var suite = new RegexTestSuite();
    suite.load(Path.of("../upstream/regex/testdata/misc.toml"));
    assertFalse(suite.tests().isEmpty());
    // Verify group name is "misc"
    assertEquals("misc", suite.tests().getFirst().groupName());
}

@Test
void loadAllTestData() {
    var suite = RegexTestSuite.loadAll(
        Path.of("../upstream/regex/testdata"));
    assertTrue(suite.tests().size() > 100,
        "Expected >100 tests, got " + suite.tests().size());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl regex-test -Dtest="RegexTestSuiteTest"`
Expected: FAIL — RegexTestSuite doesn't exist

- [ ] **Step 3: Implement RegexTestSuite**

```java
package lol.ohai.regex.test;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public final class RegexTestSuite {
    private final List<RegexTest> tests = new ArrayList<>();
    private final TomlMapper mapper = new TomlMapper();

    public void load(Path path) throws IOException {
        String groupName = pathToGroupName(path);
        // Deserialize the TOML [[test]] array
        // Set groupName on each test
    }

    public static RegexTestSuite loadAll(Path directory) throws IOException {
        var suite = new RegexTestSuite();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> p.toString().endsWith(".toml"))
                 .sorted()
                 .forEach(p -> {
                     try { suite.load(p); }
                     catch (IOException e) { throw new UncheckedIOException(e); }
                 });
        }
        return suite;
    }

    public List<RegexTest> tests() {
        return Collections.unmodifiableList(tests);
    }

    private static String pathToGroupName(Path path) {
        String name = path.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl regex-test -Dtest="RegexTestSuiteTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add regex-test/
git commit -m "add TOML test suite loader"
```

### Task 5: Test Runner (JUnit 6 Dynamic Tests)

**Files:**
- Create: `regex-test/src/main/java/lol/ohai/regex/test/TestRunner.java`
- Create: `regex-test/src/main/java/lol/ohai/regex/test/CompiledRegex.java`
- Create: `regex-test/src/main/java/lol/ohai/regex/test/TestResult.java`
- Create: `regex-test/src/main/java/lol/ohai/regex/test/EngineCapabilities.java`

- [ ] **Step 1: Create EngineCapabilities**

```java
package lol.ohai.regex.test;

import java.util.Set;

/**
 * Declares what features an engine supports.
 * Tests requiring unsupported features are skipped.
 */
public record EngineCapabilities(
    boolean supportsCaptures,
    boolean supportsUnicode,
    boolean supportsAnchored,
    Set<MatchKind> supportedMatchKinds,
    Set<SearchKind> supportedSearchKinds
) {
    public static EngineCapabilities pikeVm() {
        return new EngineCapabilities(
            true, true, true,
            Set.of(MatchKind.LEFTMOST_FIRST, MatchKind.ALL),
            Set.of(SearchKind.LEFTMOST, SearchKind.EARLIEST)
        );
    }

    public boolean supports(RegexTest test) {
        if (!supportsUnicode && test.unicode()) return false;
        if (!supportsAnchored && test.anchored()) return false;
        if (!supportedMatchKinds.contains(test.matchKind())) return false;
        if (!supportedSearchKinds.contains(test.searchKind())) return false;
        return true;
    }
}
```

- [ ] **Step 2: Create TestResult**

```java
package lol.ohai.regex.test;

import java.util.List;
import java.util.Optional;

/**
 * Result of running a single regex test.
 */
public sealed interface TestResult {
    record Matched(boolean matches) implements TestResult {}
    record Matches(List<Span> spans) implements TestResult {}
    record CaptureResults(List<Captures> captures) implements TestResult {}
    record Skipped(String reason) implements TestResult {}
    record Failed(String reason) implements TestResult {}
}
```

- [ ] **Step 3: Create CompiledRegex**

```java
package lol.ohai.regex.test;

import java.util.function.Function;

/**
 * A compiled regex ready for testing.
 * Wraps a match function that the engine provides.
 */
public final class CompiledRegex {
    private final Function<RegexTest, TestResult> matcher;

    private CompiledRegex(Function<RegexTest, TestResult> matcher) {
        this.matcher = matcher;
    }

    public static CompiledRegex compiled(Function<RegexTest, TestResult> matcher) {
        return new CompiledRegex(matcher);
    }

    public static CompiledRegex skip() {
        return new CompiledRegex(t -> new TestResult.Skipped("unsupported"));
    }

    public TestResult run(RegexTest test) {
        return matcher.apply(test);
    }
}
```

- [ ] **Step 4: Create TestRunner**

```java
package lol.ohai.regex.test;

import org.junit.jupiter.api.DynamicTest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Generates JUnit 6 DynamicTests from the upstream TOML test suite.
 *
 * Usage in engine test classes:
 * <pre>{@code
 * @TestFactory
 * Stream<DynamicTest> upstreamSuite() {
 *     var suite = RegexTestSuite.loadAll(TESTDATA_PATH);
 *     return TestRunner.run(suite, capabilities, this::compile);
 * }
 * }</pre>
 */
public final class TestRunner {

    public static Stream<DynamicTest> run(
            RegexTestSuite suite,
            EngineCapabilities capabilities,
            Function<RegexTest, CompiledRegex> compiler) {

        return suite.tests().stream().map(test -> {
            String name = test.groupName() + "/" + test.name();
            return DynamicTest.dynamicTest(name, () -> {
                if (!capabilities.supports(test)) {
                    return; // skip
                }
                CompiledRegex compiled = compiler.apply(test);
                TestResult result = compiled.run(test);
                assertResult(test, result);
            });
        });
    }

    private static void assertResult(RegexTest test, TestResult result) {
        switch (result) {
            case TestResult.Skipped s -> { /* OK */ }
            case TestResult.Failed f ->
                throw new AssertionError("Test failed: " + f.reason());
            case TestResult.Matched m ->
                assertMatched(test, m);
            case TestResult.Matches m ->
                assertMatches(test, m);
            case TestResult.CaptureResults c ->
                assertCaptures(test, c);
        }
    }

    // Assert methods compare actual results against test.matches()
    // Implementation compares spans and capture groups.
    private static void assertMatched(RegexTest test, TestResult.Matched result) {
        boolean expected = !test.matches().isEmpty();
        if (expected != result.matches()) {
            throw new AssertionError(
                "expected match=" + expected + " but got " + result.matches());
        }
    }

    private static void assertMatches(RegexTest test, TestResult.Matches result) {
        // Compare result.spans() against test.matches() overall spans
    }

    private static void assertCaptures(RegexTest test, TestResult.CaptureResults result) {
        // Compare result.captures() against test.matches() with groups
    }
}
```

The assert methods need full implementation during execution — compare actual vs expected spans accounting for `match-limit`, byte-to-char offset conversion, etc. Ref: `upstream/regex/regex-test/lib.rs` lines 730-900.

- [ ] **Step 5: Write unit test for TestRunner**

Create: `regex-test/src/test/java/lol/ohai/regex/test/TestRunnerTest.java`

Test with a mock engine that returns known results:

```java
@TestFactory
Stream<DynamicTest> mockEnginePassesSimpleTest() {
    // Create a suite with one hand-built test
    // Provide a mock compiler that returns the expected match
    // Verify the dynamic test passes
}
```

- [ ] **Step 6: Run tests**

Run: `mvn test -pl regex-test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add regex-test/
git commit -m "add TestRunner with JUnit 6 dynamic test generation"
```

---

## Chunk 3: regex-syntax AST

### Task 6: AST Node Types

**Files:**
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/ast/Ast.java`
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/ast/Span.java`
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/ast/Flags.java`
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/ast/ClassSetItem.java`
- Create: additional supporting types as needed

Ref: `upstream/regex/regex-syntax/src/ast/mod.rs` for the complete AST definition.

- [ ] **Step 1: Create Span record**

```java
package lol.ohai.regex.syntax.ast;

/** Position span in the source pattern string. */
public record Span(int start, int end) {}
```

- [ ] **Step 2: Create core Ast sealed interface**

```java
package lol.ohai.regex.syntax.ast;

import java.util.List;

/**
 * Abstract syntax tree for a regex pattern.
 * Faithful representation — preserves structure for round-tripping.
 *
 * @see <a href="upstream/regex/regex-syntax/src/ast/mod.rs">upstream AST</a>
 */
public sealed interface Ast {
    Span span();

    record Empty(Span span) implements Ast {}
    record Literal(Span span, LiteralKind kind, char c) implements Ast {}
    record Dot(Span span) implements Ast {}
    record ClassBracketed(Span span, boolean negated, ClassSet items) implements Ast {}
    record ClassPerl(Span span, PerlClass kind, boolean negated) implements Ast {}
    record ClassUnicode(Span span, boolean negated, UnicodeClass kind) implements Ast {}
    record Repetition(Span span, RepetitionOp op, boolean greedy, Ast sub) implements Ast {}
    record Group(Span span, GroupKind kind, Ast sub) implements Ast {}
    record Concat(Span span, List<Ast> asts) implements Ast {}
    record Alternation(Span span, List<Ast> asts) implements Ast {}
    record Assertion(Span span, AssertionKind kind) implements Ast {}
    record SetFlags(Span span, FlagsItem flags) implements Ast {}
}
```

The supporting enums and types (`LiteralKind`, `PerlClass`, `GroupKind`, `AssertionKind`, `RepetitionOp`, `ClassSet`, `UnicodeClass`, `FlagsItem`) should be defined as separate types in the same package. Consult `upstream/regex/regex-syntax/src/ast/mod.rs` for the full set.

- [ ] **Step 3: Create supporting enum types**

Create each in its own file in `lol.ohai.regex.syntax.ast`:

- `LiteralKind` — `VERBATIM`, `HEX_FIXED(int digits)`, `SPECIAL`
- `PerlClass` — `DIGIT`, `SPACE`, `WORD`
- `AssertionKind` — `START_LINE`, `END_LINE`, `START_TEXT`, `END_TEXT`, `WORD_BOUNDARY`, `NOT_WORD_BOUNDARY`
- `GroupKind` — `CAPTURING(int index, String name)`, `NON_CAPTURING`, `FLAGS(FlagsItem)`
- `RepetitionOp` — models `?`, `*`, `+`, `{n}`, `{n,}`, `{n,m}`
- `ClassSet` and `ClassSetItem` — bracketed class internals
- `UnicodeClass` — named property, general category, script, etc.
- `FlagsItem` — flag operations (set/clear `i`, `m`, `s`, `x`, `u`)

Ref: `upstream/regex/regex-syntax/src/ast/mod.rs` for exact variant lists.

- [ ] **Step 4: Write compilation test**

Create: `regex-syntax/src/test/java/lol/ohai/regex/syntax/ast/AstTest.java`

```java
@Test
void canConstructBasicAst() {
    var lit = new Ast.Literal(new Span(0, 1), LiteralKind.VERBATIM, 'a');
    assertEquals('a', lit.c());
    assertEquals(0, lit.span().start());
}

@Test
void sealedInterfacePatternMatch() {
    Ast ast = new Ast.Dot(new Span(0, 1));
    switch (ast) {
        case Ast.Dot d -> assertEquals(0, d.span().start());
        default -> fail("unexpected type");
    }
}
```

- [ ] **Step 5: Run tests**

Run: `mvn test -pl regex-syntax`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add regex-syntax/
git commit -m "add AST node types for regex-syntax"
```

### Task 7: Parser (Pattern → AST)

**Files:**
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/ast/Parser.java`
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/ast/Error.java`

This is the largest single task. The parser is a recursive descent parser matching the upstream at `upstream/regex/regex-syntax/src/ast/parse.rs` (~4000 lines). Implement incrementally, starting with basic patterns and expanding.

Ref: `upstream/regex/regex-syntax/src/ast/parse.rs`

- [ ] **Step 1: Create Error type**

```java
package lol.ohai.regex.syntax.ast;

public final class Error extends Exception {
    private final Span span;
    private final ErrorKind kind;

    public enum ErrorKind {
        UNEXPECTED_EOF,
        UNEXPECTED_CHAR,
        UNCLOSED_GROUP,
        UNCLOSED_BRACKET,
        INVALID_REPETITION,
        NESTING_TOO_DEEP,
        INVALID_ESCAPE,
        INVALID_UNICODE_PROPERTY,
        // ... extend as needed from upstream
    }
    // constructor, getters
}
```

- [ ] **Step 2: Write failing tests for basic patterns**

Create: `regex-syntax/src/test/java/lol/ohai/regex/syntax/ast/ParserTest.java`

```java
@Test void parseLiteral() throws Error {
    Ast ast = Parser.parse("a");
    assertInstanceOf(Ast.Literal.class, ast);
}

@Test void parseDot() throws Error {
    Ast ast = Parser.parse(".");
    assertInstanceOf(Ast.Dot.class, ast);
}

@Test void parseConcat() throws Error {
    Ast ast = Parser.parse("ab");
    assertInstanceOf(Ast.Concat.class, ast);
}

@Test void parseAlternation() throws Error {
    Ast ast = Parser.parse("a|b");
    assertInstanceOf(Ast.Alternation.class, ast);
}

@Test void parseGroup() throws Error {
    Ast ast = Parser.parse("(a)");
    assertInstanceOf(Ast.Group.class, ast);
}

@Test void parseRepetition() throws Error {
    Ast ast = Parser.parse("a*");
    assertInstanceOf(Ast.Repetition.class, ast);
}

@Test void parseCharClass() throws Error {
    Ast ast = Parser.parse("[abc]");
    assertInstanceOf(Ast.ClassBracketed.class, ast);
}

@Test void nestLimitEnforced() {
    String deep = "(".repeat(200) + "a" + ")".repeat(200);
    assertThrows(Error.class, () -> Parser.parse(deep));
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl regex-syntax -Dtest="ParserTest"`
Expected: FAIL — Parser doesn't exist

- [ ] **Step 4: Implement Parser — core structure**

```java
package lol.ohai.regex.syntax.ast;

public final class Parser {
    private final String pattern;
    private int pos;
    private int nestLevel;
    private int nestLimit = 250;
    private int captureIndex;

    public static Ast parse(String pattern) throws Error {
        return new Parser(pattern).parseInternal();
    }

    private Parser(String pattern) {
        this.pattern = pattern;
        this.pos = 0;
    }

    private Ast parseInternal() throws Error {
        Ast ast = parseAlternation();
        if (pos != pattern.length()) {
            throw error(ErrorKind.UNEXPECTED_CHAR);
        }
        return ast;
    }

    // Recursive descent: alternation → concat → repetition → atom
    // Ref: upstream/regex/regex-syntax/src/ast/parse.rs
}
```

Implement incrementally following upstream's parser structure. Key methods:
- `parseAlternation()` — `expr ('|' expr)*`
- `parseConcat()` — `atom+`
- `parseRepetition()` — `atom quantifier?`
- `parseAtom()` — literal, dot, group, class, escape, assertion
- `parseGroup()` — `'(' groupKind expr ')'`
- `parseCharClass()` — `'[' classItems ']'`
- `parseEscape()` — `'\' escapeKind`

- [ ] **Step 5: Run tests**

Run: `mvn test -pl regex-syntax -Dtest="ParserTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add regex-syntax/
git commit -m "add regex parser (pattern to AST)"
```

### Task 8: AST Printer (Round-Trip Validation)

**Files:**
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/ast/Printer.java`

Ref: `upstream/regex/regex-syntax/src/ast/print.rs`

- [ ] **Step 1: Write failing round-trip tests**

Create: `regex-syntax/src/test/java/lol/ohai/regex/syntax/ast/PrinterTest.java`

```java
@ParameterizedTest
@ValueSource(strings = {
    "a", "abc", "a|b", "(a)", "(?:a)", "a*", "a+", "a?",
    "a{3}", "a{3,}", "a{3,5}", "[abc]", "[^abc]", "[a-z]",
    "\\d", "\\w", "\\s", ".", "^", "$", "\\b",
    "(?i)abc", "(?i:abc)", "(?P<name>abc)"
})
void roundTrip(String pattern) throws Error {
    Ast ast = Parser.parse(pattern);
    String printed = Printer.print(ast);
    assertEquals(pattern, printed);
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn test -pl regex-syntax -Dtest="PrinterTest"`
Expected: FAIL

- [ ] **Step 3: Implement Printer**

Pattern-match on the sealed `Ast` interface to reconstruct the pattern string.

- [ ] **Step 4: Run tests**

Run: `mvn test -pl regex-syntax -Dtest="PrinterTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add regex-syntax/
git commit -m "add AST printer with round-trip tests"
```

---

## Chunk 4: regex-syntax HIR

### Task 9: HIR Node Types

**Files:**
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/Hir.java`
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/ClassUnicode.java`
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/ClassBytes.java`
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/Look.java`

Ref: `upstream/regex/regex-syntax/src/hir/mod.rs`

- [ ] **Step 1: Create Look enum**

```java
package lol.ohai.regex.syntax.hir;

public enum Look {
    START_LINE, END_LINE,
    START_TEXT, END_TEXT,
    WORD_BOUNDARY_UNICODE, WORD_BOUNDARY_ASCII,
    WORD_BOUNDARY_UNICODE_NEGATE, WORD_BOUNDARY_ASCII_NEGATE
}
```

- [ ] **Step 2: Create ClassUnicode**

```java
package lol.ohai.regex.syntax.hir;

import java.util.List;

/**
 * A Unicode character class: sorted, non-overlapping codepoint ranges.
 */
public record ClassUnicode(List<ClassUnicodeRange> ranges) {
    public record ClassUnicodeRange(int start, int end) {
        // start and end are inclusive codepoints
    }
}
```

- [ ] **Step 3: Create Hir sealed interface**

```java
package lol.ohai.regex.syntax.hir;

import java.util.List;

public sealed interface Hir {
    record Empty() implements Hir {}
    record Literal(byte[] bytes) implements Hir {}
    record Class(ClassUnicode unicode) implements Hir {}
    record LookHir(Look look) implements Hir {}
    record Repetition(int min, int max, boolean greedy, Hir sub) implements Hir {
        /** Unbounded max represented as Integer.MAX_VALUE */
        public static final int UNBOUNDED = Integer.MAX_VALUE;
    }
    record Capture(int index, String name, Hir sub) implements Hir {}
    record Concat(List<Hir> subs) implements Hir {}
    record Alternation(List<Hir> subs) implements Hir {}
}
```

- [ ] **Step 4: Write basic tests**

Create: `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/HirTest.java`

```java
@Test void literalToUtf8Bytes() {
    var lit = new Hir.Literal("a".getBytes(StandardCharsets.UTF_8));
    assertArrayEquals(new byte[]{0x61}, lit.bytes());
}

@Test void unicodeClassRangesAreSorted() {
    // Construct and verify
}
```

- [ ] **Step 5: Run tests, commit**

Run: `mvn test -pl regex-syntax`

```bash
git add regex-syntax/
git commit -m "add HIR node types"
```

### Task 10: AST → HIR Translator (ASCII-First)

**Files:**
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/Translator.java`
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/Error.java`

Start with ASCII-capable patterns. Unicode property expansion deferred to Task 11.

Ref: `upstream/regex/regex-syntax/src/hir/translate.rs`

- [ ] **Step 1: Create hir.Error type**

```java
package lol.ohai.regex.syntax.hir;

public final class Error extends Exception {
    public enum ErrorKind {
        UNSUPPORTED_UNICODE_PROPERTY,
        INVALID_UTF8,
        // extend as needed
    }
    // constructor with ErrorKind and message
}
```

- [ ] **Step 2: Write failing tests**

Create: `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/TranslatorTest.java`

```java
@Test void translateLiteral() throws Exception {
    Hir hir = translate("a");
    assertInstanceOf(Hir.Literal.class, hir);
    assertArrayEquals(new byte[]{0x61}, ((Hir.Literal) hir).bytes());
}

@Test void translateDot() throws Exception {
    Hir hir = translate(".");
    // Dot becomes a Class with all codepoints except \n
    assertInstanceOf(Hir.Class.class, hir);
}

@Test void translateRepetition() throws Exception {
    Hir hir = translate("a*");
    assertInstanceOf(Hir.Repetition.class, hir);
    var rep = (Hir.Repetition) hir;
    assertEquals(0, rep.min());
    assertEquals(Hir.Repetition.UNBOUNDED, rep.max());
    assertTrue(rep.greedy());
}

@Test void translateCaptureGroup() throws Exception {
    Hir hir = translate("(a)");
    assertInstanceOf(Hir.Capture.class, hir);
    assertEquals(1, ((Hir.Capture) hir).index());
}

@Test void translateFlagsCaseInsensitive() throws Exception {
    Hir hir = translate("(?i)a");
    // 'a' with case-insensitive → Class with [aA]
    assertInstanceOf(Hir.Class.class, hir);
}

private static Hir translate(String pattern) throws Exception {
    Ast ast = Parser.parse(pattern);
    return Translator.translate(ast);
}
```

- [ ] **Step 3: Implement Translator**

Visitor over the AST that produces HIR. Key transformations:
- Literals → `Hir.Literal` (encode char to UTF-8 bytes)
- Dot → `Hir.Class` (all codepoints except `\n`, or all if `(?s)`)
- `\d`, `\w`, `\s` → `Hir.Class` with appropriate ranges
- Character classes → `Hir.Class` with sorted, non-overlapping, merged ranges
- Flags → resolved and propagated (case insensitive expands literals to classes)
- Groups → `Hir.Capture` or transparent (non-capturing groups are erased)

Ref: `upstream/regex/regex-syntax/src/hir/translate.rs`

- [ ] **Step 4: Run tests**

Run: `mvn test -pl regex-syntax -Dtest="TranslatorTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add regex-syntax/
git commit -m "add AST to HIR translator (ASCII-first)"
```

### Task 11: Unicode Tables

**Files:**
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/unicode/` (package)
- Multiple generated/ported data files

Mechanically port the sorted codepoint-range arrays from `upstream/regex/regex-syntax/src/unicode_tables/`. Each file is a set of `static final int[][]` arrays.

Ref: `upstream/regex/regex-syntax/src/unicode_tables/`

This task is large but mechanical. Key tables:
- `perl_decimal.rs` → `PerlDecimal.java` (`\d`)
- `perl_space.rs` → `PerlSpace.java` (`\s`)
- `perl_word.rs` → `PerlWord.java` (`\w`)
- `general_category.rs` → `GeneralCategory.java`
- `script.rs` → `Script.java`
- `case_folding_simple.rs` → `CaseFolding.java`
- `property_bool.rs` → `PropertyBool.java`

- [ ] **Step 1: Port Perl character classes (\d, \s, \w)**

These are the most immediately useful. Each is a sorted array of `[start, end]` codepoint ranges.

- [ ] **Step 2: Write validation tests**

Create: `regex-syntax/src/test/java/lol/ohai/regex/syntax/unicode/UnicodeTablesTest.java`

```java
@Test void digitContainsAsciiDigits() {
    assertTrue(PerlDecimal.contains('0'));
    assertTrue(PerlDecimal.contains('9'));
    assertFalse(PerlDecimal.contains('a'));
}

@Test void wordContainsUnderscore() {
    assertTrue(PerlWord.contains('_'));
}

@Test void spaceContainsTab() {
    assertTrue(PerlSpace.contains('\t'));
}
```

- [ ] **Step 3: Wire Unicode tables into Translator**

Update `Translator` to use actual Unicode ranges for `\d`, `\w`, `\s`, `\p{...}`, etc.

- [ ] **Step 4: Port remaining tables as needed**

General category, scripts, case folding, property booleans. Port on demand as the translator needs them.

- [ ] **Step 5: Run tests, commit**

Run: `mvn test -pl regex-syntax`

```bash
git add regex-syntax/
git commit -m "add Unicode tables and wire into HIR translator"
```

---

## Chunk 5: regex-automata (NFA + PikeVM)

### Task 12: NFA Types

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/State.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/NFA.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/util/SparseSet.java`

Ref: `upstream/regex/regex-automata/src/nfa/thompson/nfa.rs`

- [ ] **Step 1: Create State sealed interface**

```java
package lol.ohai.regex.automata.nfa.thompson;

import lol.ohai.regex.syntax.hir.Look;

public sealed interface State {
    record ByteRange(int start, int end, int next) implements State {}
    record Sparse(Transition[] transitions) implements State {}
    record Dense(int[] next) implements State {
        // next[byte] = stateId, indexed by byte value 0-255
    }
    record LookState(Look look, int next) implements State {}
    record Union(int[] alternates) implements State {}
    record BinaryUnion(int alt1, int alt2) implements State {}
    record Capture(int next, int groupIndex, int slotIndex) implements State {}
    record Match(int patternId) implements State {}
    record Fail() implements State {}
}

record Transition(int start, int end, int next) {}
```

- [ ] **Step 2: Create NFA class**

```java
package lol.ohai.regex.automata.nfa.thompson;

import java.util.List;

public final class NFA {
    private final State[] states;
    private final int startAnchored;
    private final int startUnanchored;
    private final int captureSlotCount;
    private final List<String> captureNames;
    // constructor, accessors
}
```

- [ ] **Step 3: Create SparseSet**

```java
package lol.ohai.regex.automata.util;

/**
 * O(1) insert, contains, and clear for integer sets.
 * Used for tracking active NFA states in PikeVM.
 */
public final class SparseSet {
    private final int[] dense;
    private final int[] sparse;
    private int size;

    public SparseSet(int capacity) { ... }
    public boolean contains(int value) { ... }
    public void insert(int value) { ... }
    public void clear() { ... }
    public int size() { return size; }
}
```

- [ ] **Step 4: Test SparseSet**

Create: `regex-automata/src/test/java/lol/ohai/regex/automata/util/SparseSetTest.java`

- [ ] **Step 5: Run tests, commit**

Run: `mvn test -pl regex-automata`

```bash
git add regex-automata/
git commit -m "add NFA types and SparseSet utility"
```

### Task 13: NFA Compiler (HIR → NFA)

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Compiler.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Builder.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/BuildError.java`

Ref: `upstream/regex/regex-automata/src/nfa/thompson/compiler.rs` and `builder.rs`

- [ ] **Step 1: Create Builder**

The Builder accumulates states and produces an NFA. Key methods:
- `addState(State) → int` (returns state ID)
- `addMatch(int patternId) → int`
- `addCapture(int groupIndex, int slotIndex, int next) → int`
- `addUnion(int[] alternates) → int`
- `addBinaryUnion(int alt1, int alt2) → int`
- `patch(int stateId, int newNext)` — update a state's next pointer
- `build() → NFA`

- [ ] **Step 2: Create Compiler**

Translates HIR → NFA via the Builder. Recursive descent over HIR nodes.

Key methods:
- `compile(Hir) → NFA`
- `compileNode(Hir) → (start, end)` state ID pair
- `compileLiteral(byte[]) → (start, end)` — chain of ByteRange states
- `compileClass(ClassUnicode) → (start, end)` — UTF-8 byte automaton
- `compileRepetition(Hir.Repetition) → (start, end)` — Union + looping states
- `compileCapture(Hir.Capture) → (start, end)` — Capture states around sub
- `compileConcat(List<Hir>) → (start, end)` — chain sub-expressions
- `compileAlternation(List<Hir>) → (start, end)` — Union state

Ref: `upstream/regex/regex-automata/src/nfa/thompson/compiler.rs`

- [ ] **Step 3: Write tests**

Create: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/CompilerTest.java`

```java
@Test void compileLiteral() throws Exception {
    NFA nfa = compile("a");
    // Should have: start → ByteRange('a','a') → Match
    assertTrue(nfa.stateCount() >= 2);
}

@Test void compileAlternation() throws Exception {
    NFA nfa = compile("a|b");
    // Should contain a Union or BinaryUnion state
}

@Test void compileStar() throws Exception {
    NFA nfa = compile("a*");
    // Should contain a loop via Union
}

private static NFA compile(String pattern) throws Exception {
    Ast ast = Parser.parse(pattern);
    Hir hir = Translator.translate(ast);
    return Compiler.compile(hir);
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl regex-automata -Dtest="CompilerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add regex-automata/
git commit -m "add Thompson NFA compiler (HIR to NFA)"
```

### Task 14: PikeVM

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/pikevm/PikeVM.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/pikevm/Cache.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/util/Input.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/util/Captures.java`

Ref: `upstream/regex/regex-automata/src/nfa/thompson/pikevm.rs`

- [ ] **Step 1: Create Input**

```java
package lol.ohai.regex.automata.util;

import java.nio.charset.StandardCharsets;

/**
 * Search configuration. Handles CharSequence → UTF-8 encoding.
 */
public final class Input {
    private final byte[] haystack;
    private final int start;
    private final int end;
    private final boolean anchored;
    // Maps byte offsets ↔ char offsets for the source CharSequence
    private final int[] byteToCharMap;

    public static Input of(CharSequence text) { ... }
    public static Input of(CharSequence text, int start, int end) { ... }
    // Encodes to UTF-8, builds offset map
}
```

- [ ] **Step 2: Create Captures utility**

```java
package lol.ohai.regex.automata.util;

/**
 * Internal capture slot storage. Slots are pairs: [start, end] per group.
 */
public final class Captures {
    private final int[] slots; // length = groupCount * 2
    // -1 means "not captured"

    public int groupCount() { return slots.length / 2; }
    public int start(int group) { return slots[group * 2]; }
    public int end(int group) { return slots[group * 2 + 1]; }
    public void set(int slot, int value) { slots[slot] = value; }
    public void clear() { Arrays.fill(slots, -1); }
    public Captures clone() { ... }
}
```

- [ ] **Step 3: Create Cache**

```java
package lol.ohai.regex.automata.nfa.thompson.pikevm;

import lol.ohai.regex.automata.util.*;

/**
 * Mutable scratch space for PikeVM search. Not thread-safe.
 * Reusable across searches on the same PikeVM instance.
 */
public final class Cache {
    SparseSet currentStates;
    SparseSet nextStates;
    Captures[] threads; // one Captures per active thread
    // Sized from NFA state count and capture slot count
}
```

- [ ] **Step 4: Write failing tests**

Create: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/pikevm/PikeVMTest.java`

```java
@Test void matchLiteral() {
    assertMatch("a", "a", 0, 1);
}

@Test void noMatch() {
    assertNoMatch("a", "b");
}

@Test void matchConcat() {
    assertMatch("abc", "xabcy", 1, 4);
}

@Test void matchAlternation() {
    assertMatch("a|b", "b", 0, 1);
}

@Test void matchStar() {
    assertMatch("a*", "aaa", 0, 3);
}

@Test void matchCapture() {
    var result = findCaptures("(a)(b)", "ab");
    assertEquals(0, result.start(0)); // overall
    assertEquals(2, result.end(0));
    assertEquals(0, result.start(1)); // group 1
    assertEquals(1, result.end(1));
}

private void assertMatch(String pattern, String haystack, int start, int end) {
    // Full pipeline: parse → HIR → NFA → PikeVM search
}
```

- [ ] **Step 5: Implement PikeVM**

```java
package lol.ohai.regex.automata.nfa.thompson.pikevm;

import lol.ohai.regex.automata.nfa.thompson.*;
import lol.ohai.regex.automata.util.*;

public final class PikeVM {
    private final NFA nfa;

    public PikeVM(NFA nfa) { this.nfa = nfa; }

    public Cache createCache() { ... }

    public boolean isMatch(Input input, Cache cache) { ... }

    public Captures search(Input input, Cache cache) { ... }

    /**
     * Core search loop. Steps all active threads through input byte by byte.
     */
    private void searchInternal(Input input, Cache cache, Captures result) {
        // For each byte position:
        //   1. Add start state(s) to current set (epsilon closure)
        //   2. For each state in current set:
        //      - ByteRange: if byte matches, add next to nextStates
        //      - Union/BinaryUnion: add alternates (epsilon closure)
        //      - Look: check assertion, add next if satisfied
        //      - Capture: record slot, add next
        //      - Match: record match, continue for leftmost-longest
        //   3. Swap current/next
    }
}
```

Ref: `upstream/regex/regex-automata/src/nfa/thompson/pikevm.rs` — the `search_imp` method.

- [ ] **Step 6: Run unit tests**

Run: `mvn test -pl regex-automata -Dtest="PikeVMTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add regex-automata/
git commit -m "add PikeVM engine"
```

### Task 15: Wire PikeVM to Upstream Test Suite

**Files:**
- Create: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/pikevm/PikeVMSuiteTest.java`

This is the milestone — running the upstream TOML tests against our engine.

- [ ] **Step 1: Create PikeVM suite test**

```java
package lol.ohai.regex.automata.nfa.thompson.pikevm;

import lol.ohai.regex.test.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.stream.Stream;

class PikeVMSuiteTest {

    private static final Path TESTDATA =
        Path.of("../upstream/regex/testdata");

    @TestFactory
    Stream<DynamicTest> upstreamSuite() throws Exception {
        var suite = RegexTestSuite.loadAll(TESTDATA);
        return TestRunner.run(
            suite,
            EngineCapabilities.pikeVm(),
            this::compile
        );
    }

    private CompiledRegex compile(RegexTest test) {
        try {
            // parse → HIR → NFA → PikeVM
            var ast = Parser.parse(test.regexes().getFirst());
            var hir = Translator.translate(ast);
            var nfa = Compiler.compile(hir);
            var vm = new PikeVM(nfa);
            var cache = vm.createCache();

            return CompiledRegex.compiled(t -> {
                var input = Input.of(t.haystack());
                // Return appropriate TestResult based on what test expects
                var captures = vm.search(input, cache);
                // Convert to TestResult.Matches or TestResult.CaptureResults
            });
        } catch (Exception e) {
            if (!test.compiles()) {
                return CompiledRegex.compiled(t ->
                    new TestResult.Matched(false));
            }
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Run the test suite**

Run: `mvn test -pl regex-automata -Dtest="PikeVMSuiteTest"`

Expect many failures initially. Track pass rate and iterate on parser/translator/NFA/PikeVM until the pass rate is high. Use failing test names to identify missing features.

- [ ] **Step 3: Iterate on failures**

Common categories of initial failures:
- Missing escape sequences in parser
- Unicode class handling
- Edge cases in repetition (lazy, bounded)
- Assertion (look) matching in PikeVM
- Multi-line mode
- Empty match handling

Fix in order of frequency. Each fix round should be its own commit.

- [ ] **Step 4: Commit when pass rate is stable**

```bash
git add regex-automata/
git commit -m "wire PikeVM to upstream TOML test suite"
```

---

## Chunk 6: Public API

### Task 16: `regex` Module Public API

**Files:**
- Create: `regex/src/main/java/lol/ohai/regex/Regex.java`
- Create: `regex/src/main/java/lol/ohai/regex/RegexBuilder.java`
- Create: `regex/src/main/java/lol/ohai/regex/Match.java`
- Create: `regex/src/main/java/lol/ohai/regex/Captures.java`
- Create: `regex/src/main/java/lol/ohai/regex/PatternSyntaxException.java`

- [ ] **Step 1: Create PatternSyntaxException**

```java
package lol.ohai.regex;

public class PatternSyntaxException extends IllegalArgumentException {
    private final String pattern;
    public PatternSyntaxException(String pattern, Throwable cause) {
        super("failed to compile regex: " + pattern, cause);
        this.pattern = pattern;
    }
    public String pattern() { return pattern; }
}
```

- [ ] **Step 2: Create Match record**

```java
package lol.ohai.regex;

public record Match(int start, int end, String text) {
    public int length() { return end - start; }
}
```

- [ ] **Step 3: Create Captures**

```java
package lol.ohai.regex;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Captures {
    private final Match overall;
    private final List<Optional<Match>> groups;
    private final Map<String, Integer> namedGroups;

    public Match group(int index) { ... }
    public Match group(String name) { ... }
    public int groupCount() { ... }
    public Match overall() { return overall; }
}
```

- [ ] **Step 4: Create Regex**

```java
package lol.ohai.regex;

import java.util.Optional;
import java.util.stream.Stream;

public final class Regex {
    // Thread-safe: immutable compiled state + thread-local cache pool
    private final /* NFA + PikeVM */ Object engine;
    private final ThreadLocal<Object> cachePool;

    public static Regex compile(String pattern) throws PatternSyntaxException {
        // parse → HIR → NFA → PikeVM, wrap errors
    }

    public boolean isMatch(CharSequence text) { ... }
    public Optional<Match> find(CharSequence text) { ... }
    public Stream<Match> findAll(CharSequence text) { ... }
    public Optional<Captures> captures(CharSequence text) { ... }
}
```

- [ ] **Step 5: Create RegexBuilder**

```java
package lol.ohai.regex;

public final class RegexBuilder {
    private boolean unicode = true;
    private boolean caseInsensitive = false;
    private int nestLimit = 250;

    public RegexBuilder unicode(boolean unicode) { ... return this; }
    public RegexBuilder caseInsensitive(boolean ci) { ... return this; }
    public RegexBuilder nestLimit(int limit) { ... return this; }
    public Regex build(String pattern) throws PatternSyntaxException { ... }
}
```

- [ ] **Step 6: Write tests**

Create: `regex/src/test/java/lol/ohai/regex/RegexTest.java`

```java
@Test void compileAndMatch() {
    Regex re = Regex.compile("\\d+");
    assertTrue(re.isMatch("abc123"));
}

@Test void find() {
    Regex re = Regex.compile("\\d+");
    var m = re.find("abc123def").orElseThrow();
    assertEquals(3, m.start());
    assertEquals(6, m.end());
    assertEquals("123", m.text());
}

@Test void captures() {
    Regex re = Regex.compile("(?P<year>\\d{4})-(?P<month>\\d{2})");
    var c = re.captures("2026-03").orElseThrow();
    assertEquals("2026", c.group("year").text());
    assertEquals("03", c.group("month").text());
}

@Test void findAll() {
    Regex re = Regex.compile("\\d+");
    var matches = re.findAll("a1b22c333").toList();
    assertEquals(3, matches.size());
}

@Test void invalidPatternThrows() {
    assertThrows(PatternSyntaxException.class,
        () -> Regex.compile("[unclosed"));
}
```

- [ ] **Step 7: Run tests**

Run: `mvn test -pl regex`
Expected: PASS

- [ ] **Step 8: Wire public API to upstream test suite**

Create: `regex/src/test/java/lol/ohai/regex/UpstreamSuiteTest.java`

Similar to Task 15 but through the public `Regex` API.

- [ ] **Step 9: Run full build**

Run: `mvn test`
Expected: All modules pass

- [ ] **Step 10: Commit**

```bash
git add regex/
git commit -m "add public Regex API with upstream test suite validation"
```

---

## Update CLAUDE.md

### Task 17: Update CLAUDE.md Build Commands

After scaffolding is complete, update CLAUDE.md with confirmed build commands.

- [ ] **Step 1: Update Build & Test section**

Replace the TODO placeholder with verified commands.

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "update CLAUDE.md with confirmed build commands"
```
