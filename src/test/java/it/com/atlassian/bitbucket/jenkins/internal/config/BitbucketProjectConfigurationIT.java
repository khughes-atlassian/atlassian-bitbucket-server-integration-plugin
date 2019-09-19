package it.com.atlassian.bitbucket.jenkins.internal.config;

import com.atlassian.bitbucket.jenkins.internal.client.BitbucketClientFactoryProvider;
import com.atlassian.bitbucket.jenkins.internal.config.BitbucketPluginConfiguration;
import com.atlassian.bitbucket.jenkins.internal.fixture.BitbucketJenkinsRule;
import com.atlassian.bitbucket.jenkins.internal.http.HttpRequestExecutorImpl;
import com.atlassian.bitbucket.jenkins.internal.scm.BitbucketSCM;
import com.atlassian.bitbucket.jenkins.internal.scm.BitbucketSCMRepository;
import com.gargoylesoftware.htmlunit.html.*;
import hudson.model.FreeStyleProject;
import hudson.plugins.git.BranchSpec;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static it.com.atlassian.bitbucket.jenkins.internal.util.HtmlUnitUtils.getDivByText;
import static it.com.atlassian.bitbucket.jenkins.internal.util.HtmlUnitUtils.waitTillItemIsRendered;
import static java.util.Collections.emptyList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BitbucketProjectConfigurationIT {

    private static final String PROJECT_KEY = "PROJECT_1";
    private static final String PROJECT_NAME = "Project 1";
    private static final String REPO_NAME = "rep_1";
    private static final String REPO_SLUG = "rep_1";
    private static final String JENKINS_PROJECT_NAME = "bitbucket";

    @ClassRule
    public static final BitbucketJenkinsRule bbJenkinsRule = new BitbucketJenkinsRule();

    private FreeStyleProject project;

    @Before
    public void setup() throws IOException {
        project = bbJenkinsRule.createFreeStyleProject(JENKINS_PROJECT_NAME);
    }

    @After
    public void tearDown() throws IOException, InterruptedException {
        project.delete();
    }

    @Test
    public void testCreateBitbucketProject() throws Exception {
        JenkinsRule.WebClient webClient = bbJenkinsRule.createWebClient();
        HtmlPage configurePage = webClient.goTo("job/" + JENKINS_PROJECT_NAME + "/configure");
        HtmlForm form = configurePage.getFormByName("config");
        List<HtmlRadioButtonInput> scms = form.getRadioButtonsByName("scm");
        Optional<HtmlRadioButtonInput> bitbucketSCMRadioButton = scms.stream()
                .filter(scm -> scm.getParentNode().getTextContent().contains("Bitbucket"))
                .findFirst();

        //Configure Bitbucket SCM
        assertTrue(bitbucketSCMRadioButton.isPresent());
        bitbucketSCMRadioButton.get().click();
        webClient.waitForBackgroundJavaScript(2000);

        HtmlSelect credential = form.getSelectByName("_.credentialsId");
        waitTillItemIsRendered(credential::getOptions);
        Optional<HtmlOption> configuredCredential = credential.getOptions().stream()
                .filter(option -> option.getValueAttribute().equals(bbJenkinsRule.getBitbucketServerConfiguration().getCredentialsId()))
                .findFirst();
        assertTrue("Credentials should be configured", configuredCredential.isPresent());
        configuredCredential.get().click();

        HtmlSelect serverId = form.getSelectByName("_.serverId");
        waitTillItemIsRendered(serverId::getOptions);
        Optional<HtmlOption> configuredServer = serverId.getOptions().stream()
                .filter(option -> option.getValueAttribute().equals(bbJenkinsRule.getBitbucketServerConfiguration().getId()))
                .findFirst();
        assertTrue("Bitbucket server should be configured", configuredServer.isPresent());
        configuredServer.get().click();

        // It would be better to actually type the value in the project/repo name inputs, do the search and select the
        // corresponding result to check that the search works. But I haven't put in the time to figure out how to do it
        form.getInputByName("_.projectName").setValueAttribute(PROJECT_NAME);
        form.getInputByName("_.repositoryName").setValueAttribute(REPO_NAME);

        HtmlPage submit = bbJenkinsRule.submit(form);
        assertNotNull(submit);

        project.doReload();

        //verify Bitbucket SCM settings are persisted
        assertTrue(project.getScm() instanceof BitbucketSCM);
        BitbucketSCM bitbucketSCM = (BitbucketSCM) project.getScm();
        assertEquals(bbJenkinsRule.getBitbucketServerConfiguration().getCredentialsId(), bitbucketSCM.getCredentialsId());
        assertEquals(bbJenkinsRule.getBitbucketServerConfiguration().getId(), bitbucketSCM.getServerId());
        assertEquals(PROJECT_KEY, bitbucketSCM.getProjectKey());
        assertEquals(REPO_SLUG, bitbucketSCM.getRepositorySlug());
        assertEquals(1, bitbucketSCM.getBranches().size());
        BranchSpec branchSpec = bitbucketSCM.getBranches().get(0);
        assertEquals("*/master", branchSpec.getName());
    }

    @Test
    public void testBitbucketSCMFieldsShouldBePopulatedWithProperValues() throws IOException, SAXException {
        setupBitbucketSCM();

        JenkinsRule.WebClient webClient = bbJenkinsRule.createWebClient();
        HtmlPage configurePage = webClient.goTo("job/" + JENKINS_PROJECT_NAME + "/configure");
        HtmlForm form = configurePage.getFormByName("config");

        HtmlSelect credential = form.getSelectByName("_.credentialsId");
        waitTillItemIsRendered(credential::getOptions);
        assertEquals(bbJenkinsRule.getBitbucketServerConfiguration().getCredentialsId(), credential.getSelectedOptions().get(0).getValueAttribute());

        HtmlSelect serverId = form.getSelectByName("_.serverId");
        waitTillItemIsRendered(serverId::getOptions);
        assertEquals(bbJenkinsRule.getBitbucketServerConfiguration().getId(), serverId.getSelectedOptions().get(0).getValueAttribute());

        assertEquals(PROJECT_NAME, form.getInputByName("_.projectName").getValueAttribute());
        assertEquals(REPO_NAME, form.getInputByName("_.repositoryName").getValueAttribute());
    }

    @Test
    public void testProjectEmpty() throws IOException, SAXException {
        setupBitbucketSCM();

        JenkinsRule.WebClient webClient = bbJenkinsRule.createWebClient();
        HtmlPage configurePage = webClient.goTo("job/" + JENKINS_PROJECT_NAME + "/configure");
        HtmlForm form = configurePage.getFormByName("config");

        HtmlInput projectNameInput = form.getInputByName("_.projectName");
        projectNameInput.click();
        projectNameInput.setValueAttribute("");
        form.click();
        webClient.waitForBackgroundJavaScript(2000);
        assertNotNull(getDivByText(form, "Please specify a project name."));
    }

    @Test
    public void testProjectNotExist() throws IOException, SAXException {
        setupBitbucketSCM();

        JenkinsRule.WebClient webClient = bbJenkinsRule.createWebClient();
        HtmlPage configurePage = webClient.goTo("job/" + JENKINS_PROJECT_NAME + "/configure");
        HtmlForm form = configurePage.getFormByName("config");

        HtmlInput projectNameInput = form.getInputByName("_.projectName");
        projectNameInput.click();
        projectNameInput.setValueAttribute("non-existent-project");
        form.click();
        webClient.waitForBackgroundJavaScript(2000);
        assertNotNull(getDivByText(form, "The project 'non-existent-project' does not exist or you do not have permission to access it."));
    }

    @Test
    public void testRepositoryEmpty() throws Exception {
        setupBitbucketSCM();

        JenkinsRule.WebClient webClient = bbJenkinsRule.createWebClient();
        HtmlPage configurePage = webClient.goTo("job/" + JENKINS_PROJECT_NAME + "/configure");
        HtmlForm form = configurePage.getFormByName("config");
        HtmlInput projectNameInput = form.getInputByName("_.projectName");
        projectNameInput.click();
        projectNameInput.setValueAttribute(PROJECT_NAME);
        form.click();

        HtmlInput repoNameInput = form.getInputByName("_.repositoryName");
        repoNameInput.click();
        repoNameInput.setValueAttribute("");
        form.click();
        webClient.waitForBackgroundJavaScript(2000);

        assertNotNull(getDivByText(form, "Please specify a repository name."));
    }

    @Test
    public void testRepositoryNotExist() throws Exception {
        setupBitbucketSCM();

        JenkinsRule.WebClient webClient = bbJenkinsRule.createWebClient();
        HtmlPage configurePage = webClient.goTo("job/" + JENKINS_PROJECT_NAME + "/configure");
        HtmlForm form = configurePage.getFormByName("config");
        HtmlInput projectNameInput = form.getInputByName("_.projectName");
        projectNameInput.click();
        projectNameInput.setValueAttribute(PROJECT_NAME);
        form.click();

        HtmlInput repoNameInput = form.getInputByName("_.repositoryName");
        repoNameInput.click();
        repoNameInput.setValueAttribute("non-existent-repo");
        form.click();
        webClient.waitForBackgroundJavaScript(2000);

        assertNotNull(getDivByText(form, "The repository 'non-existent-repo' does not exist or you do not have permission to access it."));
    }

    private void setupBitbucketSCM() throws IOException {
        String serverId = bbJenkinsRule.getBitbucketServerConfiguration().getId();
        BitbucketSCM bitbucketSCM = new BitbucketSCM(
                "",
                Collections.singletonList(new BranchSpec("*/master")),
                bbJenkinsRule.getBitbucketServerConfiguration().getCredentialsId(),
                emptyList(),
                "",
                PROJECT_NAME,
                REPO_NAME,
                serverId);
        bitbucketSCM.setBitbucketClientFactoryProvider(new BitbucketClientFactoryProvider(new HttpRequestExecutorImpl()));
        bitbucketSCM.setBitbucketPluginConfiguration(new BitbucketPluginConfiguration());
        bitbucketSCM.addRepositories(new BitbucketSCMRepository(bbJenkinsRule.getBitbucketServerConfiguration().getCredentialsId(),
                PROJECT_NAME, PROJECT_KEY, REPO_NAME, REPO_SLUG, serverId, false));
        bitbucketSCM.createGitSCM();
        project.setScm(bitbucketSCM);
        project.save();
    }
}