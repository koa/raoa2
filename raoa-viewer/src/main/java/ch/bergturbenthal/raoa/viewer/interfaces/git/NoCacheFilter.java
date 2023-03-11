/*
 * Copyright (C) 2010, Google Inc. and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package ch.bergturbenthal.raoa.viewer.interfaces.git;

import static org.eclipse.jgit.util.HttpSupport.HDR_CACHE_CONTROL;
import static org.eclipse.jgit.util.HttpSupport.HDR_EXPIRES;
import static org.eclipse.jgit.util.HttpSupport.HDR_PRAGMA;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Add HTTP response headers to prevent caching by proxies/browsers. */
class NoCacheFilter implements Filter {
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
    HttpServletResponse rsp = (HttpServletResponse) response;

    rsp.setHeader(HDR_EXPIRES, "Fri, 01 Jan 1980 00:00:00 GMT");
    rsp.setHeader(HDR_PRAGMA, "no-cache");

    final String nocache = "no-cache, max-age=0, must-revalidate";
    rsp.setHeader(HDR_CACHE_CONTROL, nocache);

    chain.doFilter(request, response);
  }
}
