package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitRemoteHelperCommandTest {

    @Test
    void repoHintsShowDefinitionTextAsText() {
        // A template, project-local included, decides a repository's path and URL (#1624).
        var repo = new ImageDef.RepoEntry();
        repo.setUrl("https://github.com/owner/app\033]0;pwned\007.git");
        repo.setPath("~/\033[2Japp");

        assertEquals(List.of(
                "The path 'x 2J' is not a git repository in instance 'work'.",
                "Known repositories:",
                "  ~/ [2Japp  (https://github.com/owner/app ]0;pwned .git)"),
                GitRemoteHelperCommand.repoHints("work", "x\u009b2J", List.of(repo)));
    }
}
