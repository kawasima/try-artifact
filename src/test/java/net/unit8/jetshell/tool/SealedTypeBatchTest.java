package net.unit8.jetshell.tool;

import net.unit8.jetshell.command.JetShellCommandRegister;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Issue #17: a {@code sealed interface}/{@code sealed class} whose permitted
 * subtypes are declared on later lines must compile in (non-interactive) batch
 * mode, the same way it would as a single {@code .java} source file.
 */
@Test
public class SealedTypeBatchTest {

    private static String run(String script) throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        JetShellTool tool = JetShellTool.create(in, ps, ps);
        new JetShellCommandRegister().register(tool);
        tool.testPrompt = true;
        int exitCode = tool.start(new String[0]);
        ps.flush();
        String output = out.toString(StandardCharsets.UTF_8);
        assertEquals(exitCode, 0, "Script should succeed but exited " + exitCode + ". Output:\n" + output);
        return output;
    }

    public void sealedInterfaceWithLaterSubtypesCompilesAndSubtypesAreUsable() throws Exception {
        String output = run(
                "sealed interface Shape {}\n" +
                "record Circle(double radius) implements Shape {}\n" +
                "record Rect(double width, double height) implements Shape {}\n" +
                // A bare expression yields JShell's "Expression value is:" feedback,
                // proving Circle is an independently usable snippet.
                "new Circle(2.0).radius()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 2.0"),
                "Circle should be an independently usable snippet. Output:\n" + output);
    }

    public void sealednessIsPreservedSoExhaustiveSwitchNeedsNoDefault() throws Exception {
        String output = run(
                "sealed interface Shape {}\n" +
                "record Circle(double radius) implements Shape {}\n" +
                "record Rect(double width, double height) implements Shape {}\n" +
                // A switch with no default compiles only when Shape is genuinely sealed
                // to exactly {Circle, Rect}.
                "String k = switch ((Shape) new Circle(1.0)) { case Circle c -> \"C\"; case Rect r -> \"R\"; };\n" +
                "/exit\n");
        assertTrue(output.contains("with initial value \"C\""),
                "Exhaustive switch over the sealed type should compile and run. Output:\n" + output);
    }

    public void sealedClassWithLaterSubclassesCompiles() throws Exception {
        String output = run(
                "sealed abstract class Expr {}\n" +
                "final class Lit extends Expr { int v; Lit(int v){ this.v = v; } }\n" +
                "final class Neg extends Expr { Expr e; Neg(Expr e){ this.e = e; } }\n" +
                "new Lit(7).v\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 7"),
                "sealed class subclasses should be usable. Output:\n" + output);
    }

    // Finding #1: a following type that merely mentions the sealed type inside a
    // generic supertype argument must NOT be pulled into the permits clause.
    public void unrelatedTypeMentioningSealedInGenericsIsNotPermitted() throws Exception {
        String output = run(
                "sealed interface Shape {}\n" +
                "record Circle(double radius) implements Shape {}\n" +
                "record Rect(double width, double height) implements Shape {}\n" +
                // Iterable<Shape>: Shape appears only as a type argument, not as a supertype.
                "class ShapeBag implements Iterable<Shape> {\n" +
                "  public java.util.Iterator<Shape> iterator() { return java.util.List.<Shape>of().iterator(); }\n" +
                "}\n" +
                "new Circle(3.0).radius()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 3.0"),
                "The sealed hierarchy must compile; ShapeBag must not join its permits. Output:\n" + output);
    }

    // Finding #2: a nested sealed type (itself a subtype) must also be deferred and
    // given a synthesised permits clause, not evaluated as-is.
    public void nestedSealedHierarchyCompiles() throws Exception {
        String output = run(
                "sealed interface Shape {}\n" +
                "sealed interface Poly extends Shape {}\n" +
                "record Tri() implements Poly {}\n" +
                "record Quad() implements Poly {}\n" +
                "new Tri()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: Tri[]"),
                "Nested sealed hierarchy should compile end to end. Output:\n" + output);
    }

    // Finding #4: a `non-sealed` subtype must not be misread as a sealed declaration,
    // and a string literal containing declaration-like text must not derail parsing.
    public void nonSealedSubtypeAndStringLiteralAreHandled() throws Exception {
        String output = run(
                "String note = \"sealed interface Ghost {}\";\n" +
                "sealed interface Shape {}\n" +
                "non-sealed interface Openable extends Shape {}\n" +
                "record Circle(double radius) implements Shape {}\n" +
                "new Circle(4.0).radius()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 4.0"),
                "non-sealed subtype and string literal must not break the hierarchy. Output:\n" + output);
    }

    // Copilot review #1: a comment or blank line between the sealed type and its
    // subtypes (as seen when a whole file is processed via /open) must not finalise
    // the hierarchy prematurely.
    public void commentsBetweenSealedTypeAndSubtypesDoNotBreakIt() throws Exception {
        Path file = Files.createTempFile("hier", ".jsh");
        Files.writeString(file,
                "sealed interface Shape {}\n" +
                "// the circle case\n" +
                "record Circle(double radius) implements Shape {}\n" +
                "\n" +
                "/* the rectangle case */\n" +
                "record Rect(double width, double height) implements Shape {}\n" +
                "new Circle(6.0).radius()\n");
        try {
            String output = run("/open " + file + "\n/exit\n");
            assertTrue(output.contains("Expression value is: 6.0"),
                    "Comments/blank lines must be transparent to the hierarchy. Output:\n" + output);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    // Copilot review #2: a supertype referenced by a qualified name whose final
    // segment equals the sealed type's simple name (e.g. java.rmi.Remote vs a local
    // Remote) must NOT be treated as a permitted subtype.
    public void qualifiedSupertypeSharingSimpleNameIsNotPermitted() throws Exception {
        String output = run(
                "sealed interface Remote {}\n" +
                "record Node() implements Remote {}\n" +
                // Gateway extends the JDK's java.rmi.Remote, a different type.
                "interface Gateway extends java.rmi.Remote {}\n" +
                "new Node()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: Node[]"),
                "Gateway (java.rmi.Remote) must not join the local Remote's permits. Output:\n" + output);
    }

    // Review #1: a `extends` inside a type-parameter bound must not be read as a
    // supertype, so a generic type bounded by the sealed type is not a subtype.
    public void typeParameterBoundIsNotASupertype() throws Exception {
        String output = run(
                "sealed interface Shape {}\n" +
                "record Circle(double radius) implements Shape {}\n" +
                // Registry is bounded by Shape but does NOT extend/implement it.
                "class Registry<T extends Shape> { T held; }\n" +
                "new Circle(3.0).radius()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 3.0"),
                "A type-parameter bound must not make Registry a permitted subtype. Output:\n" + output);
    }

    // Review #2: a `//` or `/*` inside a string literal in the declaration header
    // must not be treated as a comment and truncate detection.
    public void commentMarkerInsideStringLiteralDoesNotBreakDetection() throws Exception {
        String output = run(
                "@SuppressWarnings(\"a//b\") sealed interface Shape {}\n" +
                "record Circle(double radius) implements Shape {}\n" +
                "record Rect(double width, double height) implements Shape {}\n" +
                "new Circle(5.0).radius()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 5.0"),
                "A string literal containing // must not break sealed detection. Output:\n" + output);
    }

    // Review #3: a brace inside a comment in the sealed header must not be mistaken
    // for the type body when the permits clause is injected.
    public void braceInsideCommentInSealedHeaderIsHandled() throws Exception {
        String output = run(
                "sealed interface Shape /* body starts at { below */ {}\n" +
                "record Circle(double radius) implements Shape {}\n" +
                "record Rect(double width, double height) implements Shape {}\n" +
                "new Circle(7.0).radius()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 7.0"),
                "A brace inside a comment must not misplace the permits clause. Output:\n" + output);
    }

    // Review #4: an import statement between the sealed type and its subtypes must
    // be transparent to the hierarchy, not finalise it prematurely.
    public void importBetweenSealedTypeAndSubtypesIsTransparent() throws Exception {
        String output = run(
                "sealed interface Shape {}\n" +
                "import java.util.Optional;\n" +
                "record Circle(double radius) implements Shape {}\n" +
                "record Rect(double width, double height) implements Shape {}\n" +
                "new Circle(8.0).radius()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 8.0"),
                "An interleaved import must not break the hierarchy. Output:\n" + output);
    }

    // Copilot review: an annotation whose argument string contains ')' must not
    // defeat declaration detection.
    public void annotationArgumentWithParenIsHandled() throws Exception {
        String output = run(
                "@SuppressWarnings(\")x\") sealed interface Shape {}\n" +
                "record Circle(double radius) implements Shape {}\n" +
                "record Rect(double width, double height) implements Shape {}\n" +
                "new Circle(2.0).radius()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 2.0"),
                "A ')' inside an annotation string must not break detection. Output:\n" + output);
    }

    // Copilot review: an annotation array argument contains braces that must not be
    // mistaken for the type body.
    public void annotationArrayArgumentIsHandled() throws Exception {
        String output = run(
                "@SuppressWarnings({\"a\", \"b\"}) sealed interface Shape {}\n" +
                "record Circle(double radius) implements Shape {}\n" +
                "record Rect(double width, double height) implements Shape {}\n" +
                "new Circle(3.0).radius()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 3.0"),
                "Braces in an annotation array must not be read as the type body. Output:\n" + output);
    }

    // Review: a type implementing a NESTED type of the sealed type (Shape.Marker)
    // must not be treated as a subtype of the outer sealed type.
    public void subtypeOfNestedTypeIsNotPermittedOnOuter() throws Exception {
        String output = run(
                "sealed interface Shape { interface Marker {} }\n" +
                "record Circle(double radius) implements Shape {}\n" +
                // Tag implements Shape.Marker, NOT Shape.
                "class Tag implements Shape.Marker {}\n" +
                "new Circle(2.0).radius()\n" +
                "/exit\n");
        assertTrue(output.contains("Expression value is: 2.0"),
                "Tag (Shape.Marker) must not join the outer Shape's permits. Output:\n" + output);
    }
}
