/*
 * SonarQube :: GitHub Plugin
 * Copyright (C) 2015-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.github;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.kohsuke.github.GHCommitState;
import org.sonar.api.batch.fs.InputComponent;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.postjob.PostJob;
import org.sonar.api.batch.postjob.PostJobContext;
import org.sonar.api.batch.postjob.PostJobDescriptor;
import org.sonar.api.batch.postjob.issue.PostJobIssue;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

/**
 * Compute comments to be added on the pull request.
 */
public class PullRequestIssuePostJob implements PostJob {
  private static final Logger LOG = Loggers.get(PullRequestFacade.class);

  private static final Comparator<PostJobIssue> ISSUE_COMPARATOR = new IssueComparator();

  private final PullRequestFacade pullRequestFacade;
  private final GitHubPluginConfiguration gitHubPluginConfiguration;
  private final MarkDownUtils markDownUtils;

  public PullRequestIssuePostJob(GitHubPluginConfiguration gitHubPluginConfiguration, PullRequestFacade pullRequestFacade, MarkDownUtils markDownUtils) {
    this.gitHubPluginConfiguration = gitHubPluginConfiguration;
    this.pullRequestFacade = pullRequestFacade;
    this.markDownUtils = markDownUtils;
  }

  @Override
  public void describe(PostJobDescriptor descriptor) {
    descriptor
      .name("GitHub Pull Request Issue Publisher")
      .requireProperty(GitHubPlugin.GITHUB_PULL_REQUEST);
  }

  @Override
  public void execute(PostJobContext context) {
    GlobalReport report = new GlobalReport(markDownUtils, gitHubPluginConfiguration.tryReportIssuesInline());
    try {
      Map<InputFile, Map<Integer, StringBuilder>> commentsToBeAddedByLine = processIssues(report, context.issues());

      updateReviewComments(commentsToBeAddedByLine);

      if (gitHubPluginConfiguration.isDeleteOldCommentsEnabled()) {
        pullRequestFacade.deleteOutdatedComments();
      }

      pullRequestFacade.createOrUpdateGlobalComments(report.hasNewIssue() ? report.formatForMarkdown() : null);

      pullRequestFacade.createOrUpdateSonarQubeStatus(report.getStatus(), report.getStatusDescription());
    } catch (Exception e) {
      String msg = "SonarQube failed to complete the review of this pull request";
      LOG.error(msg, e);
      pullRequestFacade.createOrUpdateSonarQubeStatus(GHCommitState.ERROR, msg + ": " + e.getMessage());
    }
  }

  private Map<InputFile, Map<Integer, StringBuilder>> processIssues(GlobalReport report, Iterable<PostJobIssue> issues) {
    Map<InputFile, Map<Integer, StringBuilder>> commentToBeAddedByFileAndByLine = new HashMap<>();

    StreamSupport.stream(issues.spliterator(), false)
      .filter(PostJobIssue::isNew)
      // SONARGITUB-13 Ignore issues on files not modified by the P/R
      .filter(i -> {
        InputComponent inputComponent = i.inputComponent();
        return inputComponent == null ||
          !inputComponent.isFile() ||
          pullRequestFacade.hasFile((InputFile) inputComponent);
      })
      .sorted(ISSUE_COMPARATOR)
      .forEach(i -> processIssue(report, commentToBeAddedByFileAndByLine, i));
    return commentToBeAddedByFileAndByLine;

  }

  private void processIssue(GlobalReport report, Map<InputFile, Map<Integer, StringBuilder>> commentToBeAddedByFileAndByLine, PostJobIssue issue) {
    boolean reportedInline = false;
    InputComponent inputComponent = issue.inputComponent();
    if (gitHubPluginConfiguration.tryReportIssuesInline() && inputComponent != null && inputComponent.isFile()) {
      reportedInline = tryReportInline(commentToBeAddedByFileAndByLine, issue, (InputFile) inputComponent);
    }
    report.process(issue, pullRequestFacade.getGithubUrl(inputComponent, issue.line()), reportedInline);
  }

  private boolean tryReportInline(Map<InputFile, Map<Integer, StringBuilder>> commentToBeAddedByFileAndByLine, PostJobIssue issue, InputFile inputFile) {
    Integer lineOrNull = issue.line();
    if (lineOrNull != null) {
      int line = lineOrNull.intValue();
      if (pullRequestFacade.hasFileLine(inputFile, line)) {
        String message = issue.message();
        String ruleKey = issue.ruleKey().toString();
        if (!commentToBeAddedByFileAndByLine.containsKey(inputFile)) {
          commentToBeAddedByFileAndByLine.put(inputFile, new HashMap<Integer, StringBuilder>());
        }
        Map<Integer, StringBuilder> commentsByLine = commentToBeAddedByFileAndByLine.get(inputFile);
        if (!commentsByLine.containsKey(line)) {
          commentsByLine.put(line, new StringBuilder());
        }
        commentsByLine.get(line).append(markDownUtils.inlineIssue(issue.severity(), message, ruleKey)).append("\n");
        return true;
      }
    }
    return false;
  }

  private void updateReviewComments(Map<InputFile, Map<Integer, StringBuilder>> commentsToBeAddedByLine) {
    for (Map.Entry<InputFile, Map<Integer, StringBuilder>> entry : commentsToBeAddedByLine.entrySet()) {
      for (Map.Entry<Integer, StringBuilder> entryPerLine : entry.getValue().entrySet()) {
        String body = entryPerLine.getValue().toString();
        pullRequestFacade.createOrUpdateReviewComment(entry.getKey(), entryPerLine.getKey(), body);
      }
    }
  }

}