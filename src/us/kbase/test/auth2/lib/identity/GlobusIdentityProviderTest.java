package us.kbase.test.auth2.lib.identity;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Parameter;
import org.mockserver.model.ParameterBody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import us.kbase.auth2.lib.exceptions.IdentityRetrievalException;
import us.kbase.auth2.lib.identity.GlobusIdentityProvider;
import us.kbase.auth2.lib.identity.GlobusIdentityProvider.GlobusIdentityProviderConfigurator;
import us.kbase.auth2.lib.identity.IdentityProvider;
import us.kbase.auth2.lib.identity.IdentityProviderConfig;
import us.kbase.auth2.lib.identity.IdentityProviderConfig.IdentityProviderConfigurationException;
import us.kbase.auth2.lib.identity.RemoteIdentity;
import us.kbase.auth2.lib.identity.RemoteIdentityDetails;
import us.kbase.auth2.lib.identity.RemoteIdentityID;
import us.kbase.test.auth2.TestCommon;

public class GlobusIdentityProviderTest {
	
	private static final String CONTENT_TYPE = "content-type";
	private static final String ACCEPT = "accept";
	private static final String APP_JSON = "application/json";
	private static final String GLOBUS = "Globus";
	
	private static final ObjectMapper MAPPER = new ObjectMapper();
	
	private static ClientAndServer mockClientAndServer;
	
	@BeforeClass
	public static void setUpClass() {
		mockClientAndServer = ClientAndServer.startClientAndServer(TestCommon.findFreePort());
	}
	
	@After
	public void tearDownTest() {
		mockClientAndServer.reset();
	}
	
	private static final IdentityProviderConfig CFG;
	static {
		try {
			CFG = new IdentityProviderConfig(
					"Globus",
					new URL("https://login.com"),
					new URL("https://setapiurl.com"),
					"foo",
					"bar",
					new URI("http://image.com"),
					new URL("https://loginredir.com"),
					new URL("https://linkredir.com"));
		} catch (IdentityProviderConfigurationException | URISyntaxException |
				MalformedURLException e) {
			throw new RuntimeException("Fix yer tests newb", e);
		}
	}

	@Test
	public void simpleOperationsWithConfigurator() throws Exception {
		final GlobusIdentityProviderConfigurator gc = new GlobusIdentityProviderConfigurator();
		assertThat("incorrect provider name", gc.getProviderName(), is("Globus"));
		
		final IdentityProvider gip = gc.configure(CFG);
		assertThat("incorrect provider name", gip.getProviderName(), is("Globus"));
		assertThat("incorrect image uri", gip.getImageURI(), is(new URI("http://image.com")));
		assertThat("incorrect login url", gip.getLoginURL("foo2", false),
				is(new URL("https://login.com/v2/oauth2/authorize?" +
						"scope=urn%3Aglobus%3Aauth%3Ascope%3Aauth.globus.org%3Aview_identities+" +
						"email&state=foo2&redirect_uri=https%3A%2F%2Floginredir.com" +
						"&response_type=code&client_id=foo")));
		assertThat("incorrect link url", gip.getLoginURL("foo3", true),
				is(new URL("https://login.com/v2/oauth2/authorize?" +
						"scope=urn%3Aglobus%3Aauth%3Ascope%3Aauth.globus.org%3Aview_identities+" +
						"email&state=foo3&redirect_uri=https%3A%2F%2Flinkredir.com" +
						"&response_type=code&client_id=foo")));
		
	}
	
	@Test
	public void simpleOperationsWithoutConfigurator() throws Exception {
		
		final IdentityProvider gip = new GlobusIdentityProvider(CFG);
		assertThat("incorrect provider name", gip.getProviderName(), is("Globus"));
		assertThat("incorrect image uri", gip.getImageURI(), is(new URI("http://image.com")));
		assertThat("incorrect login url", gip.getLoginURL("foo2", false),
				is(new URL("https://login.com/v2/oauth2/authorize?" +
						"scope=urn%3Aglobus%3Aauth%3Ascope%3Aauth.globus.org%3Aview_identities+" +
						"email&state=foo2&redirect_uri=https%3A%2F%2Floginredir.com" +
						"&response_type=code&client_id=foo")));
		assertThat("incorrect link url", gip.getLoginURL("foo3", true),
				is(new URL("https://login.com/v2/oauth2/authorize?" +
						"scope=urn%3Aglobus%3Aauth%3Ascope%3Aauth.globus.org%3Aview_identities+" +
						"email&state=foo3&redirect_uri=https%3A%2F%2Flinkredir.com" +
						"&response_type=code&client_id=foo")));
		
	}
	
