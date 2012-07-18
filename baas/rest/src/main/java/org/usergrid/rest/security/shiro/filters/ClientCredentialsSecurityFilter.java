/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.rest.security.shiro.filters;

import static org.apache.commons.lang.StringUtils.isNotBlank;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;

import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.usergrid.security.shiro.PrincipalCredentialsToken;
import org.usergrid.security.shiro.utils.SubjectUtils;

import com.sun.jersey.spi.container.ContainerRequest;

@Component
public class ClientCredentialsSecurityFilter extends SecurityFilter {

	private static final Logger logger = LoggerFactory
			.getLogger(ClientCredentialsSecurityFilter.class);

	@Context
	protected HttpServletRequest httpServletRequest;

	public ClientCredentialsSecurityFilter() {
		logger.info("ClientCredentialsSecurityFilter is installed");
	}

	@Override
	public ContainerRequest filter(ContainerRequest request) {
		String clientId = httpServletRequest.getParameter("client_id");
		String clientSecret = httpServletRequest.getParameter("client_secret");

		if (isNotBlank(clientId) && isNotBlank(clientSecret)) {
			try {
				PrincipalCredentialsToken token = management
						.getPrincipalCredentialsTokenForClientCredentials(
								clientId, clientSecret);
				Subject subject = SubjectUtils.getSubject();
				subject.login(token);
			} catch (Exception e) {
			}
		}
		return request;
	}

}
