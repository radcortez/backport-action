///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.eclipse.jgit:org.eclipse.jgit:5.10.0.202012080955-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.gpg.bc:5.10.0.202012080955-r
//DEPS org.kohsuke:github-api:1.122
//DEPS commons-io:commons-io:2.8.0
//DEPS com.jayway.jsonpath:json-path:2.5.0
//DEPS org.slf4j:slf4j-jdk14:1.7.30
//SOURCES backport.java

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "backportAction", mixinStandardHelpOptions = true, version = "backport action 0.1")
public class backportAction implements Callable<Integer> {
    @CommandLine.Parameters(index = "0", defaultValue = "${GITHUB_TOKEN}")
    private String token;
    @CommandLine.Parameters(index = "1", defaultValue = "${GITHUB_REPOSITORY}")
    private String repository;
    @CommandLine.Parameters(index = "2", defaultValue = "${GITHUB_EVENT_PATH}")
    private File eventPath;

    public static void main(String... args) {
        int exitCode = new CommandLine(new backportAction()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws IOException {
        DocumentContext context = JsonPath.parse(eventPath);
        System.out.println(context.jsonString());

        String repositoryFullName = context.read("repository.full_name", String.class);
        if (!repository.equals(repositoryFullName)) {
            return 0;
        }

        if (!isCandidateForBackport(context)) {
            return 0;
        }

        String[] args = {
            token,
            repository,
            getPullRequestNumber(context).toString()
        };

        return new CommandLine(new backport()).execute(args);
    }

    private boolean isCandidateForBackport(DocumentContext context) {
        String action = context.read("action", String.class);
        Boolean merged = context.read("pull_request.merged", boolean.class);
        return merged.equals(true) && action.equals("labeled") || action.equals("closed");
    }

    private Integer getPullRequestNumber(DocumentContext context) {
        return context.read("number", int.class);
    }
}
