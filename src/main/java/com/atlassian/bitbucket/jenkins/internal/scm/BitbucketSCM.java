package com.atlassian.bitbucket.jenkins.internal.scm;

import com.atlassian.bitbucket.jenkins.internal.client.BitbucketClientFactory;
import com.atlassian.bitbucket.jenkins.internal.client.BitbucketClientFactoryProvider;
import com.atlassian.bitbucket.jenkins.internal.client.exception.BitbucketClientException;
import com.atlassian.bitbucket.jenkins.internal.client.exception.NotFoundException;
import com.atlassian.bitbucket.jenkins.internal.config.BitbucketPluginConfiguration;
import com.atlassian.bitbucket.jenkins.internal.config.BitbucketServerConfiguration;
import com.atlassian.bitbucket.jenkins.internal.credentials.BitbucketCredentials;
import com.atlassian.bitbucket.jenkins.internal.credentials.CredentialUtils;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketNamedLink;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketProject;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketRepository;
import com.atlassian.bitbucket.jenkins.internal.model.RepositoryState;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitTool;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.browser.Stash;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.scm.*;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static com.atlassian.bitbucket.jenkins.internal.client.BitbucketSearchHelper.getProjectByNameOrKey;
import static com.atlassian.bitbucket.jenkins.internal.client.BitbucketSearchHelper.getRepositoryByNameOrSlug;
import static com.atlassian.bitbucket.jenkins.internal.credentials.BitbucketCredentialsAdaptor.createWithFallback;
import static hudson.security.Permission.CONFIGURE;
import static java.lang.Math.max;
import static org.apache.commons.lang3.StringUtils.firstNonBlank;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.stripToEmpty;

public class BitbucketSCM extends SCM {

    private static final Logger LOGGER = Logger.getLogger(BitbucketSCM.class.getName());

    // avoid a difficult upgrade task.
    private final List<BranchSpec> branches;
    private final List<GitSCMExtension> extensions;
    private final String gitTool;
    private final String id;
    // this is to enable us to support future multiple repositories
    private final List<BitbucketSCMRepository> repositories;

    private transient BitbucketClientFactoryProvider bitbucketClientFactoryProvider;
    private transient BitbucketPluginConfiguration bitbucketPluginConfiguration;
    private GitSCM gitSCM;

    @DataBoundConstructor
    public BitbucketSCM(
            String id,
            List<BranchSpec> branches,
            @CheckForNull String credentialsId,
            @CheckForNull List<GitSCMExtension> extensions,
            @CheckForNull String gitTool,
            @CheckForNull String projectName,
            @CheckForNull String repositoryName,
            @CheckForNull String serverId) {
        bitbucketClientFactoryProvider = ((DescriptorImpl) getDescriptor()).getBitbucketClientFactoryProvider();
        bitbucketPluginConfiguration = ((DescriptorImpl) getDescriptor()).getBitbucketPluginConfiguration();

        this.branches = branches;
        this.id = isBlank(id) ? UUID.randomUUID().toString() : id;
        this.extensions = new ArrayList<>();
        if (extensions != null) {
            this.extensions.addAll(extensions);
        }
        this.gitTool = gitTool;
        repositories = new ArrayList<>(1);

        if (isBlank(serverId)) {
            LOGGER.info("Error creating the Bitbucket SCM: the server ID must not be blank");
            return;
        }
        this.extensions.add(new BitbucketPostBuildStatus(serverId));
        Optional<BitbucketServerConfiguration> maybeServerConf = bitbucketPluginConfiguration.getServerById(serverId);
        if (!maybeServerConf.isPresent()) {
            LOGGER.info("Error creating the Bitbucket SCM: No server configuration for the given server id " + serverId);
            return;
        }
        BitbucketServerConfiguration serverConf = maybeServerConf.get();

        BitbucketCredentials credentials = createWithFallback(CredentialUtils.getCredentials(credentialsId), serverConf);
        BitbucketClientFactory clientFactory = bitbucketClientFactoryProvider.getClient(serverConf.getBaseUrl(), credentials);

        projectName = stripToEmpty(projectName);
        repositoryName = stripToEmpty(repositoryName);
        BitbucketProject project = getBitbucketProject(projectName, clientFactory);
        BitbucketRepository repository = getBitbucketRepository(projectName, repositoryName, clientFactory, project);

        addRepositories(new BitbucketSCMRepository(credentialsId, projectName, project.getKey(), repositoryName, repository.getSlug(), serverId, false));
        createGitSCM();
    }

