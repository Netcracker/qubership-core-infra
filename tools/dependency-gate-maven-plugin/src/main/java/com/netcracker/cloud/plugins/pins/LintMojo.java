package com.netcracker.cloud.plugins.pins;

import com.netcracker.cloud.plugins.pins.PinsEvaluator.Finding;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.util.List;

/**
 * Reports inconsistencies inside the pins themselves, such as a BOM pinned below its own modules. Warnings only.
 */
@Mojo(name = "lint", aggregator = true, requiresProject = false, threadSafe = true)
public class LintMojo extends AbstractPinsMojo {

    @Override
    public void execute() throws MojoExecutionException {
        checkParameters();
        if (skip) {
            getLog().info("Skipping dependency pins lint");
            return;
        }
        List<Finding> findings = PinsEvaluator.lint(loadPins());
        log(findings, 5, getLog()::warn);
        getLog().info(findings.size() + " inconsistency(ies) in the pins");
    }
}
