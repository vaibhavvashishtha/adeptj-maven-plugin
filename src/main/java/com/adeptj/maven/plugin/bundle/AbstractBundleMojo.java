/*
###############################################################################
#                                                                             #
#    Copyright 2016, AdeptJ (http://www.adeptj.com)                           #
#                                                                             #
#    Licensed under the Apache License, Version 2.0 (the "License");          #
#    you may not use this file except in compliance with the License.         #
#    You may obtain a copy of the License at                                  #
#                                                                             #
#        http://www.apache.org/licenses/LICENSE-2.0                           #
#                                                                             #
#    Unless required by applicable law or agreed to in writing, software      #
#    distributed under the License is distributed on an "AS IS" BASIS,        #
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. #
#    See the License for the specific language governing permissions and      #
#    limitations under the License.                                           #
#                                                                             #
###############################################################################
*/

package com.adeptj.maven.plugin.bundle;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.http.Header;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static com.adeptj.maven.plugin.bundle.Constants.BUNDLE_NAME;
import static com.adeptj.maven.plugin.bundle.Constants.BUNDLE_SYMBOLICNAME;
import static com.adeptj.maven.plugin.bundle.Constants.BUNDLE_VERSION;
import static com.adeptj.maven.plugin.bundle.Constants.DEFAULT_AUTH_URL;
import static com.adeptj.maven.plugin.bundle.Constants.DEFAULT_CONSOLE_URL;
import static com.adeptj.maven.plugin.bundle.Constants.DEFAULT_LOGOUT_URL;
import static com.adeptj.maven.plugin.bundle.Constants.HEADER_JSESSIONID;
import static com.adeptj.maven.plugin.bundle.Constants.HEADER_SET_COOKIE;
import static com.adeptj.maven.plugin.bundle.Constants.J_PASSWORD;
import static com.adeptj.maven.plugin.bundle.Constants.J_USERNAME;
import static com.adeptj.maven.plugin.bundle.Constants.REGEX_EQ;
import static com.adeptj.maven.plugin.bundle.Constants.REGEX_SEMI_COLON;
import static com.adeptj.maven.plugin.bundle.Constants.UTF_8;
import static com.adeptj.maven.plugin.bundle.Constants.VALUE_TRUE;
import static org.apache.http.HttpStatus.SC_OK;

/**
 * Base for various bundle mojo implementations.
 *
 * @author Rakesh.Kumar, AdeptJ
 */
abstract class AbstractBundleMojo extends AbstractMojo {

    private static final String UNINSTALL_MSG = "Uninstalling Bundle [%s (%s), version: %s]";

    private static final String INSTALL_MSG = "Installing Bundle [%s (%s), version: %s]";

    @Parameter(property = "adeptj.console.url", defaultValue = DEFAULT_CONSOLE_URL, required = true)
    String adeptjConsoleURL;

    @Parameter(property = "adeptj.auth.url", defaultValue = DEFAULT_AUTH_URL, required = true)
    private String authUrl;

    @Parameter(property = "adeptj.logout.url", defaultValue = DEFAULT_LOGOUT_URL)
    private String logoutUrl;

    @Parameter(property = "adeptj.user", defaultValue = "admin", required = true)
    private String user;

    @Parameter(property = "adeptj.password", defaultValue = "admin", required = true)
    private String password;

    @Parameter(property = "adeptj.failOnError", defaultValue = VALUE_TRUE, required = true)
    boolean failOnError;

    private boolean loginSucceeded;

    CloseableHttpClient httpClient;

    boolean login() throws IOException {
        if (this.httpClient == null) {
            this.httpClient = HttpClients.createDefault();
            this.getLog().info("HttpClient initialized!!");
        }
        List<NameValuePair> authForm = new ArrayList<>();
        authForm.add(new BasicNameValuePair(J_USERNAME, this.user));
        authForm.add(new BasicNameValuePair(J_PASSWORD, this.password));
        try (CloseableHttpResponse authResponse = this.httpClient.execute(RequestBuilder.post(this.authUrl)
                .setEntity(new UrlEncodedFormEntity(authForm, UTF_8))
                .build())) {
            return this.isSessionIdPresentInResponse(authResponse);
        }
    }

    void logout() {
        if (this.loginSucceeded) {
            this.getLog().info("Invoking Logout!!");
            try (CloseableHttpResponse response = this.httpClient.execute(RequestBuilder.get(this.logoutUrl).build())) {
                if (response.getStatusLine().getStatusCode() == SC_OK) {
                    this.getLog().info("Logout successful!!");
                } else {
                    this.getLog().info("Logout failed!!");
                }
            } catch (IOException ex) {
                this.getLog().error(ex);
            }
        }
    }

    void closeHttpClient() {
        if (this.httpClient != null) {
            try {
                this.httpClient.close();
                this.getLog().info("HttpClient closed!!");
            } catch (IOException ex) {
                this.getLog().error(ex);
            }
        }
    }

    void logBundleDetails(BundleDTO dto, BundleMojoOp op) {
        switch (op) {
            case INSTALL:
                this.getLog().info("Installing " + dto);
                break;
            case UNINSTALL:
                this.getLog().info("Uninstalling " + dto);
                break;
        }
    }

    BundleDTO getBundleDTO(File bundle) throws IOException {
        try (JarFile bundleArchive = new JarFile(bundle)) {
            Attributes mainAttributes = bundleArchive.getManifest().getMainAttributes();
            String bundleName = mainAttributes.getValue(BUNDLE_NAME);
            Validate.isTrue(StringUtils.isNotEmpty(bundleName), "Artifact is not a Bundle!!");
            String bsn = mainAttributes.getValue(BUNDLE_SYMBOLICNAME);
            Validate.isTrue(StringUtils.isNotEmpty(bsn), "Bundle symbolic name is blank!!");
            String bundleVersion = mainAttributes.getValue(BUNDLE_VERSION);
            return new BundleDTO(bundleName, bsn, bundleVersion);
        }
    }

    private boolean isSessionIdPresentInResponse(CloseableHttpResponse authResponse) {
        String sessionId = null;
        for (Header header : authResponse.getAllHeaders()) {
            String headerName = header.getName();
            if (HEADER_SET_COOKIE.equals(headerName)) {
                for (String part : header.getValue().split(REGEX_SEMI_COLON)) {
                    if (part.startsWith(HEADER_JSESSIONID)) {
                        sessionId = part.split(REGEX_EQ)[1];
                        this.getLog().info("AdeptJ SessionId from [SET-COOKIE] header: " + sessionId);
                        break;
                    }
                }
            } else if (HEADER_JSESSIONID.equals(headerName)) {
                sessionId = header.getValue();
                this.getLog().info("AdeptJ SessionId from [JSESSIONID] header: " + sessionId);
                break;
            }
        }
        if (StringUtils.isNotEmpty(sessionId)) {
            this.loginSucceeded = true;
            this.getLog().info("Login succeeded!!");
        }
        return this.loginSucceeded;
    }
}
