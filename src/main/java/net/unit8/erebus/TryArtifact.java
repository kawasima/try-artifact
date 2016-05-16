package net.unit8.erebus;

import net.unit8.erebus.tryartifact.command.TryArtifactCommandRegister;
import net.unit8.erebus.tryartifact.tool.TryJShellTool;

import java.io.ByteArrayInputStream;

/**
 * @author kawasima
 */
public class TryArtifact {
    public static void main(String[] args) throws Exception {
        TryJShellTool tool = new TryJShellTool(System.in, System.out, System.err, System.out,
                new ByteArrayInputStream(new byte[0]), System.out, System.err);
        new TryArtifactCommandRegister().register(tool);

        tool.start(args);
    }
}
