///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.5.0
//DEPS org.eclipse.jgit:org.eclipse.jgit:5.10.0.202012080955-r
//DEPS org.eclipse.jgit:org.eclipse.jgit.gpg.bc:5.10.0.202012080955-r
//DEPS org.kohsuke:github-api:1.122
//DEPS commons-io:commons-io:2.8.0
//DEPS org.slf4j:slf4j-jdk14:1.7.30

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import static java.util.stream.Collectors.toList;
import static org.eclipse.jgit.api.CherryPickResult.CherryPickStatus.OK;
import static org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM;
import static org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE;

@Command(name = "backport", mixinStandardHelpOptions = true, version = "backport 0.1")
class backport implements Callable<Integer> {
    public static final Logger log = Logger.getLogger(backport.class.getName());

    @Parameters(index = "0")
    private String token;
    @Parameters(index = "1")
    private String repository;
    @Parameters(index = "2")
    private Integer pullRequestNumber;

    private GitHub gitHub;
    private BackportContext context;

    public static void main(String... args) {
        int exitCode = new CommandLine(new backport()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        gitHub = new GitHubBuilder().withJwtToken(token).build();
        context = new BackportContext(gitHub, repository, pullRequestNumber);

        // Find the backports labels
        List<String> backportBranches = context.findBackportBranches();
        if (backportBranches.isEmpty()) {
            log.info("No backport labels found");
            return 0;
        }

        // Check if PR is merged
        if (!context.getPullRequest().isMerged()) {
            log.info("The PR #" + pullRequestNumber + " is not merged, no backport will be performed");
            return 0;
        }

        log.info("Backporting #" + pullRequestNumber + " to " + String.join(", ", backportBranches));

        Path repoPath = Paths.get(context.getRepository().getName());
        if (Files.exists(repoPath) && Files.isDirectory(repoPath)) {
            FileUtils.deleteDirectory(repoPath.toFile());
        }

        Git git = Git.cloneRepository()
                     .setURI(context.getRepository().getHttpTransportUrl())
                     .call();

        List<GHPullRequest> backportPullRequests = new ArrayList<>();
        for (String branch : backportBranches) {
            // The branch to create with the backport
            String head = "backport-#" + pullRequestNumber + "-to-" + branch;

            // Verify if a branch already exits in remote
            List<Ref> remoteBranches = git.branchList().setListMode(REMOTE).call();
            if (remoteBranches.stream().map(Ref::getName).anyMatch(name -> name.endsWith(head))) {
                log.info("A backport branch " + head + " already exists in origin");
                continue;
            }

            // Add PR Ref
            RefSpec branchRefSpec = new RefSpec(
                "+refs/pull/" + pullRequestNumber.toString() + "/head:" +
                "refs/remotes/origin/pr/" + pullRequestNumber.toString());
            StoredConfig config = git.getRepository().getConfig();
            RemoteConfig remoteConfig = new RemoteConfig(config, "origin");
            remoteConfig.addFetchRefSpec(branchRefSpec);
            remoteConfig.update(config);
            config.save();

            // Fetch PR
            git.fetch()
               .setRemote("origin")
               .setRefSpecs(branchRefSpec)
               .call();

            // Checkout the branch to backport
            log.info("Checkout branch to backport origin/" + branch);
            git.checkout()
               .setCreateBranch(true)
               .setName(branch)
               .setUpstreamMode(SET_UPSTREAM)
               .setStartPoint("origin/" + branch)
               .call();

            // Create a new branch to cherry pick
            log.info("Creating local branch to apply backport commits " + head);
            git.checkout()
               .setCreateBranch(true)
               .setName(head)
               .call();

            // Add backport commits
            log.info("Backporting " + branch + " to " + head);
            List<String> commits = context.getCommits();
            if (commits.isEmpty()) {
                log.info("No commits found to backport");
            }

            // Cherry Pick
            boolean isChanged = false;
            CherryPickResult cherryPickResult = null;
            for (String commit : commits) {
                ObjectId objectId = git.getRepository().resolve(commit);
                log.info("Applying commit " + commit);
                cherryPickResult = git.cherryPick().include(objectId).setMainlineParentNumber(1).call();

                if (!cherryPickResult.getStatus().equals(OK)) {
                    log.info("Could not apply commit " + commit + " due to a conflict");
                    break;
                }

                if (cherryPickResult.getCherryPickedRefs().isEmpty()) {
                    log.info("Commit " + commit + " already applied");
                } else {
                    isChanged = true;
                }
            }

            // Handle Cherry Pick failure
            if (cherryPickResult != null && !cherryPickResult.getStatus().equals(OK)) {
                String backportFailureMessage = "Cannot backport to " + branch + " due to merge conflicts. " +
                                 "Please backport manually:\n" +
                                 getManualInstructions(commits, head, branch, context.pullRequest.getTitle());

                context.getPullRequest()
                       .comment(backportFailureMessage);

                log.info("Add cannot backport comment:\n" + backportFailureMessage);

                continue;
            }

            if (!isChanged) {
                log.info("All commits are already present in " + branch);
                continue;
            }

            // Push
            git.push()
               .setAtomic(true)
               .setRemote("origin")
               .setCredentialsProvider(new UsernamePasswordCredentialsProvider("token", token))
               .call();

            // Create PR
            GHPullRequest backportPullRequest =
                context.getRepository()
                       .createPullRequest("[" + branch + "] Backport " + context.pullRequest.getTitle(), head, branch,
                                          "Backport #" + pullRequestNumber + " to " + branch + ".", true, false);
            log.info("Created Pull Request " + backportPullRequest.getHtmlUrl());

            backportPullRequests.add(backportPullRequest);
        }

        // Add comment with created backports (if any)
        if (!backportPullRequests.isEmpty()) {
            StringBuilder backportComment = new StringBuilder();
            backportComment.append("Created Backports: ")
                           .append("\n");
            for (GHPullRequest backportPullRequest : backportPullRequests) {
                backportComment.append("- #").append(backportPullRequest.getNumber())
                               .append(" to ").append("[").append(backportPullRequest.getHead().getRef()).append("]")
                               .append("(")
                               .append(context.getRepository().getHtmlUrl()).append("/tree/")
                               .append(backportPullRequest.getBase().getRef())
                               .append(")")
                               .append("\n");
            }
            context.getPullRequest().comment(backportComment.toString());
        }

        return 0;
    }

    private String getManualInstructions(List<String> commits, String head, String branch, String title) {
        StringBuffer message = new StringBuffer()
            .append("Run:\n```\n")
            .append("git clone ").append(context.getRepository().getHttpTransportUrl())
            .append("\n")
            .append("git fetch origin pull/").append(pullRequestNumber).append("/head:pr-").append(pullRequestNumber)
            .append("\n")
            .append("git checkout -b ").append(branch).append(" ").append("origin/").append(branch)
            .append("\n")
            .append("git checkout -b ").append(head)
            .append("\n")
            .append("# One or more of the following command will fail, you will need to fix the conflict manually\n");

        commits.forEach(commit -> message.append("git cherry-pick ").append(commit).append("\n"));

        message.append("# Once all commits have been cherry-picked:\n")
               .append("git push --set-upstream origin ")
               .append(head)
               .append("\n")
               .append("```")

               .append("\n")
               .append("To fix the conflict, first check which file is impacted using: `git status`\n")
               .append("For each file with a resolved conflict, execute: `git add $FILE`\n")
               .append("Then, commit the files using the same commit message as the original commit: `git commit -m \"...\"`\n")

               .append("\n")
               .append("Once done and pushed, open the pull request.\n\n")
               .append("* Title: [").append(branch).append("] Backport ").append(title)
               .append("\n")
               .append("* Message: ")
               .append("Backport #").append(pullRequestNumber).append(" to ").append(branch).append(".\n")
               .append("* ⚡ **Set the target branch to ")
               .append(branch)
               .append("** \n")
               .append("* Set the milestone and the labels if needed\n");
        return message.toString();
    }

    private static class BackportContext {
        final GHRepository repository;
        final GHPullRequest pullRequest;

        BackportContext(GitHub gitHub, String repository, Integer pullRequestNumber) throws Exception {
            this.repository = gitHub.getRepository(repository);
            this.pullRequest = this.repository.getPullRequest(pullRequestNumber);
        }

        GHRepository getRepository() {
            return repository;
        }

        GHPullRequest getPullRequest() {
            return pullRequest;
        }

        List<String> getCommits() throws Exception {
            return pullRequest.listCommits().toList().stream().map(GHPullRequestCommitDetail::getSha).collect(toList());
        }

        List<String> findBackportBranches() {
            Collection<GHLabel> labels = pullRequest.getLabels();

            List<String> backportBranches = new ArrayList<>();
            for (GHLabel label : labels) {
                if (label.getName().startsWith("backport-")) {
                    backportBranches.add(label.getName().substring("backport-".length()));
                }
            }
            return backportBranches;
        }
    }
}
