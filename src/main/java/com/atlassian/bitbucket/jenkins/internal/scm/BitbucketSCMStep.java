package com.atlassian.bitbucket.jenkins.internal.scm;

import com.atlassian.bitbucket.jenkins.internal.client.BitbucketClientFactoryProvider;
import com.atlassian.bitbucket.jenkins.internal.config.BitbucketPluginConfiguration;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketNamedLink;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketRepository;
import hudson.Extension;
import hudson.plugins.git.BranchSpec;
import hudson.scm.SCM;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.scm.SCMStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static java.lang.Math.max;
import static org.apache.commons.lang3.StringUtils.isBlank;

public class BitbucketSCMStep extends SCMStep {

    private static final Logger LOGGER = Logger.getLogger(BitbucketSCMStep.class.getName());

    private final List<BranchSpec> branches;
    private final String cloneUrl;
    private final String credentialsId;
    private final String id;
    private final String projectKey;
    private final String projectName;
    private final String repositoryName;
    private final String repositorySlug;
    private final String repositoryUrl;
    private final String serverId;

    @DataBoundConstructor
    public BitbucketSCMStep(
            String id,
            List<BranchSpec> branches,
            String credentialsId,
            String projectName,
            String repositoryName,
            String serverId) {
        this.id = isBlank(id) ? UUID.randomUUID().toString() : id;
        this.branches = branches;
        this.credentialsId = credentialsId;
        this.projectName = projectName;
        this.repositoryName = repositoryName;
        this.serverId = serverId;
        Optional<BitbucketScmHelper> maybeScmHelper = ((DescriptorImpl) getDescriptor()).getBitbucketScmHelper(serverId, credentialsId);
        if (!maybeScmHelper.isPresent()) {
            LOGGER.info("Error creating the Bitbucket SCM: No Bitbucket Server configuration for serverId " + serverId);
            projectKey = "";
            repositorySlug = "";
            repositoryUrl = "";
            cloneUrl = "";
            return;
        }
        BitbucketScmHelper scmHelper = maybeScmHelper.get();
        if (isBlank(projectName)) {
            LOGGER.info("Error creating the Bitbucket SCM: The project name is blank");
            projectKey = "";
            repositorySlug = "";
            repositoryUrl = "";
            cloneUrl = "";
            return;
        }
        if (isBlank(repositoryName)) {
            LOGGER.info("Error creating the Bitbucket SCM: The repository name is blank");
            projectKey = "";
            repositorySlug = "";
            repositoryUrl = "";
            cloneUrl = "";
            return;
        }
        BitbucketRepository repository = scmHelper.getRepository(projectName, repositoryName);
        projectKey = repository.getProject().getKey();
        repositorySlug = repository.getSlug();
        cloneUrl = repository.getCloneUrls()
                .stream()
                .filter(link -> "http".equals(link.getName()))
                .findFirst()
                .map(BitbucketNamedLink::getHref)
                .orElse("");
        String selfLink = repository.getSelfLink();
        repositoryUrl = selfLink.substring(0, max(selfLink.indexOf("/browse"), 0)); // self-link include /browse which needs to be trimmed
    }

    public List<BranchSpec> getBranches() {
        return branches;
    }

    public String getCloneUrl() {
        return cloneUrl;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getId() {
        return id;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public String getRepositorySlug() {
        return repositorySlug;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public String getServerId() {
        return serverId;
    }

    @Nonnull
    @Override
    protected SCM createSCM() {
        return new BitbucketSCM(id, branches, cloneUrl, credentialsId, null, null, projectName, projectKey,
                repositoryName, repositorySlug, repositoryUrl, serverId);
    }

    @Symbol("BitbucketSCMStep")
    @Extension
    public static final class DescriptorImpl extends SCMStepDescriptor implements BitbucketScmFormValidation, BitbucketScmFormFill {

        @Inject
        private BitbucketClientFactoryProvider bitbucketClientFactoryProvider;
        @Inject
        private BitbucketPluginConfiguration bitbucketPluginConfiguration;
        @Inject
        private BitbucketScmFormFillDelegate formFill;
        @Inject
        private BitbucketScmFormValidationDelegate formValidation;

        @Override
        @POST
        public FormValidation doCheckCredentialsId(@QueryParameter String credentialsId) {
            return formValidation.doCheckCredentialsId(credentialsId);
        }

        @Override
        @POST
        public FormValidation doCheckProjectName(@QueryParameter String serverId, @QueryParameter String credentialsId,
                                                 @QueryParameter String projectName) {
            return formValidation.doCheckProjectName(serverId, credentialsId, projectName);
        }

        @Override
        @POST
        public FormValidation doCheckRepositoryName(@QueryParameter String serverId,
                                                    @QueryParameter String credentialsId,
                                                    @QueryParameter String projectName,
                                                    @QueryParameter String repositoryName) {
            return formValidation.doCheckRepositoryName(serverId, credentialsId, projectName, repositoryName);
        }

        @Override
        @POST
        public FormValidation doCheckServerId(@QueryParameter String serverId) {
            return formValidation.doCheckServerId(serverId);
        }

        @Override
        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String baseUrl,
                                                     @QueryParameter String credentialsId) {
            return formFill.doFillCredentialsIdItems(baseUrl, credentialsId);
        }

        @Override
        @POST
        public HttpResponse doFillProjectNameItems(@QueryParameter String serverId,
                                                   @QueryParameter String credentialsId,
                                                   @QueryParameter String projectName) {
            return formFill.doFillProjectNameItems(serverId, credentialsId, projectName);
        }

        @Override
        @POST
        public HttpResponse doFillRepositoryNameItems(@QueryParameter String serverId,
                                                      @QueryParameter String credentialsId,
                                                      @QueryParameter String projectName,
                                                      @QueryParameter String repositoryName) {
            return formFill.doFillRepositoryNameItems(serverId, credentialsId, projectName, repositoryName);
        }

        @Override
        @POST
        public ListBoxModel doFillServerIdItems(@QueryParameter String serverId) {
            return formFill.doFillServerIdItems(serverId);
        }

        public Optional<BitbucketScmHelper> getBitbucketScmHelper(@Nullable String serverId, @Nullable String credentialsId) {
            return bitbucketPluginConfiguration.getServerById(serverId)
                    .map(serverConf -> new BitbucketScmHelper(bitbucketClientFactoryProvider, serverConf, credentialsId));
        }

        @Override
        public String getFunctionName() {
            return "bbs";
        }
    }
}
