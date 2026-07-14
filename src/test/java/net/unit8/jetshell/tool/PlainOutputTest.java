package net.unit8.jetshell.tool;

import net.unit8.jetshell.command.JetShellCommandRegister;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Issue #12: -plain drops the "|  " prefix from batch output; -quiet suppresses
 * informational output entirely while still reporting errors on stderr.
 */
@Test
public class PlainOutputTest {

    private static class Result {
        final String out;
        final String err;
        Result(String out, String err) {
            this.out = out;
            this.err = err;
        }
    }

    private static Result run(String[] args, String script) throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream psOut = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream psErr = new PrintStream(err, true, StandardCharsets.UTF_8);
        JetShellTool tool = JetShellTool.create(in, psOut, psErr);
        new JetShellCommandRegister().register(tool);
        tool.testPrompt = true;
        tool.start(args);
        psOut.flush();
        psErr.flush();
        return new Result(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    public void normalModeKeepsPrefix() throws Exception {
        Result r = run(new String[0], "var x = 1 + 1;\n/exit\n");
        assertTrue(r.out.contains("|  Added variable x of type int with initial value 2"),
                "Normal mode must keep the '|  ' prefix. Output:\n" + r.out);
    }

    public void plainDropsPrefixOnStdout() throws Exception {
        Result r = run(new String[]{"-plain"}, "var x = 1 + 1;\n/exit\n");
        assertTrue(r.out.contains("Added variable x of type int with initial value 2"),
                "Plain mode must still print the message. Output:\n" + r.out);
        assertFalse(r.out.contains("|  "),
                "Plain mode must drop the '|  ' prefix. Output:\n" + r.out);
    }

    public void plainDropsPrefixOnErrors() throws Exception {
        Result r = run(new String[]{"-plain"}, "int y = \"nope\";\n/exit\n");
        assertTrue(r.out.contains("Error:"),
                "Plain mode still reports the error (on stdout, as today). Output:\n" + r.out);
        assertFalse(r.out.contains("|  "),
                "Plain mode must drop the '|  ' prefix from diagnostics. Output:\n" + r.out);
    }

    public void quietSuppressesInformationalOutput() throws Exception {
        Result r = run(new String[]{"-quiet"}, "var x = 1 + 1;\n/exit\n");
        assertTrue(r.out.isEmpty(),
                "Quiet mode must produce no informational stdout. Output:\n" + r.out);
    }

    public void quietStillReportsErrorsOnStderr() throws Exception {
        Result r = run(new String[]{"-quiet"}, "int y = \"nope\";\n/exit\n");
        assertTrue(r.err.contains("Error:"),
                "Quiet mode must still report errors on stderr. Stderr:\n" + r.err);
        assertFalse(r.out.contains("Error:"),
                "Quiet mode must not print errors on stdout. Stdout:\n" + r.out);
        assertFalse(r.err.contains("|  "),
                "Quiet mode stderr must have no '|  ' prefix. Stderr:\n" + r.err);
    }
}