	@Test
	public void createFail() throws Exception {
		failCreate(null, new NullPointerException("idc"));
		failCreate(new IdentityProviderConfig(
				"foo",
				CFG.getLoginURL(),
				CFG.getApiURL(),
				CFG.getClientID(),
				CFG.getClientSecret(),
				CFG.getImageURI(),
				CFG.getLoginRedirectURL(),
				CFG.getLinkRedirectURL()),
				new IllegalArgumentException("Bad config name: foo"));
	}
	
	private void failCreate(final IdentityProviderConfig cfg, final Exception exception) {
		try {
			new GlobusIdentityProvider(cfg);
			fail("created bad globus id provider");
		} catch (Exception e) {
			TestCommon.assertExceptionCorrect(e, exception);
		}
	}
	
	@Test
	public void illegalAuthcode() throws Exception {
		final IdentityProvider idp = new GlobusIdentityProvider(CFG);
		failGetIdentities(idp, null, true, new IllegalArgumentException(
				"authcode cannot be null or empty"));
		failGetIdentities(idp, "  \t  \n  ", true, new IllegalArgumentException(
				"authcode cannot be null or empty"));
	}
	
	@Test
	public void returnsIllegalAuthtoken() throws Exception {
		final IdentityProviderConfig testIDConfig = getTestIDConfig();
		final IdentityProvider idp = new GlobusIdentityProvider(testIDConfig);
		final String redir = testIDConfig.getLoginRedirectURL().toString();
		final String bauth = getBasicAuth(testIDConfig);
		final IdentityRetrievalException e =
				new IdentityRetrievalException("No access token was returned by Globus");
		setUpCallAuthToken("authcode3", null, redir, bauth);
		failGetIdentities(idp, "authcode3", false, e);
		setUpCallAuthToken("authcode3", "     \n    ", redir, bauth);
		failGetIdentities(idp, "authcode3", false, e);
		
	}
	
	private void failGetIdentities(
			final IdentityProvider idp,
			final String authcode,
			final boolean link,
			final Exception exception) throws Exception {
		try {
			idp.getIdentities(authcode, link);
			fail("got identities with bad setup");
		} catch (Exception e) {
			TestCommon.assertExceptionCorrect(e, exception);
		}
	}

	private IdentityProviderConfig getTestIDConfig()
			throws IdentityProviderConfigurationException, MalformedURLException,
			URISyntaxException {
		return new IdentityProviderConfig(
				"Globus",
				new URL("https://login.com"),
				new URL("http://localhost:" + mockClientAndServer.getPort()),
				"foo",
				"bar",
				new URI("http://image.com"),
				new URL("https://loginredir.com"),
				new URL("https://linkredir.com"));
	}

	private String getBasicAuth(final IdentityProviderConfig idconfig) {
		return "Basic " + Base64.getEncoder().encodeToString(
				(idconfig.getClientID() + ":" + idconfig.getClientSecret()).getBytes());
	}

	private void setUpCallAuthToken(
			final String authCode,
			final String authtoken,
			final String redirect,
			final String basicAuth)
			throws Exception {
		mockClientAndServer.when(
				new HttpRequest()
					.withMethod("POST")
					.withPath("/v2/oauth2/token")
					.withHeader(ACCEPT, APP_JSON)
					.withHeader("Authorization", basicAuth)
					.withBody(new ParameterBody(
							new Parameter("code", authCode),
							new Parameter("grant_type", "authorization_code"),
							new Parameter("redirect_uri", redirect))
					),
				Times.exactly(1)
			).respond(
				new HttpResponse()
					.withStatusCode(200)
					.withHeader(CONTENT_TYPE, APP_JSON)
					.withBody(MAPPER.writeValueAsString(map("access_token", authtoken))
					)
			);
	}
	
