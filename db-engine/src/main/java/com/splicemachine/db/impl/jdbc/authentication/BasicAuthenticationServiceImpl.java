/*
 * This file is part of Splice Machine.
 * Splice Machine is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3, or (at your option) any later version.
 * Splice Machine is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details.
 * You should have received a copy of the GNU Affero General Public License along with Splice Machine.
 * If not, see <http://www.gnu.org/licenses/>.
 *
 * Some parts of this source code are based on Apache Derby, and the following notices apply to
 * Apache Derby:
 *
 * Apache Derby is a subproject of the Apache DB project, and is licensed under
 * the Apache License, Version 2.0 (the "License"); you may not use these files
 * except in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Splice Machine, Inc. has modified the Apache Derby code in this file.
 *
 * All such Splice Machine modifications are Copyright 2012 - 2020 Splice Machine, Inc.,
 * and are licensed to you under the GNU Affero General Public License.
 */

package com.splicemachine.db.impl.jdbc.authentication;

import com.splicemachine.db.iapi.reference.Property;
import com.splicemachine.db.iapi.reference.PropertyHelper;
import com.splicemachine.db.iapi.sql.dictionary.PasswordHasher;
import com.splicemachine.db.iapi.reference.Attribute;
import com.splicemachine.db.iapi.reference.SQLState;
import com.splicemachine.db.authentication.UserAuthenticator;
import com.splicemachine.db.iapi.services.property.PropertyUtil;
import com.splicemachine.db.iapi.services.monitor.Monitor;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.util.StringUtil;
import com.splicemachine.db.impl.jdbc.Util;

import java.util.Properties;
// security imports - for SHA-1 digest
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;

/**
 * This authentication service is the basic Derby user authentication
 * level support.
 *
 * It is activated upon setting db.authentication.provider database
 * or system property to 'BUILTIN'.
 * <p>
 * It instantiates & calls the basic User authentication scheme at runtime.
 * <p>
 * In 2.0, users can now be defined as database properties.
 * If db.database.propertiesOnly is set to true, then in this
 * case, only users defined as database properties for the current database
 * will be considered.
 *
 */
