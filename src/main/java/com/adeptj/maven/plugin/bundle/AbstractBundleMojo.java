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
 * AbstractBundleMojo
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

    private CloseableHttpClient httpClient;

    AbstractBundleMojo() {
        this.httpClient = HttpClients.createDefault();
    }

    CloseableHttpClient getHttpClient() {
        return this.httpClient;
    }

    boolean login(CloseableHttpClient httpClient) throws IOException {
        List<NameValuePair> authForm = new ArrayList<>();
        authForm.add(new BasicNameValuePair(J_USERNAME, this.user));
        authForm.add(new BasicNameValuePair(J_PASSWORD, this.password));
        try (CloseableHttpResponse authResponse = httpClient.execute(RequestBuilder.post(this.authUrl)
                .setEntity(new UrlEncodedFormEntity(authForm, UTF_8))
                .build())) {
            return this.parseResponse(authResponse);
        }
    }

    void logout(CloseableHttpClient httpClient) {
        this.getLog().info("Invoking Logout!!");
        try (CloseableHttpResponse response = httpClient.execute(RequestBuilder.get(this.logoutUrl).build())) {
            if (response.getStatusLine().getStatusCode() == SC_OK) {
                this.getLog().info("Logout successful!!");
            } else {
                this.getLog().info("Logout failed!!");
            }
        } catch (IOException ex) {
            this.getLog().error(ex);
        }
    }

    void close(CloseableHttpClient httpClient) {
        try {
            httpClient.close();
        } catch (IOException ex) {
            this.getLog().error(ex);
        }
    }

    String getBundleSymbolicName(File bundle, BundleMojoOp op) {
        String bsn = null;
        try (JarFile jarFile = new JarFile(bundle)) {
            Attributes mainAttributes = jarFile.getManifest().getMainAttributes();
            String bundleName = mainAttributes.getValue(BUNDLE_NAME);
            if (bundleName == null || bundleName.isEmpty()) {
                throw new IllegalStateException("Artifact is not a Bundle!!");
            }
            bsn = mainAttributes.getValue(BUNDLE_SYMBOLICNAME);
            Validate.isTrue(StringUtils.isNotEmpty(bsn), "Bundle symbolic name is blank!!");
            String bundleVersion = mainAttributes.getValue(BUNDLE_VERSION);
            switch (op) {
                case INSTALL:
                    this.getLog().info(String.format(INSTALL_MSG, bundleName, bsn, bundleVersion));
                    break;
                case UNINSTALL:
                    this.getLog().info(String.format(UNINSTALL_MSG, bundleName, bsn, bundleVersion));
                    break;
            }
        } catch (IOException ex) {
            this.getLog().error(ex);
        }
        return bsn;
    }

    private boolean parseResponse(CloseableHttpResponse authResponse) {
        String sessionId = null;
        for (Header header : authResponse.getAllHeaders()) {
            String headerName = header.getName();
            if (HEADER_SET_COOKIE.equals(headerName)) {
                for (String part : header.getValue().split(REGEX_SEMI_COLON)) {
                    if (part.startsWith(HEADER_JSESSIONID)) {
                        sessionId = part.split(REGEX_EQ)[1];
                        break;
                    }
                }
            } else if (HEADER_JSESSIONID.equals(headerName)) {
                sessionId = header.getValue();
                break;
            }
        }
        return StringUtils.isNotEmpty(sessionId);
    }
}