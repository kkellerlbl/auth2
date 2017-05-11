package us.kbase.test.auth2.service.common;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import us.kbase.auth2.lib.Authentication;
import us.kbase.auth2.lib.DisplayName;
import us.kbase.auth2.lib.EmailAddress;
import us.kbase.auth2.lib.TokenCreationContext;
import us.kbase.auth2.lib.UserUpdate;
import us.kbase.auth2.lib.config.ConfigItem;
import us.kbase.auth2.lib.exceptions.ErrorType;
import us.kbase.auth2.lib.exceptions.ExternalConfigMappingException;
import us.kbase.auth2.lib.exceptions.IllegalParameterException;
import us.kbase.auth2.lib.exceptions.NoTokenProvidedException;
import us.kbase.auth2.lib.identity.IdentityProviderFactory;
import us.kbase.auth2.lib.token.IncomingToken;
import us.kbase.auth2.providers.GoogleIdentityProviderFactory;
import us.kbase.auth2.service.AuthExternalConfig;
import us.kbase.auth2.service.UserAgentParser;
import us.kbase.auth2.service.common.ServiceCommon;
import us.kbase.auth2.service.exceptions.AuthConfigurationException;
import us.kbase.test.auth2.TestCommon;

public class ServiceCommonTest {
	
	@Test
	public void getToken() throws Exception {
		final IncomingToken t = ServiceCommon.getToken("  \t    fooo   \n ");
		assertThat("incorrect token", t.getToken(), is("fooo"));
	}
	
	@Test
	public void getTokenFail() throws Exception {
		failGetToken(null);
		failGetToken("   \t   \n   ");
	}