public final class BasicAuthenticationServiceImpl
	extends AuthenticationServiceBase implements UserAuthenticator {

	//
	// ModuleControl implementation (overriden)
	//

	/**
	 *  Check if we should activate this authentication service.
	 */
	public boolean canSupport(Properties properties) {

		if (!requireAuthentication(properties))
			return false;

		//
		// We check 2 System/Database properties:
		//
		//
		// - if db.authentication.provider is set to 'BUILTIN'.
		//
		// and in that case we are the authentication service that should
		// be run.
		//

		String authenticationProvider = PropertyUtil.getPropertyFromSet(
					properties,
					Property.AUTHENTICATION_PROVIDER_PARAMETER);

        return !((authenticationProvider != null) &&
                (!authenticationProvider.isEmpty()) &&
                (!(StringUtil.SQLEqualsIgnoreCase(authenticationProvider,
                        PropertyHelper.AUTHENTICATION_PROVIDER_BUILTIN))));
	}

	/**
	 * @see com.splicemachine.db.iapi.services.monitor.ModuleControl#boot
	 * @exception StandardException upon failure to load/boot the expected
	 * authentication service.
	 */
	public void boot(boolean create, Properties properties)
	  throws StandardException {

		// We need authentication
		// setAuthentication(true);

		// we call the super in case there is anything to get initialized.
		super.boot(create, properties);

		// Initialize the MessageDigest class engine here
		// (we don't need to do that ideally, but there is some
		// overhead the first time it is instantiated.
		// SHA-1 is expected in jdk 1.1x and jdk1.2
		// This is a standard name: check,
		// http://java.sun.com/products/jdk/1.{1,2}
		//					/docs/guide/security/CryptoSpec.html#AppA 
		try {
			MessageDigest digestAlgorithm = MessageDigest.getInstance("SHA-1");
			digestAlgorithm.reset();

		} catch (NoSuchAlgorithmException nsae) {
			throw Monitor.exceptionStartingModule(nsae);
		}

		// Set ourselves as being ready and loading the proper
		// authentication scheme for this service
		//
		this.setAuthenticationService(this);
	}

	/*
	** UserAuthenticator methods.
	*/

	/**
	 * Authenticate the passed-in user's credentials.
	 *
	 * @param userName		The user's name used to connect to JBMS system
	 * @param userPassword	The user's password used to connect to JBMS system
	 * @param databaseName	The database which the user wants to connect to.
	 * @param info			Additional jdbc connection info.
	 */
	public String	authenticateUser(String userName,
								 String userPassword,
								 String databaseName,
								 Properties info
									)
            throws SQLException
	{
        // Client security mechanism if any specified
        // Note: Right now it is only used to handle clients authenticating
        // via DRDA SECMEC_USRSSBPWD mechanism
        String clientSecurityMechanism = null;
        // Client security mechanism (if any) short representation
        // Default value is none.
        int secMec = 0;

		// let's check if the user has been defined as a valid user of the
		// JBMS system.
		// We expect to find and match a System property corresponding to the
		// credentials passed-in.
		//
		if (userName == null)
			// We don't tolerate 'guest' user for now.
			return null;

		String definedUserPassword = null, passedUserPassword = null;

        // If a security mechanism is specified as part of the connection
        // properties, it indicates that we've to account as far as how the
        // password is presented to us - in the case of SECMEC_USRSSBPWD
        // (only expected one at the moment), the password is a substitute
        // one which has already been hashed differently than what we store
        // at the database level (for instance) - this will influence how we
        // assess the substitute password to be legitimate for Derby's
        // BUILTIN authentication scheme/provider.
        if ((clientSecurityMechanism =
                info.getProperty(Attribute.DRDA_SECMEC)) != null)
        {
            secMec = Integer.parseInt(clientSecurityMechanism);
        }

		//
		// Check if user has been defined at the database or/and
		// system level. The user (administrator) can configure it the
		// way he/she wants (as well as forcing users properties to
		// be retrieved at the datbase level only).
		//
        String userNameProperty =
                Property.USER_PROPERTY_PREFIX + userName;

		// check if user defined at the database level
		definedUserPassword = getDatabaseProperty(userNameProperty);

        if (definedUserPassword != null)
        {
            if (secMec != SECMEC_USRSSBPWD)
            {
                // hash passed-in password
                try {
                    passedUserPassword = hashPasswordUsingStoredAlgorithm(
                            userName, userPassword, definedUserPassword);
                } catch (StandardException se) {
                    // The UserAuthenticator interface does not allow us to
                    // throw a StandardException, so convert to SQLException.
                    throw Util.generateCsSQLException(se);
                }
            }
            else
            {
                // Dealing with a client SECMEC - password checking is
                // slightly different and we need to generate a
                // password substitute to compare with the substitute
                // generated one from the client.
                definedUserPassword = substitutePassword(userName,
                                                         definedUserPassword,
                                                         info, true);
                // As SecMec is SECMEC_USRSSBPWD, expected passed-in password
                // to be HexString'ified already
                passedUserPassword = userPassword;
            }
        }
        else
        {
            // DERBY-5539: Generate a hashed token even if the user is not
            // defined at the database level (that is, the user is defined at
            // the system level or does not exist at all). If we don't do that,
            // authentication failures would take less time for non-existing
            // users than they would for existing users, since generating the
            // hashed token is a relatively expensive operation. Attackers
            // could use this to determine if a user exists. By generating the
            // hashed token also for non-existing users, authentication
            // failures will take the same time for existing and non-existing
            // users, and it will be more difficult for attackers to tell the
            // difference.
            try {
                Properties props = getDatabaseProperties();
                if (props != null) {
                    hashUsingDefaultAlgorithm(
                            userName, userPassword, props);
                }
            } catch (StandardException se) {
                throw Util.generateCsSQLException(se);
            }

            // check if user defined at the system level
            definedUserPassword = getSystemProperty(userNameProperty);
            passedUserPassword = userPassword;

            if ((definedUserPassword != null) &&
                (secMec == SECMEC_USRSSBPWD))
            {
                // Dealing with a client SECMEC - see above comments
                definedUserPassword = substitutePassword(userName,
                                                         definedUserPassword,
                                                         info, false);
            }
        }

        // Check if the passwords match.
		// NOTE: We do not look at the passed-in database name value as
		// we rely on the authorization service that was put in
		// in 2.0 . (if a database name was passed-in)
        boolean passwordsMatch =
                (definedUserPassword != null) &&
                definedUserPassword.equals(passedUserPassword);

        // Provide extra information on mismatch if strong password
        // substitution is used, since the problem may be that the stored
        // password was stored using the configurable hash authentication
        // scheme which is incompatible with strong password substitution.
        if (!passwordsMatch && secMec == SECMEC_USRSSBPWD) {
            throw Util.generateCsSQLException(
                    SQLState.NET_CONNECT_SECMEC_INCOMPATIBLE_SCHEME);
        }

        return (passwordsMatch ? userName : null);
	}

    /**
     * Hash a password using the same algorithm as we used to generate the
     * stored password token.
     *
     * @param user the user whose password to hash
     * @param password the plaintext password
     * @param storedPassword the password token that's stored in the database
     * @return a digest of the password created the same way as the stored
     *         password
     * @throws StandardException if the password cannot be hashed with the
     *         requested algorithm
     */
    private String hashPasswordUsingStoredAlgorithm(
                String user, String password, String storedPassword)
            throws StandardException
    {
        if (storedPassword.startsWith( PasswordHasher.ID_PATTERN_SHA1_SCHEME )) {
            return hashPasswordSHA1Scheme(password);
        }
        else
        {
            PasswordHasher  hasher = new PasswordHasher( storedPassword );

            return hasher.hashAndEncode( user, password );
        }
    }
}
