package us.kbase.auth2.lib;

import static us.kbase.auth2.lib.Utils.checkString;
import static us.kbase.auth2.lib.Utils.clear;

import java.net.URL;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.base.Optional;

import us.kbase.auth2.cryptutils.PasswordCrypt;
import us.kbase.auth2.cryptutils.RandomDataGenerator;
import us.kbase.auth2.lib.CollectingExternalConfig.CollectingExternalConfigMapper;
import us.kbase.auth2.lib.exceptions.ErrorType;
import us.kbase.auth2.lib.exceptions.ExternalConfigMappingException;
import us.kbase.auth2.lib.exceptions.IdentityLinkedException;
import us.kbase.auth2.lib.exceptions.IdentityRetrievalException;
import us.kbase.auth2.lib.exceptions.IllegalParameterException;
import us.kbase.auth2.lib.AuthConfig.ProviderConfig;
import us.kbase.auth2.lib.AuthConfig.TokenLifetimeType;
import us.kbase.auth2.lib.exceptions.AuthenticationException;
import us.kbase.auth2.lib.exceptions.DisabledUserException;
import us.kbase.auth2.lib.exceptions.InvalidTokenException;
import us.kbase.auth2.lib.exceptions.LinkFailedException;
import us.kbase.auth2.lib.exceptions.MissingParameterException;
import us.kbase.auth2.lib.exceptions.NoSuchIdentityProviderException;
import us.kbase.auth2.lib.exceptions.NoSuchRoleException;
import us.kbase.auth2.lib.exceptions.NoSuchTokenException;
import us.kbase.auth2.lib.exceptions.NoSuchUserException;
import us.kbase.auth2.lib.exceptions.UnLinkFailedException;
import us.kbase.auth2.lib.exceptions.UnauthorizedException;
import us.kbase.auth2.lib.exceptions.UserExistsException;
import us.kbase.auth2.lib.identity.IdentityProvider;
import us.kbase.auth2.lib.identity.IdentityProviderSet;
import us.kbase.auth2.lib.identity.RemoteIdentity;
import us.kbase.auth2.lib.identity.RemoteIdentityWithLocalID;
import us.kbase.auth2.lib.storage.AuthStorage;
import us.kbase.auth2.lib.storage.exceptions.AuthStorageException;
import us.kbase.auth2.lib.storage.exceptions.StorageInitException;
import us.kbase.auth2.lib.token.NewToken;
import us.kbase.auth2.lib.token.TemporaryToken;
import us.kbase.auth2.lib.token.TokenSet;
import us.kbase.auth2.lib.token.TokenType;
import us.kbase.auth2.lib.token.HashedToken;
import us.kbase.auth2.lib.token.IncomingToken;

/** The main class for the Authentication application.
 * 
 * Handles creating accounts, login & un/linking accounts via OAuth2 flow, creating, deleting, and
 * validating tokens, handling internal and external configuration, managing user roles and
 * passwords, and searching for users.
 * 
 * Manages two types of accounts. Standard users are created and logged into via OAuth2 identity
 * providers and always have at least one 3rd party identity associated with the user. Local users
 * have no linked identities and are accessed by a traditional password scheme. The intent is
 * that local user accounts are used sparingly for admins for the authorization application,
 * admins for other services, and owners of long-lived server tokens. The vast majority of users
 * should use standard accounts.
 * 
 * Manages two types of roles. Built in roles define which actions a user may take with regard to
 * the authentication application. Additionally, administrators may define custom roles and
 * assign them to users. It is expected that the number of custom roles are relatively small (on
 * the order of thousands at most) per storage instance.
 * 
 * Many methods require a token for a user with specific roles. Most often this is the
 * administrator role. In some, but not all, cases the create administrator and root roles can also
 * perform the action. The root role's only purpose is to grant the the create administrator role.
 * The create administrator role's only purpose is to grant the administrator role.
 * 
 * @author gaprice@lbl.gov
 *
 */
public class Authentication {

	//TODO TEST test unicode for inputs and outputs
	//TODO TEST unit tests
	//TODO TEST test logging on startup
	//TODO TEST test logging on calls
	//TODO ZZLATER validate email address by sending an email
	//TODO AUTH server root should return server version (and urls for endpoints?)
	//TODO LOG logging everywhere - on login, on logout, on create / delete / expire token
	//TODO SCOPES configure scopes via ui
	//TODO SCOPES configure scope on login via ui
	//TODO SCOPES restricted scopes - allow for specific roles or users (or for specific clients via oauth2)
	//TODO USER_PROFILE_SERVICE email & username change propagation
	//TODO DEPLOY jetty should start app immediately & fail if app fails
	
	private static final int MAX_RETURNED_USERS = 10000;
	private static final int TEMP_PWD_LENGTH = 10;
	
	private static final UserName DEFAULT_SUGGESTED_USER_NAME;
	private static final DisplayName UNKNOWN_DISPLAY_NAME;
	static {
		try {
			DEFAULT_SUGGESTED_USER_NAME = new UserName("user");
			UNKNOWN_DISPLAY_NAME = new DisplayName("unknown");
		} catch (IllegalParameterException | MissingParameterException e) {
			throw new RuntimeException("this should be impossible", e);
		}
	}

	private final AuthStorage storage;
	private final IdentityProviderSet idProviderSet;
	private final RandomDataGenerator randGen;
	private final PasswordCrypt pwdcrypt;
	private final ConfigManager cfg;
	
	/** Create a new Authentication instance.
	 * @param storage the storage system to use for information persistance.
	 * @param identityProviderSet the set of identity providers that are supported for standard
	 * accounts. E.g. Google, Globus, etc.
	 * @param defaultExternalConfig the external configuration default settings. Any settings
	 * that do not already exist in the storage system will be persisted. Pre-existing settings
	 * are not overwritten.
	 * @throws StorageInitException if the storage system cannot be accessed.
	 */
	public Authentication(
			final AuthStorage storage,
			final IdentityProviderSet identityProviderSet,
			final ExternalConfig defaultExternalConfig)
			throws StorageInitException {
		
		try {
			randGen = new RandomDataGenerator();
			pwdcrypt = new PasswordCrypt();
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("This should be impossible", e);
		}
		if (storage == null) {
			throw new NullPointerException("storage");
		}
		if (identityProviderSet == null) {
			throw new NullPointerException("identityProviderSet");
		}
		if (defaultExternalConfig == null) {
			throw new NullPointerException("defaultExternalConfig");
		}
		this.storage = storage;
		this.idProviderSet = identityProviderSet;
		idProviderSet.lock();
		final Map<String, ProviderConfig> provs = new HashMap<>();
		for (final String provname: idProviderSet.getProviders()) {
			provs.put(provname, AuthConfig.DEFAULT_PROVIDER_CONFIG);
		}
		final AuthConfig ac =  new AuthConfig(AuthConfig.DEFAULT_LOGIN_ALLOWED, provs,
				AuthConfig.DEFAULT_TOKEN_LIFETIMES_MS);
		try {
			storage.updateConfig(new AuthConfigSet<ExternalConfig>(
					ac, defaultExternalConfig), false);
		} catch (AuthStorageException e) {
			throw new StorageInitException("Failed to set config in storage: " +
					e.getMessage(), e);
		}
		try {
			cfg = new ConfigManager(storage);
		} catch (AuthStorageException e) {
			throw new StorageInitException("Failed to initialize config manager: " +
					e.getMessage(), e);
		}
	}
	
	/* Caches the configuration to avoid pulling the configuration from the storage system
	 * on every request. Synchronized to prevent multiple storage accesses for one update.
	 */
	//TODO TEST config manager
	private class ConfigManager {
	
		private static final int CFG_UPDATE_INTERVAL_SEC = 30;
		
		private AuthConfigSet<CollectingExternalConfig> cfg;
		private Date nextConfigUpdate;
		private AuthStorage storage;
		
		public ConfigManager(final AuthStorage storage)
				throws AuthStorageException {
			this.storage = storage;
			updateConfig();
		}
		
