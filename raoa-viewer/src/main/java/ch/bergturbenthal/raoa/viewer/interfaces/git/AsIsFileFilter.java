/*
 * Copyright (C) 2009-2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package ch.bergturbenthal.raoa.viewer.interfaces.git;

import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import ch.bergturbenthal.raoa.viewer.interfaces.git.resolver.AsIsFileService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

class AsIsFileFilter implements Filter {
  private final AsIsFileService asIs;

  AsIsFileFilter(AsIsFileService getAnyFile) {
    this.asIs = getAnyFile;
  }

  /** {@inheritDoc} */
  @Override
  public void init(FilterConfig config) throws ServletException {
    // Do nothing.
  }

  /** {@inheritDoc} */
  @Override
  public void destroy() {
    // Do nothing.
  }

  /** {@inheritDoc} */
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;
    try {
      Repository db = ServletUtils.getRepository(request);
      asIs.access(req, db);
      chain.doFilter(request, response);
    } catch (ServiceNotAuthorizedException e) {
      res.sendError(SC_UNAUTHORIZED, e.getMessage());
    } catch (ServiceNotEnabledException e) {
      res.sendError(SC_FORBIDDEN, e.getMessage());
    }
  }
}