    private BitbucketProject getBitbucketProject(String projectName, BitbucketClientFactory clientFactory) {
        BitbucketProject project;
        if (isBlank(projectName)) {
            LOGGER.info("Error creating the Bitbucket SCM: The projectName must not be blank");
            project = new BitbucketProject("", null, "");
        } else {
            try {
                project = getProjectByNameOrKey(projectName, clientFactory);
            } catch (NotFoundException e) {
                LOGGER.info("Error creating the Bitbucket SCM: Cannot find the project " + projectName);
                project = new BitbucketProject(projectName, null, projectName);
            } catch (BitbucketClientException e) {
                // Something went wrong with the request to Bitbucket
                LOGGER.info("Error creating the Bitbucket SCM: Something went wrong when trying to contact Bitbucket Server: " + e.getMessage());
                project = new BitbucketProject(projectName, null, projectName);
            }
        }
        return project;
    }

    public void createGitSCM() {
        BitbucketSCMRepository scmRepository = getBitbucketSCMRepository();
        BitbucketServerConfiguration server = getServer();
        String repositorySlug = scmRepository.getRepositorySlug();
        String credentialsId = scmRepository.getCredentialsId();
        BitbucketRepository repo;
        try {
            repo = getRepository(server, scmRepository.getProjectKey(), repositorySlug, credentialsId);
        } catch (BitbucketClientException e) {
            LOGGER.info("Error creating the Bitbucket SCM. Reason: " + firstNonBlank(e.getMessage(), "unknown"));
            repo = new BitbucketRepository(scmRepository.getRepositoryName(), null,
                    new BitbucketProject(scmRepository.getProjectKey(), null, scmRepository.getProjectName()),
                    scmRepository.getRepositorySlug(), RepositoryState.AVAILABLE);
        }
        UserRemoteConfig remoteConfig = getCloneUrl(repo)
                .map(cloneUrl -> new UserRemoteConfig(cloneUrl, repositorySlug, null, pickCredentialsId(server, credentialsId)))
                .orElseGet(() -> {
                    LOGGER.info("Error creating the Bitbucket SCM. Reason: No http clone url for repository " +
                                scmRepository.getProjectKey() + "/" + scmRepository.getRepositorySlug());
                    return new UserRemoteConfig("", repositorySlug, null, pickCredentialsId(server, credentialsId));
                });
        gitSCM = new GitSCM(Collections.singletonList(remoteConfig), branches, false, Collections.emptyList(),
                new Stash(getRepositoryUrl(repo)), gitTool, extensions);
    }

    public void addRepositories(BitbucketSCMRepository... repositories) {
        this.repositories.addAll(Arrays.asList(repositories));
    }

    @CheckForNull
    @Override
    public SCMRevisionState calcRevisionsFromBuild(
            Run<?, ?> build,
            @Nullable FilePath workspace,
            @Nullable Launcher launcher,
            TaskListener listener)
            throws IOException, InterruptedException {
        return gitSCM.calcRevisionsFromBuild(build, workspace, launcher, listener);
    }

    @Override
    public void checkout(
            Run<?, ?> build,
            Launcher launcher,
            FilePath workspace,
            TaskListener listener,
            @CheckForNull File changelogFile,
            @CheckForNull SCMRevisionState baseline)
            throws IOException, InterruptedException {
        gitSCM.checkout(build, launcher, workspace, listener, changelogFile, baseline);
    }

    @Override
    public PollingResult compareRemoteRevisionWith(
            Job<?, ?> project,
            @Nullable Launcher launcher,
            @Nullable FilePath workspace,
            TaskListener listener,
            SCMRevisionState baseline)
            throws IOException, InterruptedException {
        return gitSCM.compareRemoteRevisionWith(project, launcher, workspace, listener, baseline);
    }

    @Override
    public ChangeLogParser createChangeLogParser() {
        return gitSCM.createChangeLogParser();
    }

    private static Optional<String> getCloneUrl(BitbucketRepository repo) {
        return repo.getCloneUrls()
                .stream()
                .filter(link -> "http".equals(link.getName()))
                .findFirst()
                .map(BitbucketNamedLink::getHref);
    }

    public List<BranchSpec> getBranches() {
        return gitSCM.getBranches();
    }

    @CheckForNull
    @Override
    public RepositoryBrowser<?> getBrowser() {
        return gitSCM.getBrowser();
    }

    @CheckForNull
    public String getCredentialsId() {
        return getBitbucketSCMRepository().getCredentialsId();
    }

    public List<GitSCMExtension> getExtensions() {
        return gitSCM.getExtensions().stream().filter(extension -> extension.getClass() !=
                                                                   BitbucketPostBuildStatus.class).collect(Collectors.toList());
    }

    @CheckForNull
    public String getGitTool() {
        return gitTool;
    }

    public String getId() {
        return id;
    }

    public String getProjectKey() {
        return getBitbucketSCMRepository().getProjectKey();
    }

    public String getProjectName() {
        return getBitbucketSCMRepository().getProjectName();
    }

    public List<BitbucketSCMRepository> getRepositories() {
        return repositories;
    }