		public synchronized AuthConfigSet<CollectingExternalConfig> getConfig()
				throws AuthStorageException {
			if (new Date().after(nextConfigUpdate)) {
				updateConfig();
			}
			return cfg;
		}
		
		public AuthConfig getAppConfig() throws AuthStorageException {
			return getConfig().getCfg();
		}
	
		public synchronized void updateConfig() throws AuthStorageException {
			try {
				cfg = storage.getConfig(new CollectingExternalConfigMapper());
			} catch (ExternalConfigMappingException e) {
				throw new RuntimeException("This should be impossible", e);
			}
			nextConfigUpdate = new Date(new Date().getTime() +
					CFG_UPDATE_INTERVAL_SEC * 1000);
		}
	}

	/** Create a root account, or update the root account password if one does not already exist.
	 * If the root account exists and is disabled, it will be enabled.
	 * 
	 * This method should not be exposed in public APIs.
	 * 
	 * The method calls pwd.clear() on the passed-in password as soon as possible.
	 * 
	 * @param pwd the new password for the root account.
	 * @throws AuthStorageException if updating the root account fails.
	 */
	public void createRoot(final Password pwd) throws AuthStorageException {
		if (pwd == null) {
			throw new NullPointerException("pwd");
		}
		final byte[] salt = randGen.generateSalt();
		final byte[] passwordHash = pwdcrypt.getEncryptedPassword(pwd.getPassword(), salt);
		pwd.clear();
		final DisplayName dn;
		try {
			dn = new DisplayName("root");
		} catch (IllegalParameterException | MissingParameterException e) {
			throw new RuntimeException("This is impossible", e);
		}
		final NewRootUser root = new NewRootUser(EmailAddress.UNKNOWN, dn, passwordHash, salt);
		try {
			storage.createLocalUser(root);
		// only way to avoid a race condition. Checking existence before creating user means if
		// user is added between check and update update will fail
		} catch (UserExistsException uee) {
			try {
				storage.changePassword(UserName.ROOT, passwordHash, salt, false);
				if (storage.getUser(UserName.ROOT).isDisabled()) {
					storage.enableAccount(UserName.ROOT, UserName.ROOT);
				}
			} catch (NoSuchUserException nsue) {
				throw new RuntimeException("OK. This is really bad. I give up.", nsue);
			}
		}
		clear(passwordHash);
		clear(salt);
	}
	