	private void failGetToken(final String s) {
		try {
			ServiceCommon.getToken(s);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got,
					new NoTokenProvidedException("No user token provided"));
		}
	}
	
	@Test
	public void updateUserNulls() throws Exception {
		final Authentication auth = mock(Authentication.class);
		ServiceCommon.updateUser(auth, new IncomingToken("foo"), null, null);
		verify(auth).updateUser(new IncomingToken("foo"), UserUpdate.getBuilder().build());
	}
	
	@Test
	public void updateUserEmpty() throws Exception {
		final Authentication auth = mock(Authentication.class);
		ServiceCommon.updateUser(auth, new IncomingToken("foo"), " \t \n  ", " \t \n  ");
		verify(auth).updateUser(new IncomingToken("foo"), UserUpdate.getBuilder().build());
	}
	
	@Test
	public void updateUserValidInput() throws Exception {
		final Authentication auth = mock(Authentication.class);
		ServiceCommon.updateUser(auth, new IncomingToken("foo"), "my name", "f@g.com");
		verify(auth).updateUser(new IncomingToken("foo"), UserUpdate.getBuilder()
				.withDisplayName(new DisplayName("my name"))
				.withEmail(new EmailAddress("f@g.com"))
				.build());
	}
	
	@Test
	public void updateUserFailNulls() throws Exception {
		final Authentication auth = mock(Authentication.class);
		final IncomingToken token = new IncomingToken("foo");
		final String displayName = "foo";
		final String email = "f@g.com";
		failUpdateUser(null, token, displayName, email, new NullPointerException("auth"));
		failUpdateUser(auth, null, displayName, email, new NullPointerException("token"));
		failUpdateUser(auth, token, "foo\nbar", email,
				new IllegalParameterException("display name contains control characters"));
		failUpdateUser(auth, token, displayName, "notanemail",
				new IllegalParameterException(ErrorType.ILLEGAL_EMAIL_ADDRESS, "notanemail"));
	}
	
	@Test
	public void updateUserFailBadDisplayName() throws Exception {
		final Authentication auth = mock(Authentication.class);
		final IncomingToken token = new IncomingToken("foo");
		final String displayName = "foo\nbar";
		final String email = "f@g.com";
		failUpdateUser(auth, token, displayName, email,
				new IllegalParameterException("display name contains control characters"));
	}
	
	@Test
	public void updateUserFailBadEmail() throws Exception {
		final Authentication auth = mock(Authentication.class);
		final IncomingToken token = new IncomingToken("foo");
		final String displayName = "foobar";
		final String email = "not an email";
		failUpdateUser(auth, token, displayName, email,
				new IllegalParameterException(ErrorType.ILLEGAL_EMAIL_ADDRESS, "not an email"));
	}
	
	private void failUpdateUser(
			final Authentication auth,
			final IncomingToken token,
			final String displayName,
			final String email,
			final Exception e)
			throws Exception {
		try {
			ServiceCommon.updateUser(auth, token, displayName, email);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, e);
		}
	}
	
	@Test
	public void loadClassWithInterface() throws Exception {
		final IdentityProviderFactory fac = ServiceCommon.loadClassWithInterface(
				GoogleIdentityProviderFactory.class.getName(), IdentityProviderFactory.class);
		assertThat("incorrect class loaded", fac, instanceOf(GoogleIdentityProviderFactory.class));
	}
	
	@Test
	public void loadClassWithInterfaceFailNoSuchClass() throws Exception {
		failLoadClassWithInterface(GoogleIdentityProviderFactory.class.getName() + "a",
				IdentityProviderFactory.class, new AuthConfigurationException(
						"Cannot load class us.kbase.auth2.providers." +
						"GoogleIdentityProviderFactorya: us.kbase.auth2.providers." +
								"GoogleIdentityProviderFactorya"));
	}
	
	@Test
	public void loadClassWithInterfaceFailIncorrectInterface() throws Exception {
		failLoadClassWithInterface(Map.class.getName(), IdentityProviderFactory.class, 
				new AuthConfigurationException("Module java.util.Map must implement " +
						"us.kbase.auth2.lib.identity.IdentityProviderFactory interface"));
	}
	
	@Test
	public void loadClassWithInterfaceFailOnConstruct() throws Exception {
		failLoadClassWithInterface(FailOnInstantiation.class.getName(),
				IdentityProviderFactory.class, new IllegalArgumentException("foo"));
	}
	
	@Test
	public void loadClassWithInterfaceFailNoNullaryConstructor() throws Exception {
		failLoadClassWithInterface(FailOnInstantiationNoNullaryConstructor.class.getName(),
				IdentityProviderFactory.class, new AuthConfigurationException(
						"Module us.kbase.test.auth2.service.common.FailOnInstantiation" +
						"NoNullaryConstructor could not be instantiated: us.kbase.test.auth2." +
						"service.common.FailOnInstantiationNoNullaryConstructor"));
	}
	
	@Test
	public void loadClassWithInterfaceFailPrivateConstructor() throws Exception {
		failLoadClassWithInterface(FailOnInstantiationPrivateConstructor.class.getName(),
				IdentityProviderFactory.class, new AuthConfigurationException(
						"Module us.kbase.test.auth2.service.common.FailOnInstantiation" +
						"PrivateConstructor could not be instantiated: Class us.kbase.auth2." +
						"service.common.ServiceCommon can not access a member of class us." +
						"kbase.test.auth2.service.common.FailOnInstantiationPrivateConstructor " +
						"with modifiers \"private\""));
	}
	
	private void failLoadClassWithInterface(
			final String className,
			final Class<?> interfce,
			final Exception e)
			throws Exception {
		try {
			ServiceCommon.loadClassWithInterface(className, interfce);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, e);
		}
	}
	
	private final String TEST_USER_AGENT =
			"Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:47.0) Gecko/20100101 Firefox/47.0";
	
	@Test
	public void getTokenContextMinimalInputIgnoreIPHeaders() throws Exception {
		getTokenContextTestIPHeaders(true, "127.0.0.7", null, null, "127.0.0.7");
	}
	
	@Test
	public void getTokenContextWithCustomContextAndBadIP() throws Exception {
		final HttpServletRequest req = mock(HttpServletRequest.class);
		// mocked because creation takes multiple seconds
		final UserAgentParser parser = mock(UserAgentParser.class);
		when(req.getRemoteAddr()).thenReturn("fakeip");
		when(req.getHeader("user-agent")).thenReturn(TEST_USER_AGENT);
		when(req.getHeader("x-forwarded-for")).thenReturn(null);
		when(req.getHeader("x-real-ip")).thenReturn(null);
		when(parser.getTokenContextFromUserAgent(TEST_USER_AGENT)).thenReturn(
				TokenCreationContext.getBuilder()
						.withNullableAgent("Firefox", "47.0")
						.withNullableOS("Windows NT", "6.1"));
		final TokenCreationContext res = ServiceCommon.getTokenContext(
				parser, req, true, ImmutableMap.of("foo", "bar", "baz", "bat"));
		final TokenCreationContext expected = TokenCreationContext.getBuilder()
				.withNullableAgent("Firefox", "47.0")
				.withNullableOS("Windows NT", "6.1")
				.withCustomContext("baz", "bat")
				.withCustomContext("foo", "bar")
				.build();
		
		assertThat("incorrect context", res, is(expected));
	}
	
	@Test
	public void getTokenContextNoIP() throws Exception {
		final HttpServletRequest req = mock(HttpServletRequest.class);
		// mocked because creation takes multiple seconds
		final UserAgentParser parser = mock(UserAgentParser.class);
		when(req.getRemoteAddr()).thenReturn("   \t  ");
		when(req.getHeader("user-agent")).thenReturn(TEST_USER_AGENT);
		when(req.getHeader("x-forwarded-for")).thenReturn(null);
		when(req.getHeader("x-real-ip")).thenReturn(null);
		when(parser.getTokenContextFromUserAgent(TEST_USER_AGENT)).thenReturn(
				TokenCreationContext.getBuilder()
						.withNullableAgent("Firefox", "47.0")
						.withNullableOS("Windows NT", "6.1"));
		final TokenCreationContext res = ServiceCommon.getTokenContext(
				parser, req, true, Collections.emptyMap());
		final TokenCreationContext expected = TokenCreationContext.getBuilder()
				.withNullableAgent("Firefox", "47.0")
				.withNullableOS("Windows NT", "6.1")
				.build();
		
		assertThat("incorrect context", res, is(expected));
	}
	
	@Test
	public void getTokenContextXFFHeader() throws Exception {
		getTokenContextTestIPHeaders(false, "127.0.0.7", "127.0.0.2, 127.0.0.3", "127.0.0.4",
				"127.0.0.2");
	}
	
	@Test
	public void getTokenContextRealIPHeader() throws Exception {
		getTokenContextTestIPHeaders(false, "127.0.0.7", null, "127.0.0.4", "127.0.0.4");
	}
	
	@Test
	public void getTokenContextRealIPEmptyXFFHeader() throws Exception {
		getTokenContextTestIPHeaders(false, "127.0.0.7", "  \t   ", "127.0.0.4", "127.0.0.4");
	}
	
	@Test
	public void getTokenContextNullHeaders() throws Exception {
		getTokenContextTestIPHeaders(false, "127.0.0.7", null, null, "127.0.0.7");
	}
	
	@Test
	public void getTokenContextEmptyRealIPHeader() throws Exception {
		getTokenContextTestIPHeaders(false, "127.0.0.7", null, "  \t   ", "127.0.0.7");
	}
	
	private void getTokenContextTestIPHeaders(
			final boolean ignoreIpHeaders,
			final String ip,
			final String xff,
			final String xrealip,
			final String expectedIp)
			throws Exception {
		final HttpServletRequest req = mock(HttpServletRequest.class);
		when(req.getRemoteAddr()).thenReturn(ip);
		when(req.getHeader("user-agent")).thenReturn(TEST_USER_AGENT);
		when(req.getHeader("X-Forwarded-For")).thenReturn(xff);
		when(req.getHeader("X-Real-IP")).thenReturn(xrealip);
		final UserAgentParser parser = mock(UserAgentParser.class);
		// mocked because creation takes multiple seconds
		when(parser.getTokenContextFromUserAgent(TEST_USER_AGENT)).thenReturn(
				TokenCreationContext.getBuilder()
						.withNullableAgent("Firefox", "47.0")
						.withNullableOS("Windows NT", "6.1"));
		final TokenCreationContext res = ServiceCommon.getTokenContext(
				parser, req, ignoreIpHeaders, Collections.emptyMap());
		final TokenCreationContext expected = TokenCreationContext.getBuilder()
				.withIpAddress(InetAddress.getByName(expectedIp))
				.withNullableAgent("Firefox", "47.0")
				.withNullableOS("Windows NT", "6.1")
				.build();
		
		assertThat("incorrect context", res, is(expected));
	}
	
	@Test
	public void getTokenContectFailNulls() throws Exception {
		final UserAgentParser parser = mock(UserAgentParser.class);
		final HttpServletRequest req = mock(HttpServletRequest.class);
		final Map<String, String> customContext = Collections.emptyMap();
		failGetTokenContext(null, req, customContext, new NullPointerException("userAgentParser"));
		failGetTokenContext(parser, null, customContext, new NullPointerException("request"));
		failGetTokenContext(parser, req, null, new NullPointerException("customContext"));
		
	}
	
	private void failGetTokenContext(
			final UserAgentParser parser,
			final HttpServletRequest req,
			final Map<String, String> customContext,
			final Exception e)
			throws Exception {
		try {
			ServiceCommon.getTokenContext(parser, req, true, customContext);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, e);
		}
	}
	
	@Test
	public void isIgnoreIPsInHeaders() throws Exception {
		final Authentication auth = mock(Authentication.class);
		when(auth.getExternalConfig(isA(AuthExternalConfig.AuthExternalConfigMapper.class)))
				.thenReturn(new AuthExternalConfig<>(ConfigItem.emptyState(),
						ConfigItem.emptyState(), ConfigItem.emptyState(), ConfigItem.emptyState(),
						ConfigItem.state(false), ConfigItem.state(false)));
		assertThat("incorrect ignore IPs setting", ServiceCommon.isIgnoreIPsInHeaders(auth),
				is(false));
	}
	
	@Test
	public void isIgnoreIPsInHeadersFail() throws Exception {
		final Authentication auth = mock(Authentication.class);
		when(auth.getExternalConfig(isA(AuthExternalConfig.AuthExternalConfigMapper.class)))
				.thenThrow(new ExternalConfigMappingException("foo"));
		try {
			ServiceCommon.isIgnoreIPsInHeaders(auth);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got,
					new RuntimeException("There appears to be a programming error here..."));
		}
	}
	
	@Test
	public void getCustomContext() throws Exception {
		final Map<String, String> cc = ServiceCommon.getCustomContextFromString(
				"   \t  foo   , \n  bar; baz,bat;  \t \n ;");
		assertThat("incorrect custom context", cc,
				is(ImmutableMap.of("foo", "bar", "baz", "bat")));
	}
	
	@Test
	public void getCustomContextNull() throws Exception {
		final Map<String, String> cc = ServiceCommon.getCustomContextFromString(null);
		assertThat("incorrect custom context", cc, is(Collections.emptyMap()));
	}
	
	@Test
	public void getCustomContextEmpty() throws Exception {
		final Map<String, String> cc = ServiceCommon.getCustomContextFromString("  \t \n  ");
		assertThat("incorrect custom context", cc, is(Collections.emptyMap()));
	}
	
	@Test
	public void getCustomContextFail() throws Exception {
		try {
			ServiceCommon.getCustomContextFromString("  foo, bar, baz  ");
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, new IllegalParameterException(
					"Bad key/value pair in custom context: foo, bar, baz"));
		}
	}

	@Test
	public void nullOrEmptyNeither() {
		assertThat("incorrect nullOrEmpty response", ServiceCommon.nullOrEmpty("s"), is(false));
	}
	
	@Test
	public void nullOrEmptyNull() {
		assertThat("incorrect nullOrEmpty response", ServiceCommon.nullOrEmpty(null), is(true));
	}
	
	@Test
	public void nullOrEmptyEmpty() {
		assertThat("incorrect nullOrEmpty response", ServiceCommon.nullOrEmpty("  \t "), is(true));
	}
	
}