	@Test
	public void getIdentityWithSecondariesAndLoginURL() throws Exception {
		final String authCode = "authcode";
		final IdentityProviderConfig testIDConfig = getTestIDConfig();
		final IdentityProvider idp = new GlobusIdentityProvider(testIDConfig);
		final String bauth = getBasicAuth(testIDConfig);

		setUpCallAuthToken(authCode, "footoken", "https://loginredir.com", bauth);
		mockClientAndServer.when(
					new HttpRequest()
						.withMethod("POST")
						.withPath("/v2/oauth2/token/introspect")
						.withHeader(ACCEPT, APP_JSON)
						.withHeader("Authorization", bauth)
						.withBody(new ParameterBody(
								new Parameter("include", "identities_set"),
								new Parameter("token", "footoken"))
						),
					Times.exactly(1)
				).respond(
					new HttpResponse()
						.withStatusCode(200)
						.withHeader(new Header(CONTENT_TYPE, APP_JSON))
						.withBody(MAPPER.writeValueAsString(
								new ImmutableMap.Builder<String, Object>()
									.put("aud", Arrays.asList(testIDConfig.getClientID()))
									.put("sub", "anID")
									.put("username", "aUsername")
									.put("name", "fullname")
									.put("email", "anEmail")
									.put("identities_set",
											Arrays.asList("ident1", "anID", "ident2"))
									.build())
						)
				);
		final List<Map<String, Object>> idents = new LinkedList<>();
		idents.add(map("id", "id1", "username", "user1", "name", "name1", "email", null));
		idents.add(map("id", "id2", "username", "user2", "name", null, "email", "email2"));
		
		mockClientAndServer.when(
					new HttpRequest()
						.withMethod("GET")
						.withPath("/v2/api/identities")
						.withHeader(ACCEPT, APP_JSON)
						.withHeader("Authorization", "Bearer " +  "footoken")
						.withQueryStringParameter("ids", "^ident2,ident1|ident1,ident2$"),
					Times.exactly(1)
				).respond(
					new HttpResponse()
						.withStatusCode(200)
						.withHeader(new Header(CONTENT_TYPE, APP_JSON))
						.withBody(MAPPER.writeValueAsString(ImmutableMap.of(
								"identities", idents))
						)
				);
				
				
		final Set<RemoteIdentity> rids = idp.getIdentities(authCode, false);
		final Set<RemoteIdentity> expected = new HashSet<>();
		expected.add(new RemoteIdentity(new RemoteIdentityID(GLOBUS, "anID"),
				new RemoteIdentityDetails("aUsername", "fullname", "anEmail")));
		expected.add(new RemoteIdentity(new RemoteIdentityID(GLOBUS, "id1"),
				new RemoteIdentityDetails("user1", "name1", null)));
		expected.add(new RemoteIdentity(new RemoteIdentityID(GLOBUS, "id2"),
				new RemoteIdentityDetails("user2", null, "email2")));
		assertThat("incorrect ident set", rids, is(expected));
	}

	@Test
	public void getIdentityWithoutSecondariesAndLinkURL() throws Exception {
		final String clientID = "clientID2";
		final String authCode = "authcode2";
		final IdentityProviderConfig idconfig = new IdentityProviderConfig(
				"Globus",
				new URL("https://login2.com"),
				new URL("http://localhost:" + mockClientAndServer.getPort()),
				clientID,
				"bar2",
				new URI("http://image2.com"),
				new URL("https://loginredir2.com"),
				new URL("https://linkredir2.com"));
		final IdentityProvider idp = new GlobusIdentityProvider(idconfig);
		final String bauth = getBasicAuth(idconfig);
		
		setUpCallAuthToken(authCode, "footoken2", "https://linkredir2.com", bauth);
		mockClientAndServer.when(
					new HttpRequest()
						.withMethod("POST")
						.withPath("/v2/oauth2/token/introspect")
						.withHeader(ACCEPT, APP_JSON)
						.withHeader("Authorization", bauth)
						.withBody(new ParameterBody(
								new Parameter("include", "identities_set"),
								new Parameter("token", "footoken2"))
						),
					Times.exactly(1)
				).respond(
					new HttpResponse()
						.withStatusCode(200)
						.withHeader(new Header(CONTENT_TYPE, APP_JSON))
						.withBody(MAPPER.writeValueAsString(map(
									"aud", Arrays.asList(clientID),
									"sub", "anID2",
									"username", "aUsername2",
									"name", null,
									"email", null,
									"identities_set", Arrays.asList("anID2"))
								)
						)
				);
		final Set<RemoteIdentity> rids = idp.getIdentities(authCode, true);
		final Set<RemoteIdentity> expected = new HashSet<>();
		expected.add(new RemoteIdentity(new RemoteIdentityID(GLOBUS, "anID2"),
				new RemoteIdentityDetails("aUsername2", null, null)));
		assertThat("incorrect ident set", rids, is(expected));
	}
	
	private Map<String, Object> map(final Object... entries) {
		if (entries.length % 2 != 0) {
			throw new IllegalArgumentException();
		}
		final Map<String, Object> ret = new HashMap<>();
		for (int i = 0; i < entries.length; i += 2) {
			ret.put((String) entries[i], entries[i + 1]);
		}
		return ret;
	}
}
