package com.atlassian.bitbucket.jenkins.internal.scm;

import hudson.plugins.git.GitTool;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.util.ListBoxModel;
import org.kohsuke.stapler.HttpResponse;

import java.util.Collections;
import java.util.List;

public interface BitbucketScmFormFill {

    ListBoxModel doFillCredentialsIdItems(String baseUrl, String credentialsId);

    HttpResponse doFillProjectNameItems(String serverId, String credentialsId, String projectName);

    HttpResponse doFillRepositoryNameItems(String serverId,
                                           String credentialsId,
                                           String projectName,
                                           String repositoryName);

    ListBoxModel doFillServerIdItems(String serverId);

    default List<GitSCMExtensionDescriptor> getExtensionDescriptors() {
        return Collections.emptyList();
    }

    default List<GitTool> getGitTools() {
        return Collections.emptyList();
    }

    default boolean getShowGitToolOptions() {
        return false;
    }
}
