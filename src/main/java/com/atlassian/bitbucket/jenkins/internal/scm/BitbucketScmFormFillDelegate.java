package com.atlassian.bitbucket.jenkins.internal.scm;

import com.atlassian.bitbucket.jenkins.internal.client.BitbucketClientFactoryProvider;
import com.atlassian.bitbucket.jenkins.internal.client.exception.BitbucketClientException;
import com.atlassian.bitbucket.jenkins.internal.config.BitbucketPluginConfiguration;
import com.atlassian.bitbucket.jenkins.internal.credentials.BitbucketCredentials;
import com.atlassian.bitbucket.jenkins.internal.credentials.CredentialUtils;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketProject;
import com.atlassian.bitbucket.jenkins.internal.model.BitbucketRepository;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.HttpResponse;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collection;
import java.util.logging.Logger;

import static com.atlassian.bitbucket.jenkins.internal.client.BitbucketSearchHelper.findProjects;
import static com.atlassian.bitbucket.jenkins.internal.client.BitbucketSearchHelper.findRepositories;
import static com.atlassian.bitbucket.jenkins.internal.credentials.BitbucketCredentialsAdaptor.createWithFallback;
import static hudson.security.Permission.CONFIGURE;
import static hudson.util.HttpResponses.okJSON;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.stripToEmpty;
import static org.kohsuke.stapler.HttpResponses.errorWithoutStack;

@Singleton
public class BitbucketScmFormFillDelegate implements BitbucketScmFormFill {

    private static final Logger LOGGER = Logger.getLogger(BitbucketScmFormFillDelegate.class.getName());

    private final BitbucketClientFactoryProvider bitbucketClientFactoryProvider;
    private final BitbucketPluginConfiguration bitbucketPluginConfiguration;

    @Inject
    public BitbucketScmFormFillDelegate(BitbucketClientFactoryProvider bitbucketClientFactoryProvider, BitbucketPluginConfiguration bitbucketPluginConfiguration) {
        this.bitbucketClientFactoryProvider = requireNonNull(bitbucketClientFactoryProvider, "bitbucketClientFactoryProvider");
        this.bitbucketPluginConfiguration = requireNonNull(bitbucketPluginConfiguration, "bitbucketPluginConfiguration");
    }

    @Override
    public ListBoxModel doFillCredentialsIdItems(String baseUrl, String credentialsId) {
        Jenkins instance = Jenkins.get();
        instance.checkPermission(CONFIGURE);
        if (!instance.hasPermission(Jenkins.ADMINISTER)) {
            return new StandardListBoxModel().includeCurrentValue(credentialsId);
        }

        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        ACL.SYSTEM,
                        instance,
                        StringCredentials.class,
                        URIRequirementBuilder.fromUri(baseUrl).build(),
                        CredentialsMatchers.always())
                .includeMatchingAs(
                        ACL.SYSTEM,
                        instance,
                        StandardUsernamePasswordCredentials.class,
                        URIRequirementBuilder.fromUri(baseUrl).build(),
                        CredentialsMatchers.always());
    }

    @Override
    public HttpResponse doFillProjectNameItems(String serverId, String credentialsId, String projectName) {
        Jenkins.get().checkPermission(CONFIGURE);
        if (isBlank(serverId)) {
            return errorWithoutStack(HTTP_BAD_REQUEST, "A Bitbucket Server serverId must be provided");
        }
        if (stripToEmpty(projectName).length() < 2) {
            return errorWithoutStack(HTTP_BAD_REQUEST, "The project name must be at least 2 characters long");
        }

        Credentials providedCredentials = CredentialUtils.getCredentials(credentialsId);
        if (!isBlank(credentialsId) && providedCredentials == null) {
            return errorWithoutStack(HTTP_BAD_REQUEST, "No credentials exist for the provided credentialsId");
        }

        return bitbucketPluginConfiguration.getServerById(serverId)
                .map(serverConf -> {
                    try {
                        BitbucketCredentials credentials = createWithFallback(providedCredentials, serverConf);
                        Collection<BitbucketProject> projects = findProjects(projectName,
                                bitbucketClientFactoryProvider.getClient(serverConf.getBaseUrl(), credentials));
                        return okJSON(JSONArray.fromObject(projects));
                    } catch (BitbucketClientException e) {
                        // Something went wrong with the request to Bitbucket
                        LOGGER.info(e.getMessage());
                        return errorWithoutStack(HTTP_INTERNAL_ERROR, "An error occurred in Bitbucket: " + e.getMessage());
                    }
                }).orElseGet(() -> errorWithoutStack(HTTP_BAD_REQUEST, "The provided Bitbucket Server serverId does not exist"));
    }

    @Override
    public HttpResponse doFillRepositoryNameItems(String serverId, String credentialsId, String projectName, String repositoryName) {
        Jenkins.get().checkPermission(CONFIGURE);
        if (isBlank(serverId)) {
            return errorWithoutStack(HTTP_BAD_REQUEST, "A Bitbucket Server serverId must be provided");
        }
        if (stripToEmpty(repositoryName).length() < 2) {
            return errorWithoutStack(HTTP_BAD_REQUEST, "The repository name must be at least 2 characters long");
        }
        if (isBlank(projectName)) {
            return errorWithoutStack(HTTP_BAD_REQUEST, "The projectName must be present");
        }

        Credentials providedCredentials = CredentialUtils.getCredentials(credentialsId);
        if (!isBlank(credentialsId) && providedCredentials == null) {
            return errorWithoutStack(HTTP_BAD_REQUEST, "No credentials exist for the provided credentialsId");
        }

        return bitbucketPluginConfiguration.getServerById(serverId)
                .map(serverConf -> {
                    BitbucketCredentials credentials = createWithFallback(providedCredentials, serverConf);
                    try {
                        Collection<BitbucketRepository> repositories = findRepositories(repositoryName, projectName,
                                bitbucketClientFactoryProvider.getClient(serverConf.getBaseUrl(), credentials));
                        return okJSON(JSONArray.fromObject(repositories));
                    } catch (BitbucketClientException e) {
                        // Something went wrong with the request to Bitbucket
                        LOGGER.info(e.getMessage());
                        return errorWithoutStack(HTTP_INTERNAL_ERROR, "An error occurred in Bitbucket: " + e.getMessage());
                    }
                }).orElseGet(() -> errorWithoutStack(HTTP_BAD_REQUEST, "The provided Bitbucket Server serverId does not exist"));
    }

    @Override
    public ListBoxModel doFillServerIdItems(String serverId) {
        Jenkins.get().checkPermission(CONFIGURE);
        //Filtered to only include valid server configurations
        StandardListBoxModel model =
                bitbucketPluginConfiguration.getServerList()
                        .stream()
                        .filter(server -> server.getId().equals(serverId) ||
                                          server.validate().kind == FormValidation.Kind.OK)
                        .map(server ->
                                new ListBoxModel.Option(
                                        server.getServerName(),
                                        server.getId(),
                                        server.getId().equals(serverId)))
                        .collect(toCollection(StandardListBoxModel::new));
        if (model.isEmpty() || model.stream().noneMatch(server -> server.value.equals(serverId))) {
            model.includeEmptyValue();
        }
        return model;
    }
}