    public String getRepositorySlug() {
        return getBitbucketSCMRepository().getRepositorySlug();
    }

    public String getRepositoryName() {
        return getBitbucketSCMRepository().getRepositoryName();
    }

    @CheckForNull
    public String getServerId() {
        return getBitbucketSCMRepository().getServerId();
    }

    public void setBitbucketClientFactoryProvider(
            BitbucketClientFactoryProvider bitbucketClientFactoryProvider) {
        this.bitbucketClientFactoryProvider = bitbucketClientFactoryProvider;
    }

    public void setBitbucketPluginConfiguration(BitbucketPluginConfiguration bitbucketPluginConfiguration) {
        this.bitbucketPluginConfiguration = bitbucketPluginConfiguration;
    }

    private BitbucketRepository getBitbucketRepository(String projectName, String repositoryName, BitbucketClientFactory clientFactory, BitbucketProject project) {
        BitbucketRepository repository;
        if (isBlank(repositoryName)) {
            LOGGER.info("Error creating the Bitbucket SCM: The repositoryName must not be blank");
            repository = new BitbucketRepository("", null, project, "", RepositoryState.AVAILABLE);
        } else {
            try {
                repository = getRepositoryByNameOrSlug(projectName, repositoryName, clientFactory);
            } catch (NotFoundException e) {
                LOGGER.info("Error creating the Bitbucket SCM: Cannot find the repository " + projectName + "/" + repositoryName);
                repository = new BitbucketRepository(repositoryName, null, project, repositoryName, RepositoryState.AVAILABLE);
            } catch (BitbucketClientException e) {
                // Something went wrong with the request to Bitbucket
                LOGGER.info("Error creating the Bitbucket SCM: Something went wrong when trying to contact Bitbucket Server: " + e.getMessage());
                repository = new BitbucketRepository(repositoryName, null, project, repositoryName, RepositoryState.AVAILABLE);
            }
        }
        return repository;
    }

    private static String getRepositoryUrl(BitbucketRepository repository) {
        String selfLink = repository.getSelfLink(); // self-link include /browse which needs to be trimmed
        return selfLink.substring(0, max(selfLink.indexOf("/browse"), 0));
    }

    private BitbucketSCMRepository getBitbucketSCMRepository() {
        return repositories.get(0);
    }

    private BitbucketRepository getRepository(BitbucketServerConfiguration server, String projectKey,
                                              String repositorySlug, @Nullable String credentialsId) {
        return bitbucketClientFactoryProvider
                .getClient(server.getBaseUrl(), createWithFallback(credentialsId, server))
                .getProjectClient(projectKey)
                .getRepositoryClient(repositorySlug)
                .getRepository();
    }

    private BitbucketServerConfiguration getServer() {
        return bitbucketPluginConfiguration
                .getServerById(getBitbucketSCMRepository().getServerId())
                .orElseThrow(() -> new RuntimeException("Server config not found"));
    }

    @Nullable
    private String pickCredentialsId(BitbucketServerConfiguration serverConfiguration, @Nullable String credentialsId) {
        return credentialsId != null ? credentialsId : serverConfiguration.getCredentialsId();
    }

    @Symbol("BbS")
    @Extension
    @SuppressWarnings({"unused"})
    public static class DescriptorImpl extends SCMDescriptor<BitbucketSCM> implements BitbucketScmFormValidation,
            BitbucketScmFormFill {

        private final GitSCM.DescriptorImpl gitScmDescriptor;
        @Inject
        private BitbucketScmFormFillDelegate formFill;
        @Inject
        private BitbucketScmFormValidationDelegate formValidation;
        @Inject
        private BitbucketClientFactoryProvider bitbucketClientFactoryProvider;
        @Inject
        private BitbucketPluginConfiguration bitbucketPluginConfiguration;

        public DescriptorImpl() {
            super(Stash.class);
            gitScmDescriptor = new GitSCM.DescriptorImpl();
            load();
        }

        @Override
        public List<GitSCMExtensionDescriptor> getExtensionDescriptors() {
            return gitScmDescriptor.getExtensionDescriptors();
        }

        @Override
        public List<GitTool> getGitTools() {
            return gitScmDescriptor.getGitTools();
        }

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

        @POST
        public ListBoxModel doFillGitToolItems() {
            Jenkins.get().checkPermission(CONFIGURE);
            return gitScmDescriptor.doFillGitToolItems();
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

        @Override
        public boolean getShowGitToolOptions() {
            return gitScmDescriptor.showGitToolOptions();
        }

        BitbucketClientFactoryProvider getBitbucketClientFactoryProvider() {
            return bitbucketClientFactoryProvider;
        }

        BitbucketPluginConfiguration getBitbucketPluginConfiguration() {
            return bitbucketPluginConfiguration;
        }
    }
}