	/** Creates a new local user.
	 * @param adminToken a token for a user with the administrator, create administrator, or root
	 * role.
	 * @param userName the name of the new user.
	 * @param displayName the display name of the new user.
	 * @param email the email address of the new user.
	 * @return the new password for the local user.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws UserExistsException if the username is already in use.
	 * @throws UnauthorizedException if the user for the token is not an admin, or the username to
	 * be created is the root user name.
	 * @throws InvalidTokenException if the token is invalid.
	 */
	public Password createLocalUser(
			final IncomingToken adminToken,
			final UserName userName,
			final DisplayName displayName,
			final EmailAddress email)
			throws AuthStorageException, UserExistsException, UnauthorizedException,
			InvalidTokenException {
		getUser(adminToken, Role.ROOT, Role.CREATE_ADMIN, Role.ADMIN);
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		if (userName.isRoot()) {
			throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
					"Cannot create ROOT user");
		}
		if (displayName == null) {
			throw new NullPointerException("displayName");
		}
		if (email == null) {
			throw new NullPointerException("email");
		}
		final Password pwd = new Password(randGen.getTemporaryPassword(TEMP_PWD_LENGTH));
		final byte[] salt = randGen.generateSalt();
		final byte[] passwordHash = pwdcrypt.getEncryptedPassword(pwd.getPassword(), salt);
		final NewLocalUser lu = new NewLocalUser(userName, email, displayName,
				passwordHash, salt, true);
		storage.createLocalUser(lu);
		clear(passwordHash);
		clear(salt);
		return pwd;
	}
	
	/** Login with a local user.
	 * @param userName the username of the account.
	 * @param pwd the password for the account.
	 * @return the result of the login attempt.
	 * @throws AuthenticationException if the login attempt failed.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws UnauthorizedException if the user is not an admin and non-admin login is disabled or
	 * the user account is disabled.
	 */
	public LocalLoginResult localLogin(final UserName userName, final Password pwd)
			throws AuthenticationException, AuthStorageException, UnauthorizedException {
		final LocalUser u = getLocalUser(userName, pwd);
		if (u.isPwdResetRequired()) {
			return new LocalLoginResult(u.getUserName());
		}
		return new LocalLoginResult(login(u.getUserName()));
	}

	private LocalUser getLocalUser(final UserName userName, final Password pwd)
			throws AuthStorageException, AuthenticationException, UnauthorizedException {
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		if (pwd == null) {
			throw new NullPointerException("pwd");
		}
		final LocalUser u;
		try {
			u = storage.getLocalUser(userName);
		} catch (NoSuchUserException e) {
			throw new AuthenticationException(ErrorType.AUTHENTICATION_FAILED,
					"Username / password mismatch");
		}
		if (!pwdcrypt.authenticate(pwd.getPassword(), u.getPasswordHash(), u.getSalt())) {
			throw new AuthenticationException(ErrorType.AUTHENTICATION_FAILED,
					"Username / password mismatch");
		}
		if (!cfg.getAppConfig().isLoginAllowed() && !Role.isAdmin(u.getRoles())) {
			throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
					"Non-admin login is disabled");
		}
		if (u.isDisabled()) {
			throw new UnauthorizedException(ErrorType.DISABLED, "This account is disabled");
		}
		pwd.clear();
		return u;
	}

	/** Change a local user's password.
	 * @param userName the user name of the account.
	 * @param pwdold the old password for the user account.
	 * @param pwdnew the new password for the user account.
	 * @throws AuthenticationException if the username and password do not match.
	 * @throws UnauthorizedException if the user is not an admin and non-admin login is disabled or
	 * the user account is disabled.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public void localPasswordChange(
			final UserName userName,
			final Password pwdold,
			final Password pwdnew)
			throws AuthenticationException, UnauthorizedException, AuthStorageException {
		if (pwdnew == null) {
			throw new NullPointerException("pwdnew");
		}
		//TODO PWD do any cross pwd checks like checking they're not the same
		getLocalUser(userName, pwdold); //checks pwd validity and nulls
		final byte[] salt = randGen.generateSalt();
		final byte[] passwordHash = pwdcrypt.getEncryptedPassword(pwdnew.getPassword(), salt);
		pwdnew.clear();
		try {
			storage.changePassword(userName, passwordHash, salt, false);
		} catch (NoSuchUserException e) {
			// we know user already exists and is local so this can't happen
			throw new RuntimeException("Sorry, you ceased to exist in the last ~10ms.", e);
		}
		clear(passwordHash);
		clear(salt);
	}

	/** Reset a local user's password to a random password.
	 * @param token a token for a user with the administrator role.
	 * @param userName the user name of the account to reset.
	 * @return the new password.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token does not
	 * have the administrator role.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws NoSuchUserException if there is no user corresponding to the given user name.
	 */
	public Password resetPassword(final IncomingToken token, final UserName userName)
			throws InvalidTokenException, UnauthorizedException, AuthStorageException,
			NoSuchUserException {
		//TODO RESET to reset and get admin password must be that admin
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		getUser(token, Role.ADMIN); // force admin
		
		final Password pwd = new Password(randGen.getTemporaryPassword(TEMP_PWD_LENGTH));
		final byte[] salt = randGen.generateSalt();
		final byte[] passwordHash = pwdcrypt.getEncryptedPassword(pwd.getPassword(), salt);
		storage.changePassword(userName, passwordHash, salt, true);
		clear(passwordHash);
		clear(salt);
		return pwd;
	}
	
	/** Force a local user to reset their password on their next login.
	 * @param token a token for a user with the administrator role.
	 * @param userName the user name of the account for which a password reset is required.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token does not
	 * have the administrator role.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws NoSuchUserException if there is no user corresponding to the given user name.
	 */
	public void forceResetPassword(final IncomingToken token, final UserName userName)
			throws InvalidTokenException, UnauthorizedException, AuthStorageException,
			NoSuchUserException {
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		getUser(token, Role.ADMIN); // force admin
		storage.forcePasswordReset(userName);
	}

	/** Force all local users to reset their password on their next login.
	 * @param token a token for a user with the administrator role.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token does not
	 * have the administrator role.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public void forceResetAllPasswords(final IncomingToken token)
			throws InvalidTokenException, UnauthorizedException, AuthStorageException {
		getUser(token, Role.ADMIN); // force admin
		storage.forcePasswordReset();
	}
	
	private NewToken login(final UserName userName) throws AuthStorageException {
		final NewToken nt = new NewToken(TokenType.LOGIN, randGen.getToken(),
				userName, cfg.getAppConfig().getTokenLifetimeMS(TokenLifetimeType.LOGIN));
		storage.storeToken(nt.getHashedToken());
		setLastLogin(userName);
		return nt;
	}

	// used when it's known that the user exists
	private void setLastLogin(final UserName userName)
			throws AuthStorageException {
		try {
			storage.setLastLogin(userName, new Date());
		} catch (NoSuchUserException e) {
			throw new AuthStorageException(
					"Something is very broken. User should exist but doesn't: "
							+ e.getMessage(), e);
		}
	}

	/** Get the tokens associated with a user account associated with a possessed token.
	 * @param token a user token for the account in question.
	 * @return the tokens.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public TokenSet getTokens(final IncomingToken token)
			throws AuthStorageException, InvalidTokenException {
		final HashedToken ht = getToken(token);
		return new TokenSet(ht, storage.getTokens(ht.getUserName()));
	}

	/** Get the tokens associated with an arbitrary user account.
	 * 
	 * @param token a token for a user with the administrator role.
	 * @param userName the user name of the account.
	 * @return the tokens associated with the given account.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token does not
	 * have the administrator role.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public Set<HashedToken> getTokens(final IncomingToken token, final UserName userName)
			throws InvalidTokenException, UnauthorizedException, AuthStorageException {
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		getUser(token, Role.ADMIN); // force admin
		return storage.getTokens(userName);
	}

	// converts a no such token exception into an invalid token exception.
	/** Get details about a token.
	 * @param token the token in question.
	 * @return the token's details.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public HashedToken getToken(final IncomingToken token)
			throws AuthStorageException, InvalidTokenException {
		if (token == null) {
			throw new NullPointerException("token");
		}
		try {
			return storage.getToken(token.getHashedToken());
		} catch (NoSuchTokenException e) {
			throw new InvalidTokenException();
		}
	}

	/** Create a new developer or service token.
	 * @param token a token for the user that wishes to create a new token.
	 * @param tokenName a name for the token.
	 * @param serverToken whether the token should be a developer or server token.
	 * @return the new token.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws MissingParameterException If the name is missing.
	 * @throws InvalidTokenException if the provided token is not valid.
	 * @throws UnauthorizedException if the provided token is not a login token or the user does
	 * not have the role required to create the token type.
	 */
	public NewToken createToken(
			final IncomingToken token,
			final String tokenName,
			final boolean serverToken)
			throws AuthStorageException, MissingParameterException,
			InvalidTokenException, UnauthorizedException {
		checkString(tokenName, "token name"); //TODO CODE max token name size 100, make a Name class that handles the checking, removes control chars, etc and UserName & other classes inherit from
		final HashedToken t = getToken(token);
		if (!t.getTokenType().equals(TokenType.LOGIN)) {
			throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
					"Only login tokens may be used to create a token");
		}
		final AuthUser au = getUser(t);
		final Role reqRole = serverToken ? Role.SERV_TOKEN : Role.DEV_TOKEN;
		if (!reqRole.isSatisfiedBy(au.getRoles())) {
			throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
					"User %s is not authorized to create this token type.");
		}
		final long life;
		final AuthConfig c = cfg.getAppConfig();
		if (serverToken) {
			life = c.getTokenLifetimeMS(TokenLifetimeType.SERV);
		} else {
			life = c.getTokenLifetimeMS(TokenLifetimeType.DEV);
		}
		final NewToken nt = new NewToken(TokenType.EXTENDED_LIFETIME,
				tokenName, randGen.getToken(), au.getUserName(), life);
		storage.storeToken(nt.getHashedToken());
		return nt;
	}
	
	/** Get a user from an incoming token.
	 * @param token the token.
	 * @return the user.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws DisabledUserException if the user is disabled.
	 */
	public AuthUser getUser(final IncomingToken token)
			throws InvalidTokenException, AuthStorageException, DisabledUserException {
		try {
			return getUser(token, new Role[0]);
		} catch (DisabledUserException e) {
			throw e; // what the hell is the point of this?
		} catch (UnauthorizedException e) {
			throw new RuntimeException("Good job dude, you just broke reality", e);
		}
	}

	// requires the user to have at least one of the required roles
	private AuthUser getUser(
			final IncomingToken token,
			final Role ... required)
			throws AuthStorageException, InvalidTokenException, UnauthorizedException {
		return getUser(getToken(token), required);
	}

	// assumes hashed token is good
	// requires the user to have at least one of the required roles
	private AuthUser getUser(final HashedToken ht, final Role ... required)
			throws AuthStorageException, UnauthorizedException {
		final AuthUser u;
		try {
			u = storage.getUser(ht.getUserName());
		} catch (NoSuchUserException e) {
			throw new RuntimeException("There seems to be an error in the " +
					"storage system. Token was valid, but no user", e);
		}
		if (u.isDisabled()) {
			// apparently this disabled user still has some tokens, so kill 'em all
			storage.deleteTokens(ht.getUserName());
			throw new DisabledUserException("This account is disabled");
		}
		if (required.length > 0) {
			final Set<Role> has = u.getRoles().stream().flatMap(r -> r.included().stream())
					.collect(Collectors.toSet());
			//TODO CODE why Arrays.asList here? write tests and then remove
			has.retainAll(Arrays.asList(required)); // intersection
			if (has.isEmpty()) {
				throw new UnauthorizedException(ErrorType.UNAUTHORIZED);
			}
		}
		return u;
	}

	/** Get a restricted view of a user. Typically used for one user viewing another.
	 * @param token the token of the user requesting the view.
	 * @param user the username of the user to view.
	 * @return a view of the user.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws NoSuchUserException if there is no user corresponding to the given user name.
	 */
	public ViewableUser getUser(
			final IncomingToken token,
			final UserName user)
			throws AuthStorageException, InvalidTokenException,
			NoSuchUserException {
		final HashedToken ht = getToken(token);
		final AuthUser u = storage.getUser(user);
		if (ht.getUserName().equals(u.getUserName())) {
			return new ViewableUser(u, true);
		} else {
			return new ViewableUser(u, false);
		}
	}

	/** Look up display names for a set of user names. A maximum of 10000 users may be looked up
	 * at once.
	 * @param token a token for the user requesting the lookup.
	 * @param usernames the user names to look up.
	 * @return the display names for each user name. Any non-existent user names will be missing.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws IllegalParameterException if the number of requested user names is greater than the
	 * limit.
	 */
	public Map<UserName, DisplayName> getUserDisplayNames(
			final IncomingToken token,
			final Set<UserName> usernames)
			throws InvalidTokenException, AuthStorageException, IllegalParameterException {
		//TODO DISABLED don't return disabled users in user search (do return in search from admin)
		getToken(token); // just check the token is valid
		if (usernames.size() > MAX_RETURNED_USERS) {
			throw new IllegalParameterException(
					"User count exceeds maximum of " + MAX_RETURNED_USERS);
		}
		if (usernames.isEmpty()) {
			return new HashMap<>();
		}
		return storage.getUserDisplayNames(usernames);
	}
	
	/** Look up display names based on a search specification. A maximum of 10000 users will be
	 * returned.
	 * @param token a token for the user requesting the lookup.
	 * @param spec the search specification.
	 * @return the display names of the users.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user does not have the administrator, create
	 * administrator, or root role and a role search or prefix-less search is requested.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public Map<UserName, DisplayName> getUserDisplayNames(
			final IncomingToken token,
			final UserSearchSpec spec)
			throws InvalidTokenException, UnauthorizedException, AuthStorageException {
		if (spec == null) {
			throw new NullPointerException("spec");
		}
		//TODO DISABLED don't return disabled users in user search (do return in search from admin)
		final AuthUser user = getUser(token);
		if (!Role.ADMIN.isSatisfiedBy(user.getRoles())) {
			if (spec.isCustomRoleSearch() || spec.isRoleSearch()) {
				throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
						"Only admins may search on roles");
			}
			if (!spec.getSearchPrefix().isPresent()) {
				throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
						"Only admins may search without a prefix");
			}
		}
		return storage.getUserDisplayNames(spec, MAX_RETURNED_USERS, false);
	}
	
	/** Revoke a token.
	 * @param token the user's token.
	 * @param tokenID the id of the token to revoke.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws NoSuchTokenException if the user does not possess a token with the given id.
	 * @throws InvalidTokenException if the user's token is invalid.
	 */
	public void revokeToken(
			final IncomingToken token,
			final UUID tokenID)
			throws AuthStorageException,
			NoSuchTokenException, InvalidTokenException {
		if (tokenID == null){
			throw new NullPointerException("tokenID");
		}
		final HashedToken ht = getToken(token);
		storage.deleteToken(ht.getUserName(), tokenID);
	}

	/* maybe combine this with the above method...? The username is a good check that you're
	 * revoking the right token though.
	 */
	/**
	 * @param token a token for a user with the administrator role.
	 * @param userName the user name of the account for which a token will be revoked.
	 * @param tokenID the ID of the token to revoke.
	 * @throws InvalidTokenException if the admin token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token does not have
	 * the administrator role.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws NoSuchTokenException if there is no token for the specified user with the
	 * specified ID.
	 */
	public void revokeToken(
			final IncomingToken token,
			final UserName userName,
			final UUID tokenID)
			throws InvalidTokenException, UnauthorizedException, AuthStorageException,
			NoSuchTokenException {
		getUser(token, Role.ADMIN); // ensure admin
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		if (tokenID == null) {
			throw new NullPointerException("tokenID");
		}
		storage.deleteToken(userName, tokenID);
		
	}
	
	/** Revoke the current token. Returns an empty Optional if the token does not exist in the
	 * database, and thus there is nothing to revoke.
	 * @param token the token to be revoked.
	 * @return the revoked token, or an empty Optional.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public Optional<HashedToken> revokeToken(final IncomingToken token)
			throws AuthStorageException {
		if (token == null) {
			throw new NullPointerException("token");
		}
		HashedToken ht = null;
		try {
			ht = storage.getToken(token.getHashedToken());
			storage.deleteToken(ht.getUserName(), ht.getId());
			return Optional.of(ht);
		} catch (NoSuchTokenException e) {
			// no problem, continue
		}
		return Optional.absent();
	}

	/** Revokes all of a user's tokens.
	 * @param token the token for the user.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws InvalidTokenException if the token is invalid.
	 */
	public void revokeTokens(final IncomingToken token)
			throws AuthStorageException, InvalidTokenException {
		final HashedToken ht = getToken(token);
		storage.deleteTokens(ht.getUserName());
	}
	
	/** Revokes all tokens across all users, including the current user.
	 * @param token a token for a user with the administrator role.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token does not have
	 * the administrator role.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public void revokeAllTokens(final IncomingToken token)
			throws InvalidTokenException, UnauthorizedException, AuthStorageException {
		getUser(token, Role.ADMIN); // ensure admin
		storage.deleteTokens();
	}
	

	/** Revoke all of a user's tokens as an admin.
	 * @param token a token for a user with the administrator role.
	 * @param userName the name of the user account for which all tokens will be revoked.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token does not have
	 * the administrator role.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public void revokeAllTokens(final IncomingToken token, final UserName userName)
			throws InvalidTokenException, UnauthorizedException, AuthStorageException {
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		getUser(token, Role.ADMIN); // ensure admin
		storage.deleteTokens(userName);
	}

	/** Get a user as an admin.
	 * @param adminToken a token for a user with the administator, create administator, or root
	 * role.
	 * @param userName the name of the user account to get.
	 * @return the user.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws NoSuchUserException if no user exists with the given user name.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token does not have
	 * one of the required roles.
	 */
	public AuthUser getUserAsAdmin(
			final IncomingToken adminToken,
			final UserName userName)
			throws AuthStorageException, NoSuchUserException,
			InvalidTokenException, UnauthorizedException {
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		getUser(adminToken, Role.ROOT, Role.CREATE_ADMIN, Role.ADMIN);
		return storage.getUser(userName);
	}
	
	/** Remove roles from a user.
	 * @param token the user's token.
	 * @param removeRoles the roles to remove.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user is the root user.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public void removeRoles(final IncomingToken token, final Set<Role> removeRoles)
			throws InvalidTokenException, UnauthorizedException,
			AuthStorageException {
		final HashedToken ht = getToken(token);
		try {
			updateRoles(token, ht.getUserName(), Collections.emptySet(), removeRoles);
		} catch (NoSuchUserException e) {
			throw new AuthStorageException(String.format(
					"Token %s for user %s exists in the database, but no user record can be found",
					ht.getId(), ht.getUserName()));
		} catch (IllegalParameterException e) {
			throw new RuntimeException(
					"Reality appears to be broken. Please turn it off and then back on again");
		}
	}
	
	/** Update a user's roles. Any user can remove their own roles, but certain roles must be
	 * possessed to grant or remove certain other roles.
	 * 
	 * @see Role
	 * @param userToken the token of the user adding or removing roles.
	 * @param userName the user name of the user account for which roles are to be altered.
	 * @param addRoles the roles to add to the user account.
	 * @param removeRoles the roles to remove from the user account.
	 * @throws NoSuchUserException if there is no user account with the given name.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws UnauthorizedException if the user account associated with the token is not
	 * authorized to make the changes requested.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws IllegalParameterException if a role is to be both removed and added.
	 */
	public void updateRoles(
			final IncomingToken userToken,
			final UserName userName,
			final Set<Role> addRoles,
			final Set<Role> removeRoles)
			throws NoSuchUserException, AuthStorageException,
			UnauthorizedException, InvalidTokenException, IllegalParameterException {
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		if (addRoles == null) {
			throw new NullPointerException("addRoles");
		}
		if (removeRoles == null) {
			throw new NullPointerException("removeRoles");
		}
		Utils.noNulls(addRoles, "Null role in addRoles");
		Utils.noNulls(removeRoles, "Null role in removeRoles");
		
		final Set<Role> intersect = new HashSet<>(addRoles);
		intersect.retainAll(removeRoles);
		if (!intersect.isEmpty()) {
			throw new IllegalParameterException(
					"One or more roles is to be both removed and added: " +
					String.join(", ", rolesToDescriptions(intersect)));
		}
		if (userName.isRoot()) {
			throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
					"Cannot change ROOT roles");
		}
		final AuthUser actinguser = getUser(userToken);
		
		final Set<Role> add = new HashSet<>(addRoles);
		add.removeAll(actinguser.getGrantableRoles());
		final Set<Role> sub = new HashSet<>(removeRoles);
		sub.removeAll(actinguser.getGrantableRoles());
		
		if (!add.isEmpty()) {
			throwUnauthorizedToManageRoles("grant", add);
		}
		if (!sub.isEmpty() && !userName.equals(actinguser.getUserName())) {
			throwUnauthorizedToManageRoles("remove", sub);
		}
		storage.updateRoles(userName, addRoles, removeRoles);
	}

	private void throwUnauthorizedToManageRoles(final String action, final Set<Role> roles)
			throws UnauthorizedException {
		throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
				String.format("Not authorized to %s role(s): %s", action,
						String.join(", ", rolesToDescriptions(roles))));
	}

	private Set<String> rolesToDescriptions(final Set<Role> roles) {
		return roles.stream().map(r -> r.getDescription()).collect(Collectors.toSet());
	}

	/** Create or update a custom role.
	 * @param token a token for a user account with the administrator privilege.
	 * @param role the role to create, or if it already exists based on the ID, update.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws UnauthorizedException if the user account associated with the token does not have
	 * the administrator role.
	 * @throws InvalidTokenException if the token is invalid.
	 */
	public void setCustomRole(
			final IncomingToken token,
			final CustomRole role)
			throws AuthStorageException, InvalidTokenException, UnauthorizedException {
		if (role == null) {
			throw new NullPointerException("role");
		}
		getUser(token, Role.ADMIN);
		storage.setCustomRole(role);
	}
	
	/** Delete a custom role. Deletes the role from all users.
	 * @param token a token for a user account with the administrator privilege.
	 * @param roleId the id of the role.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws UnauthorizedException if the user account associated with the token does not have
	 * the administrator role.
	 * @throws NoSuchRoleException if there is no role by the given ID.
	 * @throws MissingParameterException if the role ID is missing.
	 * @throws IllegalParameterException if the role ID is illegal.
	 */
	public void deleteCustomRole(
			final IncomingToken token,
			final String roleId)
			throws InvalidTokenException, UnauthorizedException, AuthStorageException,
			NoSuchRoleException, MissingParameterException, IllegalParameterException {
		if (roleId == null || roleId.trim().isEmpty()) {
			throw new MissingParameterException("roleId cannot be null or empty");
		}
		getUser(token, Role.ADMIN); // ensure admin
		storage.deleteCustomRole(roleId);
	}

	/* may need to restrict to a subset of users in the future */
	/** Get all custom roles.
	 * @param token a user's token.
	 * @param forceAdmin ensure the user has the administrator, create administrator, or root role.
	 * @return the custom roles.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws UnauthorizedException if the user account associated with the token does not have
	 * an appropriate role and forceAdmin is true.
	 * @throws InvalidTokenException if the token is invalid.
	 */
	public Set<CustomRole> getCustomRoles(final IncomingToken token, final boolean forceAdmin)
			throws AuthStorageException, InvalidTokenException, UnauthorizedException {
		if (forceAdmin) {
			getUser(token, Role.ADMIN, Role.CREATE_ADMIN, Role.ROOT);
		} else {
			getToken(token); // check for valid token
		}
		return storage.getCustomRoles();
	}

	/** Update a user's custom roles.
	 * @param userToken a token for a user account with the administrator role.
	 * @param userName the name of the user for which the custom roles will be altered.
	 * @param addRoles the roles to add.
	 * @param removeRoles the roles to remove.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws UnauthorizedException if the user account associated with the token does not have
	 * the administrator role.
	 * @throws NoSuchUserException if there is no user account with the given name.
	 * @throws NoSuchRoleException if one of the roles does not exist in the database.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws IllegalParameterException if a role is to be both removed and added.
	 */
	public void updateCustomRoles(
			final IncomingToken userToken,
			final UserName userName,
			final Set<String> addRoles,
			final Set<String> removeRoles)
			throws AuthStorageException, NoSuchUserException, NoSuchRoleException,
			InvalidTokenException, UnauthorizedException, IllegalParameterException {
		// some of this code is similar to the updateRoles function, refactor?
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		if (addRoles == null) {
			throw new NullPointerException("addRoles");
		}
		if (removeRoles == null) {
			throw new NullPointerException("removeRoles");
		}
		Utils.noNulls(addRoles, "Null role in addRoles");
		Utils.noNulls(removeRoles, "Null role in removeRoles");
		
		final Set<String> intersect = new HashSet<>(addRoles);
		intersect.retainAll(removeRoles);
		if (!intersect.isEmpty()) {
			throw new IllegalParameterException(
					"One or more roles is to be both removed and added: " +
					String.join(", ", intersect));
		}
		/* for now don't allow users to remove their own custom roles, since admins may want
		 * to set roles for users that users can't change. However, there's no reason not to allow
		 * users to remove standard roles, which are privileges, not tags 
		 */
		getUser(userToken, Role.ADMIN);
		storage.updateCustomRoles(userName, addRoles, removeRoles);
	}

	/** Get an ordered list of the supported and enabled identity providers.
	 * @return the identity provider names.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public List<String> getIdentityProviders() throws AuthStorageException {
		final AuthConfig ac = cfg.getAppConfig();
		return idProviderSet.getProviders().stream()
				.filter(p -> ac.getProviderConfig(p).isEnabled())
				.collect(Collectors.toList());
	}
	
	private IdentityProvider getIdentityProvider(final String provider)
			throws NoSuchIdentityProviderException, AuthStorageException {
		final IdentityProvider ip = idProviderSet.getProvider(provider);
		if (!cfg.getAppConfig().getProviderConfig(provider).isEnabled()) {
			throw new NoSuchIdentityProviderException(provider);
		}
		return ip;
	}
	
	/** Get a redirection url for an identity provider.
	 * Applications should redirect to this url to initiate an OAuth2 flow.
	 * @param provider the name of the provider that will service the OAuth2 request.
	 * @param state the OAuth2 state variable to send with the request.
	 * @param link true if the action is a link request rather than a login request.
	 * @return the redirect url.
	 * @throws NoSuchIdentityProviderException if the provider does not exist or is not enabled.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public URL getIdentityProviderURL(
			final String provider,
			final String state,
			final boolean link)
			throws NoSuchIdentityProviderException, AuthStorageException {
		return getIdentityProvider(provider).getLoginURL(state, link);
	}

	/** Get a random token. This token is not persisted in the storage system.
	 * @return a token.
	 */
	public String getBareToken() {
		return randGen.getToken();
	}

	/** Continue the local portion of an OAuth2 login flow after redirection from a 3rd party
	 * identity provider.
	 * If the information returned from the identity provider allows the login to occur
	 * immediately, a login token is returned.
	 * If user interaction is required, a temporary token is returned that is associated with the
	 * state of the login request. This token can be used to retrieve the login state via
	 * {@link #getLoginState(IncomingToken)}.
	 * 
	 * The login state is not returned directly here because in most cases, this method will have
	 * been called after a redirect from a 3rd party identity provider, and as such, the login
	 * flow is not under the control of any UI elements at that point. Hence, if the login cannot
	 * proceed immediately, the state is stored so the user can be redirected to the appropriate
	 * UI element, which can then retrieve the login state and continue. A secondary point is that
	 * the URL will contain the identity provider authcode, and so the user should be redirected
	 * to a new URL that does not contain said authcode as soon as possible.
	 * 
	 * @param provider the name of the identity provider that is servicing the login request.
	 * @param authcode the authcode provided by the provider.
	 * @return either a login token or temporary token.
	 * @throws MissingParameterException if the authcode is missing.
	 * @throws IdentityRetrievalException if an error occurred when trying to retrieve identities
	 * from the provider.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws NoSuchIdentityProviderException if there is no provider by the given name.
	 */
	public LoginToken login(final String provider, final String authcode)
			throws MissingParameterException, IdentityRetrievalException,
			AuthStorageException, NoSuchIdentityProviderException {
		final IdentityProvider idp = getIdentityProvider(provider);
		if (authcode == null || authcode.trim().isEmpty()) {
			throw new MissingParameterException("authorization code");
		}
		final Set<RemoteIdentity> ids = idp.getIdentities(authcode, false);
		AuthUser lastUser = null;
		// check for uniqueness using names rather than the AuthUser class since it's possible
		// a role could be added or something between two retrievals
		final Set<UserName> names = new HashSet<>();
		final Set<RemoteIdentity> noUser = new HashSet<>();
		final Set<RemoteIdentityWithLocalID> hasUser = new HashSet<>();
		for (final RemoteIdentity id: ids) {
			final Optional<AuthUser> u = storage.getUser(id);
			if (u.isPresent()) {
				lastUser = u.get();
				hasUser.add(lastUser.getIdentity(id));
				names.add(lastUser.getUserName()); 
			} else {
				noUser.add(id);
			}
		}
		final LoginToken lr;
		if (names.size() == 1 && noUser.isEmpty()) {
			/* Don't throw an error here since an auth UI is not controlling the call in most
			 * cases - this call is almost certainly the result of a redirect from a 3rd party
			 * provider. Any controllable error should be thrown when the process flow is back
			 * under the control of the primary auth UI.
			 * 
			 * There's a tiny chance here that the user could be made an admin, be enabled, or
			 * non-admin login be enabled between this step and the getLoginState() step. The
			 * consequence is that they'll have to choose from one account in the next step,
			 * so who cares.
			 */
			if (!cfg.getAppConfig().isLoginAllowed() && !Role.isAdmin(lastUser.getRoles())) {
				lr = storeIdentitiesTemporarily(noUser, hasUser);
			} else if (lastUser.isDisabled()) {
				lr = storeIdentitiesTemporarily(noUser, hasUser);
			} else {
				lr = new LoginToken(login(lastUser.getUserName()));
			}
		} else {
			// store the identities so the user can create an account or choose from more than one
			// account
			lr = storeIdentitiesTemporarily(noUser, hasUser);
		}
		return lr;
	}

	private LoginToken storeIdentitiesTemporarily(
			final Set<RemoteIdentity> noUser,
			final Set<RemoteIdentityWithLocalID> hasUser)
			throws AuthStorageException {
		final Set<RemoteIdentityWithLocalID> store = noUser.stream()
				.map(id -> id.withID()).collect(Collectors.toSet());
		hasUser.stream().forEach(id -> store.add(id));

		final TemporaryToken tt = new TemporaryToken(randGen.getToken(), 30 * 60 * 1000);
		storage.storeIdentitiesTemporarily(tt.getHashedToken(), store);
		return new LoginToken(tt);
	}


	/** Get the current state of a login process associated with a temporary token.
	 * This method is expected to be called after {@link #login(String, String)}.
	 * After user interaction is completed, a new user can be created via
	 * {@link #createUser(IncomingToken, UUID, UserName, DisplayName, EmailAddress)} or the login
	 * can complete via {@link #login(IncomingToken, UUID)}.
	 * @param token the temporary token.
	 * @return the state of the login process.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws InvalidTokenException if the token is invalid.
	 */
	public LoginState getLoginState(final IncomingToken token)
			throws AuthStorageException, InvalidTokenException {
		final Set<RemoteIdentityWithLocalID> ids = getTemporaryIdentities(token);
		if (ids.isEmpty()) {
			throw new RuntimeException(
					"Programming error: temporary login token stored with no identities");
		}
		final String provider = ids.iterator().next().getRemoteID().getProvider();
		final LoginState.Builder builder = new LoginState.Builder(provider,
				cfg.getAppConfig().isLoginAllowed());
		for (final RemoteIdentityWithLocalID ri: ids) {
			final Optional<AuthUser> u = storage.getUser(ri);
			if (!u.isPresent()) {
				builder.withIdentity(ri);
			} else {
				/* there's a possibility of a race condition here:
				 * 1) temporary token gets created with a RI with a UUID
				 * 2) RI gets linked to a user and gets a new UUID
				 * 3) At this point in the code, the UUIDs will differ for the ri variable
				 * and the equivalent RI in the user
				 * So just use ri as is so the temporary token lookup works later 
				 */
				builder.withUser(u.get(), ri);
			}
		}
		return builder.build();
	}

	private Set<RemoteIdentityWithLocalID> getTemporaryIdentities(
			final IncomingToken token)
			throws AuthStorageException, InvalidTokenException {
		if (token == null) {
			throw new NullPointerException("token");
		}
		try {
			return storage.getTemporaryIdentities(token.getHashedToken());
		} catch (NoSuchTokenException e) {
			throw new InvalidTokenException();
		}
	}

	/** Create a new user linked to a 3rd party identity.
	 * 
	 * This method is expected to be called after {@link #getLoginState(IncomingToken)}.
	 * @param token a temporary token associated with the login process state.
	 * @param identityID the id of the identity to link to the new user. This identity must be
	 * included in the login state associated with the temporary token. 
	 * @param userName the user name for the new user.
	 * @param displayName the display name for the new user.
	 * @param email the email address for the new user.
	 * @return a new login token for the new user.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws AuthenticationException if the id is not included in the login state associated with
	 * the token.
	 * @throws UserExistsException if the user name is already in use.
	 * @throws UnauthorizedException if login is disabled or the username is the root user name.
	 * @throws IdentityLinkedException if the specified identity is already linked to a user.
	 */
	public NewToken createUser(
			final IncomingToken token,
			final UUID identityID,
			final UserName userName,
			final DisplayName displayName,
			final EmailAddress email)
			throws AuthStorageException, AuthenticationException,
				UserExistsException, UnauthorizedException, IdentityLinkedException {
		if (!cfg.getAppConfig().isLoginAllowed()) {
			throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
					"Account creation is disabled");
		}
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		if (userName.isRoot()) {
			throw new UnauthorizedException(ErrorType.UNAUTHORIZED, "Cannot create ROOT user");
		}
		if (displayName == null) {
			throw new NullPointerException("displayName");
		}
		if (email == null) {
			throw new NullPointerException("email");
		}
		final Optional<RemoteIdentityWithLocalID> match = getIdentity(token, identityID);
		if (!match.isPresent()) {
			throw new UnauthorizedException(ErrorType.UNAUTHORIZED, String.format(
					"Not authorized to create user with remote identity %s", identityID));
		}
		storage.createUser(new NewUser(userName, email, displayName, match.get(), new Date()));
		return login(userName);
	}

	/** Complete the OAuth2 login process.
	 * 
	 * This method is expected to be called after {@link #getLoginState(IncomingToken)}.
	 * @param token a temporary token associated with the login process state.
	 * @param identityID the id of the identity associated with the user account that is the login
	 * target. This identity must be included in the login state associated with the temporary
	 * token. 
	 * @return a new login token.
	 * @throws AuthenticationException if the id is not included in the login state associated with
	 * the token or there is no user associated with the remote identity.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws UnauthorizedException if the user is not an administrator and non-admin login
	 * is not allowed or the user account is disabled.
	 */
	public NewToken login(final IncomingToken token, final UUID identityID)
			throws AuthenticationException, AuthStorageException, UnauthorizedException {
		final Optional<RemoteIdentityWithLocalID> ri = getIdentity(token, identityID);
		if (!ri.isPresent()) {
			throw new UnauthorizedException(ErrorType.UNAUTHORIZED, String.format(
					"Not authorized to login to user with remote identity %s", identityID));
		}
		final Optional<AuthUser> u = storage.getUser(ri.get());
		if (!u.isPresent()) {
			// someone's trying to login to an account they haven't created yet
			// The UI shouldn't allow this, but they can always curl
			throw new AuthenticationException(ErrorType.AUTHENTICATION_FAILED,
					"There is no account linked to the provided identity ID");
		}
		if (!cfg.getAppConfig().isLoginAllowed() && !Role.isAdmin(u.get().getRoles())) {
			throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
					"Non-admin login is disabled");
		}
		if (u.get().isDisabled()) {
			throw new DisabledUserException("This account is disabled");
		}
		return login(u.get().getUserName());
	}
	
	private Optional<RemoteIdentityWithLocalID> getIdentity(
			final IncomingToken token,
			final UUID identityID)
			throws AuthStorageException, InvalidTokenException {
		if (identityID == null) {
			throw new NullPointerException("identityID");
		}
		final Set<RemoteIdentityWithLocalID> ids = getTemporaryIdentities(token);
		for (final RemoteIdentityWithLocalID ri: ids) {
			if (ri.getID().equals(identityID)) {
				return Optional.of(ri);
			}
		}
		return Optional.absent();
	}

	/** Get a currently available user name given a user name suggestion. Returns an empty Optional
	 * if a reasonable user name cannot be found.
	 * @param suggestedUserName the suggested user name.
	 * @return an available user name.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public Optional<UserName> getAvailableUserName(final String suggestedUserName)
			throws AuthStorageException {
		if (suggestedUserName == null) {
			throw new NullPointerException("suggestedUserName");
		}
		final UserName un = UserName.sanitizeName(suggestedUserName);
		if (un == null) {
			return getAvailableUserName(DEFAULT_SUGGESTED_USER_NAME, true);
		} else {
			return getAvailableUserName(un, false);
		}
	}
	
	private Optional<UserName> getAvailableUserName(
			final UserName suggestedUserName,
			final boolean forceNumericSuffix) throws AuthStorageException {
		
		final String sugName = suggestedUserName.getName();
		final String sugStrip = sugName.replaceAll("\\d*$", "");
		/* this might return a lot of names, but not super likely and it'd take a *lot* to cause
		 * problems. Make this smarter if necessary. E.g. could store username and numeric suffix
		 * db side and search and sort db side.
		 */
		final UserSearchSpec spec = UserSearchSpec.getBuilder()
				// checked that this does indeed use an index for the mongo implementation
				.withSearchPrefix("^" + Pattern.quote(sugStrip) + "\\d*$")
				.withSearchOnUserName(true).build();
		final Map<UserName, DisplayName> users = storage.getUserDisplayNames(spec, -1, true);
		boolean match = false;
		long largest = 0;
		for (final UserName d: users.keySet()) {
			final String lastName = d.getName();
			match = match || sugName.equals(lastName);
			final String num = lastName.replace(sugStrip, "");
			final long n = num.isEmpty() ? 1 : Long.parseLong(num);
			largest = n > largest ? n : largest;
		}
		final String newName;
		if (largest == 0 || !match) {
			final boolean hasNumSuffix = sugStrip.length() != sugName.length();
			if (!hasNumSuffix && forceNumericSuffix) {
				newName = sugStrip + (largest + 1);
			} else {
				newName = sugName;
			}
		} else {
			newName = sugStrip + (largest + 1);
		}
		if (newName.length() > UserName.MAX_NAME_LENGTH) {
			 // not worth trying to do something clever here, should never happen
			return Optional.absent();
		}
		try {
			return Optional.of(new UserName(newName));
		} catch (IllegalParameterException | MissingParameterException e) {
			throw new RuntimeException("this should be impossible", e);
		}
	}
	
	/** Continue the local portion of an OAuth2 link flow after redirection from a 3rd party
	 * identity provider.
	 * If the information returned from the identity provider allows the link to occur
	 * immediately, the account is linked to the remote identity immediately.
	 * 
	 * If user interaction is required, a temporary token is returned that is associated with the
	 * state of the link request. This token can be used to retrieve the link state via
	 * {@link #getLinkState(IncomingToken, IncomingToken)}.
	 * 
	 * The link state is not returned directly here because in most cases, this method will have
	 * been called after a redirect from a 3rd party identity provider, and as such, the link
	 * flow is not under the control of any UI elements at that point. Hence, if the link cannot
	 * proceed immediately, the state is stored so the user can be redirected to the appropriate
	 * UI element, which can then retrieve the link state and continue. A secondary point is that
	 * the URL will contain the identity provider authcode, and so the user should be redirected
	 * to a new URL that does not contain said authcode as soon as possible.
	 * 
	 * @param token the user's token.
	 * @param provider the name of the identity provider that is servicing the link request.
	 * @param authcode the authcode provided by the provider.
	 * @return a temporary token if required.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws MissingParameterException if the authcode is missing.
	 * @throws IdentityRetrievalException if an error occurred when trying to retrieve identities
	 * from the provider.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws NoSuchIdentityProviderException if there is no provider by the given name.
	 * @throws LinkFailedException if the link was unsuccessful.
	 * @throws DisabledUserException if the user account is disabled.
	 */
	public LinkToken link(
			final IncomingToken token,
			final String provider,
			final String authcode)
			throws InvalidTokenException, AuthStorageException,
			MissingParameterException, IdentityRetrievalException,
			LinkFailedException, NoSuchIdentityProviderException, DisabledUserException {
		final AuthUser u = getUser(token); // UI shouldn't allow disabled users to link
		if (u.isLocal()) {
			// the ui shouldn't allow local users to link accounts, so ok to throw this
			throw new LinkFailedException("Cannot link identities to local accounts");
		}
		final IdentityProvider idp = getIdentityProvider(provider);
		if (authcode == null || authcode.trim().isEmpty()) {
			throw new MissingParameterException("authorization code");
		}
		final Set<RemoteIdentity> ids = idp.getIdentities(authcode, true);
		filterLinkCandidates(ids);
		/* Don't throw an error if ids are empty since an auth UI is not controlling the call in
		 * most cases - this call is almost certainly the result of a redirect from a 3rd party
		 * provider. Any controllable error should be thrown when the process flow is back
		 * under the control of the primary auth UI.
		 */
		final LinkToken lt;
		final ProviderConfig pc = cfg.getAppConfig().getProviderConfig(provider);
		if (ids.size() == 1 && !pc.isForceLinkChoice()) {
			try {
				/* throws link failed exception if u is a local user or the id is already linked
				 * Since we already checked those, only a rare race condition can cause a link
				 * failed exception, and the consequence is trivial so don't worry about it
				 */
				storage.link(u.getUserName(), ids.iterator().next().withID());
			} catch (NoSuchUserException e) {
				throw new AuthStorageException(
						"User unexpectedly disappeared from the database", e);
			}
			lt = new LinkToken();
		} else { // will store an ID set if said set is empty.
			final TemporaryToken tt = new TemporaryToken(randGen.getToken(),
					10 * 60 * 1000);
			storage.storeIdentitiesTemporarily(tt.getHashedToken(),
					ids.stream().map(r -> r.withID())
					.collect(Collectors.toSet()));
			lt = new LinkToken(tt);
		}
		return lt;
	}

	private void filterLinkCandidates(final Set<? extends RemoteIdentity> rids)
			throws AuthStorageException {
		final Iterator<? extends RemoteIdentity> iter = rids.iterator();
		while (iter.hasNext()) {
			if (storage.getUser(iter.next()).isPresent()) {
				iter.remove();
			}
		}
	}
	
	/** Get the current state of a linking process associated with a temporary token.
	 * This method is expected to be called after {@link #link(IncomingToken, String, String)}.
	 * After user interaction is completed, the link can be completed by calling
	 * {@link #link(IncomingToken, IncomingToken, UUID)}.
	 * 
	 * @param token the user's token.
	 * @param linktoken the temporary token associated with the link state.
	 * @return the state of the linking process.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws InvalidTokenException if either token is invalid.
	 * @throws LinkFailedException if the user is a local user or there are no available identities
	 * to link.
	 * @throws DisabledUserException if the user is disabled.
	 */
	public LinkIdentities getLinkState(
			final IncomingToken token,
			final IncomingToken linktoken)
			throws InvalidTokenException, AuthStorageException,
			LinkFailedException, DisabledUserException {
		final AuthUser u = getUser(token);
		if (u.isLocal()) {
			throw new LinkFailedException("Cannot link identities to local accounts");
		}
		final Set<RemoteIdentityWithLocalID> ids = getTemporaryIdentities(linktoken);
		filterLinkCandidates(ids);
		if (ids.isEmpty()) {
			throw new LinkFailedException("All provided identities are already linked");
		}
		return new LinkIdentities(u, ids);
	}

	/** Complete the OAuth2 account linking process.
	 * 
	 * This method is expected to be called after
	 * {@link #getLinkState(IncomingToken, IncomingToken)}.
	 * 
	 * @param token the user's token.
	 * @param linktoken a temporary token associated with the link process state.
	 * @param identityID the id of the remote identity to link to the user's account. This identity
	 * must be included in the link state associated with the temporary token. 
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws LinkFailedException if the identity is already linked, the id is not included in
	 * the link state associated with the temporary token, or the user is a local user.
	 * @throws DisabledUserException if the user is disabled.
	 * @throws InvalidTokenException if either token is invalid.
	 */
	public void link(
			final IncomingToken token,
			final IncomingToken linktoken,
			final UUID identityID)
			throws AuthStorageException, LinkFailedException, DisabledUserException,
			InvalidTokenException {
		final AuthUser au = getUser(token);
		final Optional<RemoteIdentityWithLocalID> ri = getIdentity(linktoken, identityID);
		if (!ri.isPresent()) {
			throw new LinkFailedException(String.format("Not authorized to link identity %s",
					identityID));
		}
		try {
			storage.link(au.getUserName(), ri.get());
		} catch (NoSuchUserException e) {
			throw new AuthStorageException("User magically disappeared from database: " +
					au.getUserName().getName());
		}
	}

	/** Remove a remote identity from a user account.
	 * @param token the user's token.
	 * @param id the ID of the remote identity to remove.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws UnLinkFailedException if the user has only one identity or does not possess the
	 * specified identity.
	 * @throws DisabledUserException if the user is disabled.
	 */
	public void unlink(
			final IncomingToken token,
			final UUID id)
			throws InvalidTokenException, AuthStorageException,
			UnLinkFailedException, DisabledUserException {
		if (id == null) {
			throw new NullPointerException("id");
		}
		final AuthUser au = getUser(token);
		try {
			storage.unlink(au.getUserName(), id);
		} catch (NoSuchUserException e) {
			throw new AuthStorageException("User magically disappeared from database: " +
					au.getUserName().getName());
		}
		
	}

	/** Update a user's details.
	 * @param token the user's token.
	 * @param update the update.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 */
	public void updateUser(
			final IncomingToken token,
			final UserUpdate update)
			throws InvalidTokenException, AuthStorageException {
		if (update == null) {
			throw new NullPointerException("update");
		}
		if (!update.hasUpdates()) {
			return; //noop
		}
		final HashedToken ht = getToken(token);
		try {
			storage.updateUser(ht.getUserName(), update);
		} catch (NoSuchUserException e) {
			throw new AuthStorageException("Token without a user: " +
					ht.getId());
		}
	}

	/** Disable or enable an account.
	 * @param token a token for a user with the administrator, create administrator, or root
	 * role.
	 * @param user the user name of the account to disable.
	 * @param disable true to disable the account, false to enable it.
	 * @param reason the reason the account was disabled. Ignored if enable is true.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token doesn't have
	 * the appropriate role, or if any user other than the root user attempts to disable the root
	 * account, or any user attempts to enable the root account.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws IllegalParameterException if a disable reason is not provided.
	 * @throws NoSuchUserException if the specified user doesn't exist.
	 */
	public void disableAccount(
			final IncomingToken token,
			final UserName user,
			final boolean disable,
			final String reason)
			throws InvalidTokenException, UnauthorizedException, AuthStorageException,
			IllegalParameterException, NoSuchUserException {
		final AuthUser admin = getUser(token, Role.ROOT, Role.CREATE_ADMIN, Role.ADMIN);
		if (user == null) {
			throw new NullPointerException("user");
		}
		if (disable) {
			if (user.isRoot() && !admin.isRoot()) {
				throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
						"Only the root user can disable the root account");
			}
			if (reason == null || reason.trim().isEmpty()) {
				throw new IllegalParameterException(
						"Must provide a reason why the account was disabled");
			}
			storage.deleteTokens(user); //not really necessary but doesn't hurt
			storage.disableAccount(user, admin.getUserName(), reason);
			/* there's a tiny chance a login could be in process right now and have have passed the
			 * disabled check, and then have the token created after the next line, but that's
			 * so improbable I'm not going to worry about it
			 * The getUser method checks to see if a user is disabled if if so deletes their tokens
			 * as well as a backup
			 */
			storage.deleteTokens(user);
		} else {
			if (user.isRoot()) {
				throw new UnauthorizedException(ErrorType.UNAUTHORIZED,
						"The root user cannot be enabled from the UI");
			}
			storage.enableAccount(user, admin.getUserName());
		}
		
	}
	
	/** Update the server configuration.
	 * @param token a token for a user with the administrator role.
	 * @param acs the new server configuration.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token doesn't have
	 * the appropriate role.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws NoSuchIdentityProviderException if a provider specified in the new configuration
	 * is not supported by this authentication instance.
	 */
	public <T extends ExternalConfig> void updateConfig(
			final IncomingToken token,
			final AuthConfigSet<T> acs)
			throws InvalidTokenException, UnauthorizedException,
			AuthStorageException, NoSuchIdentityProviderException {
		if (acs == null) {
			throw new NullPointerException("acs");
		}
		getUser(token, Role.ADMIN);
		for (final String provider: acs.getCfg().getProviders().keySet()) {
			//throws an exception if no provider by given name
			idProviderSet.getProvider(provider);
		}
		storage.updateConfig(acs, true);
		cfg.updateConfig();
	}
	
	/** Gets the authentication configuration.
	 * @param token a token for a user with the administrator role.
	 * @param mapper a mapper for the external configuration.
	 * @return the authentication configuration.
	 * @throws InvalidTokenException if the token is invalid.
	 * @throws UnauthorizedException if the user account associated with the token doesn't have
	 * the appropriate role.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws ExternalConfigMappingException if the mapper failed to map the external
	 * configuration into a class.
	 */
	public <T extends ExternalConfig> AuthConfigSet<T> getConfig(
			final IncomingToken token,
			final ExternalConfigMapper<T> mapper)
			throws InvalidTokenException, UnauthorizedException,
			AuthStorageException, ExternalConfigMappingException {
		getUser(token, Role.ADMIN);
		if (mapper == null) {
			throw new NullPointerException("mapper");
		}
		final AuthConfigSet<CollectingExternalConfig> acs = cfg.getConfig();
		return new AuthConfigSet<T>(acs.getCfg(),
				mapper.fromMap(acs.getExtcfg().toMap()));
	}
	
	/** Returns the suggested cache time for tokens.
	 * @return the suggested cache time.
	 * @throws AuthStorageException if an error occurred accessing the storage system. 
	 */
	public long getSuggestedTokenCacheTime() throws AuthStorageException {
		return cfg.getAppConfig().getTokenLifetimeMS(TokenLifetimeType.EXT_CACHE);
	}
	
	/** Get the external configuration without providing any credentials.
	 * 
	 * This method should not be exposed in a public API.
	 * 
	 * @param mapper a mapper for the external configuration.
	 * @return the external configuration.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws ExternalConfigMappingException if the mapper failed to map the external
	 * configuration into a class.
	 */
	public <T extends ExternalConfig> T getExternalConfig(
			final ExternalConfigMapper<T> mapper)
			throws AuthStorageException, ExternalConfigMappingException {
		if (mapper == null) {
			throw new NullPointerException("mapper");
		}
		final AuthConfigSet<CollectingExternalConfig> acs = cfg.getConfig();
		return mapper.fromMap(acs.getExtcfg().toMap());
	}

	/** Imports a user from an external service without requiring credentials.
	 * 
	 * Do not exposed this method in a public API.
	 * 
	 * @param userName the user name for the user.
	 * @param ri the remote identity to link to the new user.
	 * @throws UserExistsException if the user name is already in use.
	 * @throws AuthStorageException if an error occurred accessing the storage system.
	 * @throws IdentityLinkedException if the identity is already linked to a user.
	 */
	public void importUser(final UserName userName, final RemoteIdentity ri)
			throws UserExistsException, AuthStorageException, IdentityLinkedException {
		if (ri == null) {
			throw new NullPointerException("ri");
		}
		if (userName == null) {
			throw new NullPointerException("userName");
		}
		DisplayName dn;
		try { // hacky, but eh. Python guys will like it though
			dn = new DisplayName(ri.getDetails().getFullname());
		} catch (IllegalParameterException | MissingParameterException e) {
			dn = UNKNOWN_DISPLAY_NAME;
		}
		EmailAddress email;
		try {
			email = new EmailAddress(ri.getDetails().getEmail());
		} catch (IllegalParameterException | MissingParameterException e) {
			email = EmailAddress.UNKNOWN;
		}
		storage.createUser(new NewUser(userName, email, dn, ri.withID(), null));
	}
}
