package net.unit8.jetshell.tool;

import net.unit8.jetshell.command.JetShellCommandRegister;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
}
